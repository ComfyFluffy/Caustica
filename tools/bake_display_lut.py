#!/usr/bin/env python3
"""Bakes the RT renderer's ACES display-transform LUTs and imports its packaged LMT.

The renderer feeds these LUTs scene-linear ACEScg (AP1/D60) radiance, already multiplied by the
auto-exposure scalar (RtExposure). The default look package's scene-referred LMT runs first, followed by ACES
2.0 output transform, gamut mapping, tone scale, and display transfer function.

One SDR LUT (BT.709, sRGB OETF) plus one HDR LUT per REC2020 mastering-nits target ACES 2.0 ships
(500/1000/2000/4000 -- see HDR_REC2020_NITS). Hdr.PEAK_NITS selects one of the baked HDR LUTs.
The package LMT is a separate log-to-log scene-referred table, so it does not duplicate all five
output LUTs.

Requires: pip install opencolorio numpy  (tested with opencolorio 2.5.2 / numpy 2.5.1, Python 3.14)

Usage:
    python tools/bake_display_lut.py
    python tools/bake_display_lut.py --import-lmt path/to/resolve-export.cube

Regenerate whenever SHAPER_LO/HI, LUT_SIZE, or the OCIO config/view below changes. The baked
.bin files are committed binary resources. The display transforms live in
src/main/resources/caustica/color/luts/; the sole LMT lives beside the default package JSON.
"""
import argparse
import hashlib
import struct
import sys
from pathlib import Path

import numpy as np
import PyOpenColorIO as OCIO

# OCIO 2.2+ ships this config compiled into the library -- no external config file/network fetch
# needed. This is the renderer's pinned ACES 2.0 color configuration.
OCIO_BUILTIN_CONFIG = "cg-config-v4.0.0_aces-v2.0_ocio-v2.5"
SOURCE_SPACE = "ACEScg"  # matches the renderer's scene-linear ACEScg/AP1/D60 working space

# Log2 shaper range, in stops relative to linear 1.0. Matches LOG_MIN/LOG_MAX in
# shaders/display/exposure_hist.comp and exposure_resolve.comp -- same renderer quantity metered
# in both places, so the same bounds. Input is exposed scene-linear (may exceed 1.0 for
# unclipped-highlight emitters), so headroom above 0 stops matters, not just below.
SHAPER_LO_STOPS = -12.0
SHAPER_HI_STOPS = 12.0

LUT_SIZE = 65  # samples per axis; N^3 total
OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/caustica/color/luts"
LOOK_PACKAGE_DIR = (
    Path(__file__).resolve().parent.parent / "src/main/resources/caustica/color/looks/default"
)

# ACES 2.0's built-in BT.2020 transforms use these fixed HDR mastering targets.
HDR_REC2020_NITS = [500, 1000, 2000, 4000]

# sobotka/AgX, the config the "Ultra Realism Tonemapper for UE5" guide installs into Unreal via OCIO.
# Baked here as an alternative SDR view transform so the same look is available without a second
# runtime dependency: the guide's Unreal setup and this LUT resolve to the same image transform.
#
# SDR ONLY, and not an oversight. Every view in this config terminates in a display encoding for a
# ~100 nit SDR device (sRGB, BT.1886, Display P3); AgX has no HDR output transform, so there is
# nothing to bake for the PQ path. The HDR LUTs stay ACES 2.0 whatever this is set to.
AGX_CONFIG_URL = "https://github.com/sobotka/AgX"  # clone and pass --agx-config <path>/config.ocio

# ACEScg (AP1/D60) -> linear BT.709 (D65). This is the exact inverse of BT709_TO_ACESCG_* in
# world_common.slang rather than a matrix from a table: the renderer converts sRGB assets INTO ACEScg
# with that matrix, and the AgX config's input space is Linear BT.709, so inverting the renderer's own
# matrix makes the round trip exact instead of leaving a small residual tint on neutrals.
ACESCG_TO_BT709 = [
    [1.70505091, -0.62179208, -0.08325886],
    [-0.13025641, 1.14080473, -0.01054832],
    [-0.02400335, -0.12896898, 1.15297234],
]

AGX_LUTS = [
    dict(
        name="sdr_agx_punchy_rec709",
        agx_space="Appearance Punchy sRGB",
        note="SDR output, AgX Punchy appearance, sRGB-encoded BT.709. The guide's default choice; "
             "less aggressive desaturation than AgX Base.",
    ),
    dict(
        name="sdr_agx_base_rec709",
        agx_space="AgX Base",
        note="SDR output, AgX Base, sRGB-encoded BT.709. Stronger highlight desaturation than Punchy.",
    ),
]

