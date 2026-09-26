"""Analytic reference for spatial-medium transmittance on light connections.

Offline math check for `traceSpatialLightTransmittance` in
`packages/renderer-raytracing/src/main/resources/caustica/shaders/world/trace_transport.slang`:
the quadrature ladder (1/2/4/8 Gauss nodes by leg length, 256 m cap) must reproduce analytic
transmittance for homogeneous media and stay bounded and monotone for arbitrary node densities.
Light-leg optical depth is varied independently of camera-leg optical depth; the two legs share no
state. Before the light-connection fix the shader returned exactly 1.0 on every outdoor light leg,
making a 100 m connection through extinction 0.01/m read 2.718x too bright.

Run: python tools/debug/check_light_transmittance.py
"""

import math
import sys

SPATIAL_QUADRATURE = [
    (0.5, 1.0),
    (0.2113248654051871, 0.5), (0.7886751345948129, 0.5),
    (0.0694318442029737, 0.1739274225687269), (0.3300094782075719, 0.3260725774312731),
    (0.6699905217924281, 0.3260725774312731), (0.9305681557970263, 0.1739274225687269),
    (0.0198550717512319, 0.0506142681451882), (0.1016667612931867, 0.1111905172266873),
    (0.2372337950418355, 0.1568533229389437), (0.4082826787521751, 0.1813418916891810),
    (0.5917173212478249, 0.1813418916891810), (0.7627662049581645, 0.1568533229389437),
    (0.8983332387068133, 0.1111905172266873), (0.9801449282487681, 0.0506142681451882),
]

MAXIMUM_DISTANCE_METERS = 256.0


def sample_count(length_meters):
    if length_meters <= 1.0:
        return 1
    if length_meters <= 4.0:
        return 2
    if length_meters <= 8.0:
        return 4
    return 8


def light_transmittance(extinction_at, distance_meters):
    """Mirror of the shader helper: extinction_at(fraction in [0, 1]) -> scalar extinction."""
    length = min(distance_meters, MAXIMUM_DISTANCE_METERS)
    transmittance = 1.0
    for node, weight in nodes(length):
        transmittance *= math.exp(-extinction_at(node) * (length * weight))
    return transmittance


def nodes(length_meters):
    count = sample_count(length_meters)
    return SPATIAL_QUADRATURE[count - 1:count - 1 + count]


def check_homogeneous_is_exact():
    for sigma in (0.001, 0.01, 0.1, 1.0):
        for length in (0.5, 3.0, 6.0, 20.0, 100.0, 256.0, 400.0):
            capped = min(length, MAXIMUM_DISTANCE_METERS)
            got = light_transmittance(lambda _: sigma, length)
            want = math.exp(-sigma * capped)
            assert abs(got - want) < 1e-12, (sigma, length, got, want)
    print("homogeneous legs reproduce exp(-sigma*L) exactly (weights sum to 1)")


def check_audit_witness():
    sigma, length = 0.01, 100.0
    got = light_transmittance(lambda _: sigma, length)
    want = math.exp(-0.01 * 100.0)
    assert abs(got - want) < 1e-12, (got, want)
    print(f"audit witness: 100 m leg at extinction {sigma}/m -> {got:.6f} "
          f"(pre-fix shader returned 1.0; the direct source read {1.0 / got:.3f}x too bright)")


def check_light_leg_independent_of_camera_leg():
    # Transmittance depends only on the light leg's own optical depth, never on any camera-leg value.
    for camera_sigma in (0.0, 0.02, 0.5):
        for light_sigma in (0.0, 0.005, 0.05):
            got = light_transmittance(lambda _: light_sigma, 80.0)
            assert abs(got - math.exp(-light_sigma * 80.0)) < 1e-12
    print("light-leg transmittance is independent of camera-leg optical depth")


def check_monotone_and_bounded():
    previous = 1.0
    for sigma in (0.0, 0.002, 0.01, 0.05, 0.2, 1.0, 5.0):
        value = light_transmittance(lambda _: sigma, 64.0)
        assert 0.0 <= value <= 1.0
        assert value <= previous + 1e-15
        previous = value
    for length in (1.0, 8.0, 64.0, 256.0, 1024.0):
        value = light_transmittance(lambda _: 0.02, length)
        assert 0.0 <= value <= 1.0
    print("transmittance is bounded [0, 1] and monotone in extinction and length")


def check_inactive_and_empty_medium():
    assert light_transmittance(lambda _: 0.0, 100.0) == 1.0
    assert light_transmittance(lambda _: 0.0, 0.0) == 1.0
    print("zero-extinction and empty legs transmit 1.0")


def main():
    check_homogeneous_is_exact()
    check_audit_witness()
    check_light_leg_independent_of_camera_leg()
    check_monotone_and_bounded()
    check_inactive_and_empty_medium()
    print("light-connection transmittance reference: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
