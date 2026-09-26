#!/usr/bin/env python3
"""Bakes the RT renderer's ACES display-transform LUTs and imports its LMT.

The renderer feeds these LUTs scene-linear ACEScg (AP1/D60) radiance, already multiplied by the
auto-exposure scalar (RtExposure). The scene-referred LMT runs first, followed by ACES
2.0 output transform, gamut mapping, tone scale, and display transfer function.

One SDR LUT (BT.709, sRGB OETF) plus one HDR LUT per REC2020 mastering-nits target ACES 2.0 ships
(500/1000/2000/4000 -- see HDR_REC2020_NITS). The ACES 2.0 HDR mode renders through the LUT nearest Hdr.PEAK_NITS.
The LMT is a separate log-to-log scene-referred table, so it does not duplicate all five
output LUTs.

Dependencies are declared in the repository's pyproject.toml and uv.lock.

Usage:
    uv run python packages/renderer-presentation/tools/bake_display_lut.py
    uv run python packages/renderer-presentation/tools/bake_display_lut.py --import-lmt path/to/resolve-export.cube

Regenerate whenever SHAPER_LO/HI, LUT_SIZE, or the OCIO config/view below changes. The baked
.bin files are committed binary resources. The display transforms live in
packages/renderer-presentation/src/main/resources/caustica/color/luts/.
"""
import argparse
import hashlib
import struct
from pathlib import Path

import numpy as np
import PyOpenColorIO as OCIO

# OCIO ships this pinned ACES 2.0 config in the library; no external config file is needed.
OCIO_BUILTIN_CONFIG = "cg-config-v4.0.0_aces-v2.0_ocio-v2.5"
SOURCE_SPACE = "ACEScg"  # matches the renderer's scene-linear ACEScg/AP1/D60 working space

# Log2 shaper range relative to linear 1.0, shared with the display shader and LUT loader.
# Exposed scene-linear input can exceed 1.0, so the range includes highlight headroom.
SHAPER_LO_STOPS = -12.0
SHAPER_HI_STOPS = 12.0

LUT_SIZE = 65  # samples per axis; N^3 total
PRESENTATION_RESOURCES = (
    Path(__file__).resolve().parent.parent / "src/main/resources"
)
OUT_DIR = PRESENTATION_RESOURCES / "caustica/color/luts"

# ACES 2.0's built-in BT.2020 transforms use these fixed HDR mastering targets.
HDR_REC2020_NITS = [500, 1000, 2000, 4000]

LUTS = [
    dict(
        name="sdr_aces2_rec709",
        display_view=("sRGB - Display", "ACES 2.0 - SDR 100 nits (Rec.709)"),
        note="SDR output, sRGB-OETF-encoded BT.709 code values for RGBA8 presentation.",
    ),
] + [
    dict(
        name=f"hdr_aces2_rec2020_{nits}nit",
        # The built-in chain selects each mastering target explicitly. Its final transform
        # produces PQ-encoded BT.2020 for the HDR10 swapchain; the shader must not encode it again.
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


def make_processor(cfg: "OCIO.Config", spec: dict):
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
    # Vulkan's x coordinate varies fastest. Store R on the last spatial NumPy axis,
    # followed by G and B, so each contiguous row corresponds to increasing red input.
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")  # b,g,r all shape (N,N,N)
    grid = np.stack([r, g, b], axis=-1).astype(np.float32)  # (N,N,N,3), fastest axis = r = x

    cpu = make_processor(cfg, spec)

    flat = grid.reshape(-1, 3).copy()
    cpu.applyRGB(flat)
    flat = np.clip(flat, 0.0, 1.0)
    return flat.reshape(size, size, size, 3)


def write_lut(path: Path, size: int, rgb: np.ndarray) -> None:
    # The loader consumes little-endian RGBA16F texels, with R varying fastest and unused alpha at 1.
    # The header supplies the dimensions and shaper range used to validate and upload the texture.
    rgba = np.concatenate([rgb, np.ones((size, size, size, 1), dtype=np.float32)], axis=-1)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"CLUT")
        f.write(struct.pack("<I", 1))  # version
        f.write(struct.pack("<I", size))
        f.write(struct.pack("<ff", SHAPER_LO_STOPS, SHAPER_HI_STOPS))
        f.write(rgba.astype("<f2").tobytes())
    print(f"wrote {path} ({path.stat().st_size} bytes, {size}^3 texels)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--import-lmt",
        type=Path,
        metavar="PATH",
        help="replace the LMT from a normalized log-shaper .cube, then exit",
    )
    args = parser.parse_args()

    if args.import_lmt is not None:
        source_path = args.import_lmt
        size, rgb, title = read_shaper_cube(source_path)
        digest = hashlib.sha256(source_path.read_bytes()).hexdigest()
        print(
            f"importing LMT: {size}^3 normalized log-shaper cube"
            f"{f' ({title})' if title else ''}; source SHA-256={digest}"
        )
        write_lut(OUT_DIR / "lmt.bin", size, rgb)
        return

    cfg = OCIO.Config.CreateFromBuiltinConfig(OCIO_BUILTIN_CONFIG)
    for spec in LUTS:
        print(f"baking {spec['name']}: {spec['note']}")
        rgb = bake_one(cfg, spec, LUT_SIZE)
        write_lut(OUT_DIR / f"{spec['name']}.bin", LUT_SIZE, rgb)

if __name__ == "__main__":
    main()