LUTS = [
    dict(
        name="sdr_aces2_rec709",
        display_view=("sRGB - Display", "ACES 2.0 - SDR 100 nits (Rec.709)"),
        note="SDR output, BT.709 display code values (renderer's existing rgba8 gamma-encoded "
             "presentation path expects sRGB-OETF-encoded BT.709, same as the AgX path it replaces).",
    ),
] + [
    dict(
        name=f"hdr_aces2_rec2020_{nits}nit",
        # Composed directly from BuiltinTransform pieces (verified bit-exact against the config's
        # own Display/View path for 1000nit, the only nits value pre-wired as a named View) rather
        # than via getProcessor(display, view, ...): ACES2065-1_to_CIE-XYZ-D65 is the ACES 2 output
        # transform itself; CIE-XYZ-D65_to_REC.2100-PQ is the final display encode, matching
        # VK_COLOR_SPACE_HDR10_ST2084_EXT's container exactly, so no separate gamut step or
        # pqEncode() needed at sample time.
        builtin_chain=[
            ("colorspace", ("ACEScg", "ACES2065-1")),
            ("builtin", f"ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - HDR-{nits}nit-REC2020_2.0"),
            ("builtin", "DISPLAY - CIE-XYZ-D65_to_REC.2100-PQ"),
        ],
        note=f"HDR output, {nits} nit peak, BT.2020 primaries + ST.2084/PQ-encoded code values.",
    )
    for nits in HDR_REC2020_NITS
]


def shaper_axis(size: int) -> np.ndarray:
    t = np.linspace(0.0, 1.0, size, dtype=np.float64)
    stops = SHAPER_LO_STOPS + t * (SHAPER_HI_STOPS - SHAPER_LO_STOPS)
    return np.exp2(stops)


# BT.709 (D65) -> BT.2020 (D65). Both are D65 so this is a pure primaries change, no chromatic
# adaptation, which is why it can be a plain 3x3 with no CAT term.
BT709_TO_BT2020 = [
    [0.62740390, 0.32928304, 0.04331307],
    [0.06909729, 0.91954040, 0.01136232],
    [0.01639144, 0.08801331, 0.89559525],
]

# SMPTE ST 2084 (PQ) constants.
PQ_M1 = 2610.0 / 16384.0
PQ_M2 = 128.0 * 2523.0 / 4096.0
PQ_C1 = 3424.0 / 4096.0
PQ_C2 = 32.0 * 2413.0 / 4096.0
PQ_C3 = 32.0 * 2392.0 / 4096.0
PQ_PEAK_NITS = 10000.0

# HDR AgX. READ THIS BEFORE TRUSTING THE NAME.
#
# sobotka's config has no HDR view transform -- every view in it terminates in a ~100 nit SDR display
# encoding. So this is NOT a port of an upstream AgX HDR view, because no such thing exists. It is a
# construction:
#
#   ACEScg -> AgX Punchy (display code values) -> inverse sRGB EOTF -> display-linear 0..1
#          -> scale so display white lands at DIFFUSE_WHITE_NITS -> BT.2020 -> PQ encode
#
# What that gives you is AgX's tone curve and its characteristic highlight desaturation, presented
# through the PQ pipe, with diffuse white placed at a sane HDR level instead of being crushed to the
# SDR 100 nit assumption. What it does NOT give you is extra highlight range: AgX's sigmoid still
# rolls off to its own white point, so specular highlights do not extend to your display's peak the
# way ACES 2.0's HDR output transforms do. Extending the sigmoid's shoulder to reach 1000+ nits would
# mean redesigning AgX's tone curve, which is a different thing from baking it.
#
# Use this if you want the AgX look on an HDR display. Use ACES 2.0 if you want HDR highlight range.
DIFFUSE_WHITE_NITS = 203.0  # ITU-R BT.2408 reference diffuse white

AGX_HDR_LUTS = [
    dict(
        name="hdr_agx_punchy_rec2020",
        agx_space="Appearance Punchy sRGB",
        note="AgX Punchy tone curve and look, BT.2020 primaries, PQ encoded, diffuse white at "
             "203 nits. See the caveat above: AgX look, not AgX-with-HDR-range.",
    ),
]


def srgb_eotf_inverse(code):
    """Display code values -> display-linear 0..1."""
    code = np.clip(code, 0.0, 1.0)
    return np.where(code <= 0.04045, code / 12.92, ((code + 0.055) / 1.055) ** 2.4)


def pq_encode(nits):
    """Absolute nits -> PQ code values."""
    y = np.clip(nits, 0.0, PQ_PEAK_NITS) / PQ_PEAK_NITS
    num = PQ_C1 + PQ_C2 * (y ** PQ_M1)
    den = 1.0 + PQ_C3 * (y ** PQ_M1)
    return (num / den) ** PQ_M2


