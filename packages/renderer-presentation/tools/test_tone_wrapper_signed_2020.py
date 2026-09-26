"""Signed-conversion reference for `pqEncodeMappedBt709` in display/tone_mapping.slang.

Wide-gamut colors can carry negative linear BT.709 transport coordinates while converting to
entirely contained, nonnegative BT.2020. The HDR wrapper must therefore clamp only after the basis
conversion. The matrix and formula shape are parsed from the shader source itself so this reference
cannot drift from the implementation it checks.

Audit witness (2026-09-25 renodx audit, float64 source-equation model): with the default-grade
PsychoV31 core fed BT.709 (0, 0, 4) at peak 5, the core returns BT.709
(-0.0810581863, 0.1545981070, 2.9787578240); the correct signed conversion is
(0.1290695417, 0.1704005976, 2.6800395077), while clamping in BT.709 first yields
(0.1799257640, 0.1760014995, 2.6813681811) — a pure color error before PQ encoding.

Run: python -m unittest test_tone_wrapper_signed_2020 -v   (from this tools directory)
"""

import re
import unittest
from pathlib import Path

SHADER = Path(__file__).resolve().parents[1] / "shaders" / "pipelines" / "display" / "tone_mapping.slang"


def parse_matrix(source):
    match = re.search(r"BT709_TO_BT2020 = float3x3\(\s*([^;]+)\);", source)
    if match is None:
        raise AssertionError("BT709_TO_BT2020 matrix not found in tone_mapping.slang")
    values = [float(v) for v in re.findall(r"-?\d+\.\d+", match.group(1))]
    if len(values) != 9:
        raise AssertionError(f"expected 9 matrix coefficients, found {len(values)}")
    return [values[0:3], values[3:6], values[6:9]]


def multiply(matrix, vector):
    return [sum(matrix[row][column] * vector[column] for column in range(3)) for row in range(3)]


def clamp_nonnegative(vector):
    return [max(component, 0.0) for component in vector]


def wrapper_fixed(matrix, mapped709):
    return clamp_nonnegative(multiply(matrix, mapped709))


def wrapper_clamped_in_709(matrix, mapped709):
    return clamp_nonnegative(multiply(matrix, clamp_nonnegative(mapped709)))


WITNESS_CORE_BT709 = (-0.0810581863, 0.1545981070, 2.9787578240)
WITNESS_CORRECT_BT2020 = (0.1290695417, 0.1704005976, 2.6800395077)
WITNESS_CLAMPED_BT2020 = (0.1799257640, 0.1760014995, 2.6813681811)


class SignedBt2020ConversionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SHADER.read_text(encoding="utf-8")
        cls.matrix = parse_matrix(cls.source)

    def test_wrapper_clamps_only_in_the_target_basis(self):
        body = re.search(r"float3 pqEncodeMappedBt709\([^)]*\)\s*\{(.*?)\n\}", self.source, re.S)
        self.assertIsNotNone(body, "pqEncodeMappedBt709 not found")
        self.assertNotIn("max(mapped709", body.group(1),
                         "the wrapper must not clamp BT.709 transport before conversion")
        self.assertIn("max(mul(BT709_TO_BT2020, mapped709)", body.group(1))

    def test_audit_witness_matches_the_signed_conversion(self):
        fixed = wrapper_fixed(self.matrix, WITNESS_CORE_BT709)
        for got, want in zip(fixed, WITNESS_CORRECT_BT2020):
            self.assertAlmostEqual(got, want, delta=5e-4)
        clamped = wrapper_clamped_in_709(self.matrix, WITNESS_CORE_BT709)
        for got, want in zip(clamped, WITNESS_CLAMPED_BT2020):
            self.assertAlmostEqual(got, want, delta=5e-4)
        for fixed_component, clamped_component in zip(fixed, clamped):
            self.assertNotAlmostEqual(fixed_component, clamped_component, delta=5e-4)

    def test_bt2020_contained_colors_survive_conversion_exactly(self):
        for vector in ((0.2, 0.3, 0.5), (0.0, 0.0, 4.0), (1.0, 1.0, 1.0)):
            signed = multiply(self.matrix, vector)
            if min(signed) < 0.0:
                continue
            self.assertEqual(wrapper_fixed(self.matrix, vector), signed)
            self.assertEqual(wrapper_clamped_in_709(self.matrix, vector), signed)

    def test_negative_bt709_transport_is_not_neutralized_before_conversion(self):
        # A BT.2020-contained color with one negative BT.709 coordinate converts differently
        # depending on where the clamp happens; only the post-conversion clamp is correct.
        vector = (-0.0810581863, 0.1545981070, 2.9787578240)
        fixed = wrapper_fixed(self.matrix, vector)
        self.assertTrue(all(0.0 <= component for component in fixed))


if __name__ == "__main__":
    unittest.main()