def make_agx_hdr_processor(agx_cfg: "OCIO.Config", spec: dict):
    """Wraps the SDR AgX processor, then does the display-linear -> PQ stage in numpy."""
    inner = make_agx_processor(agx_cfg, spec)

    class _AgxHdr:
        def applyRGB(self, rgb):
            inner.applyRGB(rgb)
            arr = np.asarray(rgb, dtype=np.float32).reshape(-1, 3)
            display_linear = srgb_eotf_inverse(arr)
            nits = display_linear * DIFFUSE_WHITE_NITS
            rec2020 = nits @ np.array(BT709_TO_BT2020, dtype=np.float64).T
            encoded = pq_encode(np.clip(rec2020, 0.0, None)).astype(np.float32)
            arr[:] = encoded
            np.asarray(rgb, dtype=np.float32).reshape(-1, 3)[:] = arr

    return _AgxHdr()


def make_agx_processor(agx_cfg: "OCIO.Config", spec: dict):
    """ACEScg -> linear BT.709 -> the requested AgX appearance, as one processor."""
    matrix = [0.0] * 16
    for row in range(3):
        for col in range(3):
            matrix[row * 4 + col] = ACESCG_TO_BT709[row][col]
    matrix[15] = 1.0
    grp = OCIO.GroupTransform()
    grp.appendTransform(OCIO.MatrixTransform(matrix))
    grp.appendTransform(OCIO.ColorSpaceTransform(src="Linear BT.709", dst=spec["agx_space"]))
    return agx_cfg.getProcessor(grp).getDefaultCPUProcessor()


def make_processor(cfg: "OCIO.Config", spec: dict):
    if spec.get("agx_hdr"):
        return make_agx_hdr_processor(cfg, spec)
    if "agx_space" in spec:
        return make_agx_processor(cfg, spec)
    if "display_view" in spec:
        display, view = spec["display_view"]
        return cfg.getProcessor(SOURCE_SPACE, display, view, OCIO.TRANSFORM_DIR_FORWARD).getDefaultCPUProcessor()
    grp = OCIO.GroupTransform()
    for kind, arg in spec["builtin_chain"]:
        if kind == "colorspace":
            src, dst = arg
            grp.appendTransform(OCIO.ColorSpaceTransform(src=src, dst=dst))
        elif kind == "builtin":
            grp.appendTransform(OCIO.BuiltinTransform(style=arg))
        else:
            raise ValueError(f"unknown chain step kind: {kind}")
    return cfg.getProcessor(grp).getDefaultCPUProcessor()


def read_shaper_cube(path: Path) -> tuple[int, np.ndarray, str | None]:
    """Read a normalized .cube as a log-shaper-to-log-shaper scene look.

    A .cube file does not carry reliable color-space semantics. Imported creative curves are therefore
    defined over the renderer's normalized -12..+12 EV shaper coordinates, not over linear ACEScg values:
    applying a conventional [0,1] cube directly to scene-linear input would clamp all values above 1.0.
    The file order (R fastest, then G, then B) already matches the 3D Vulkan image layout used by write_lut.
    """
    size = None
    title = None
    domain_min = np.zeros(3, dtype=np.float64)
    domain_max = np.ones(3, dtype=np.float64)
    rows: list[list[float]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        directive = fields[0].upper()
        if directive == "TITLE":
            title = line[len(fields[0]):].strip().strip('"')
        elif directive == "LUT_3D_SIZE":
            if len(fields) != 2:
                raise ValueError(f"{path}:{line_number}: LUT_3D_SIZE requires one integer")
            size = int(fields[1])
        elif directive == "LUT_1D_SIZE":
            raise ValueError(f"{path}:{line_number}: 1D LUTs are not supported")
        elif directive in ("DOMAIN_MIN", "DOMAIN_MAX"):
            if len(fields) != 4:
                raise ValueError(f"{path}:{line_number}: {directive} requires three values")
            domain = np.asarray([float(value) for value in fields[1:]], dtype=np.float64)
            if directive == "DOMAIN_MIN":
                domain_min = domain
            else:
                domain_max = domain
        else:
            if len(fields) != 3:
                raise ValueError(f"{path}:{line_number}: unknown directive or malformed RGB row")
            try:
                rows.append([float(value) for value in fields])
            except ValueError as exc:
                raise ValueError(f"{path}:{line_number}: unknown directive {fields[0]!r}") from exc

    if size is None or size < 2:
        raise ValueError(f"{path}: missing or invalid LUT_3D_SIZE")
    expected_rows = size ** 3
    if len(rows) != expected_rows:
        raise ValueError(f"{path}: expected {expected_rows} RGB rows for {size}^3, got {len(rows)}")
    if not np.allclose(domain_min, 0.0) or not np.allclose(domain_max, 1.0):
        raise ValueError(
            f"{path}: imported shaper cubes must use DOMAIN_MIN 0 0 0 and DOMAIN_MAX 1 1 1")

    rgb = np.asarray(rows, dtype=np.float32).reshape(size, size, size, 3)
    if not np.isfinite(rgb).all():
        raise ValueError(f"{path}: LUT contains non-finite values")
    if float(rgb.min()) < 0.0 or float(rgb.max()) > 1.0:
        raise ValueError(f"{path}: shaper-domain LUT output must stay within [0,1]")
    return size, rgb, title


def bake_one(cfg: "OCIO.Config", spec: dict, size: int) -> np.ndarray:
    axis = shaper_axis(size)  # same axis reused for R, G, B -- the shaper is a per-channel diagonal
    # Grid shape (N,N,N,3) with R fastest-varying (x), G next (y), B slowest (z). This matches
    # VkBufferImageCopy's row-major layout for a 3D image of extent (N,N,N): x is the contiguous
    # texel run, so keep that axis == index 0 of the meshgrid arrays below, i.e. last numpy axis
    # before the channel axis. See decodeToneLut()'s texCoord order in display.comp.
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")  # b,g,r all shape (N,N,N)
    grid = np.stack([r, g, b], axis=-1).astype(np.float32)  # (N,N,N,3), fastest axis = r = x

    cpu = make_processor(cfg, spec)

    flat = grid.reshape(-1, 3).copy()
    cpu.applyRGB(flat)
    flat = np.clip(flat, 0.0, 1.0)
    return flat.reshape(size, size, size, 3)


def write_lut(path: Path, size: int, rgb: np.ndarray) -> None:
    # RGBA16F texel data (alpha unused, kept 1.0 for a well-defined value + simpler Vulkan format
    # matching: R16G16B16_SFLOAT support is patchy, RGBA16F is universal). Header is self-describing
    # so the Java loader doesn't need a second source of truth for size/shaper range.
    rgba = np.concatenate([rgb, np.ones((size, size, size, 1), dtype=np.float32)], axis=-1)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"CLUT")
        f.write(struct.pack("<I", 1))  # version
        f.write(struct.pack("<I", size))
        f.write(struct.pack("<ff", SHAPER_LO_STOPS, SHAPER_HI_STOPS))
        f.write(rgba.astype(np.float16).tobytes())
    print(f"wrote {path} ({path.stat().st_size} bytes, {size}^3 texels)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--import-lmt",
        type=Path,
        metavar="PATH",
        help="replace the default look package's LMT from a normalized log-shaper .cube, then exit",
    )
    parser.add_argument(
        "--agx-config",
        type=Path,
        metavar="PATH",
        help=f"bake the AgX SDR view transforms from a sobotka/AgX config.ocio ({AGX_CONFIG_URL}), "
             "then exit. The ACES 2.0 LUTs are left untouched.",
    )
    args = parser.parse_args()

    if args.agx_config is not None:
        agx_cfg = OCIO.Config.CreateFromFile(str(args.agx_config))
        digest = hashlib.sha256(Path(args.agx_config).read_bytes()).hexdigest()
        print(f"baking AgX view transforms from {args.agx_config}; config SHA-256={digest}")
        for spec in AGX_LUTS:
            print(f"baking {spec['name']}: {spec['note']}")
            rgb = bake_one(agx_cfg, spec, LUT_SIZE)
            write_lut(OUT_DIR / f"{spec['name']}.bin", LUT_SIZE, rgb)
        for spec in AGX_HDR_LUTS:
            print(f"baking {spec['name']}: {spec['note']}")
            spec = dict(spec, agx_hdr=True)
            rgb = bake_one(agx_cfg, spec, LUT_SIZE)
            write_lut(OUT_DIR / f"{spec['name']}.bin", LUT_SIZE, rgb)
        return

    if args.import_lmt is not None:
        source_path = args.import_lmt
        size, rgb, title = read_shaper_cube(source_path)
        digest = hashlib.sha256(source_path.read_bytes()).hexdigest()
        print(
            f"importing default-package LMT: {size}^3 normalized log-shaper cube"
            f"{f' ({title})' if title else ''}; source SHA-256={digest}"
        )
        write_lut(LOOK_PACKAGE_DIR / "lmt.bin", size, rgb)
        return

    cfg = OCIO.Config.CreateFromBuiltinConfig(OCIO_BUILTIN_CONFIG)
    for spec in LUTS:
        print(f"baking {spec['name']}: {spec['note']}")
        rgb = bake_one(cfg, spec, LUT_SIZE)
        write_lut(OUT_DIR / f"{spec['name']}.bin", LUT_SIZE, rgb)

if __name__ == "__main__":
    sys.exit(main())
