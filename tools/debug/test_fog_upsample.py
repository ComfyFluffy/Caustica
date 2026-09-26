"""Independent references for composing reduced-resolution fog columns; these tests do not execute GPU shaders.

A two-dimensional x/z world is viewed from the origin along +z through one row of trace pixels. Each fog
column integrates along its center ray through every surface up to its conservative depth limit, as the
fog pass does, and each trace pixel evaluates neighboring columns at its own first-surface distance.
"""
import unittest

import numpy as np

SIGMA = 0.02
MAXIMUM = 64.0
STEPS = 64
PIXELS = 256
DIVISOR = 8
COLUMNS = PIXELS // DIVISOR
TAN_HALF = np.tan(np.radians(30.0))
PENETRATION = 0.1


def pixel_direction(u):
    slope = (2.0 * u - 1.0) * TAN_HALF
    return np.array([slope, 1.0]) / np.hypot(slope, 1.0)


def box_hit(direction, box):
    # No pixel or column center lies on the view axis, so the x component is never zero.
    x0, x1, z0, z1 = box
    tx = np.sort([x0 / direction[0], x1 / direction[0]])
    tz = np.sort([z0 / direction[1], z1 / direction[1]])
    near, far = max(tx[0], tz[0]), min(tx[1], tz[1])
    return near if 0 < near <= far else np.inf


class World:
    def __init__(self, boxes=(), background=None, receding=None, dark_slab=(0.0, 0.0), dark_from=0.0):
        self.boxes = boxes
        self.background = background
        self.receding = receding
        self.dark_slab = dark_slab
        self.dark_from = dark_from

    def first_hit(self, direction):
        hit = min([box_hit(direction, box) for box in self.boxes], default=np.inf)
        if self.background is not None:
            hit = min(hit, self.background / direction[1])
        if self.receding is not None:
            # Plane z = z0 + x * rate, seen at an increasingly grazing angle toward +x.
            z0, rate = self.receding
            denominator = direction[1] - rate * direction[0]
            if denominator > 0:
                hit = min(hit, z0 / denominator)
        return min(hit, MAXIMUM)

    def lit(self, points):
        # Light travels along +z, so an occluder shadows the slab of x it covers beyond its front face.
        x0, x1 = self.dark_slab
        dark = (points[..., 0] >= x0) & (points[..., 0] <= x1) & (points[..., 1] >= self.dark_from)
        if self.receding is not None:
            # The receding plane bounds solid ground; no light reaches points behind it.
            z0, rate = self.receding
            dark |= points[..., 1] > z0 + rate * points[..., 0]
        return ~dark


def pixel_distances(world):
    return np.array([world.first_hit(pixel_direction((k + 0.5) / PIXELS)) for k in range(PIXELS)])


def column_limits(distances):
    # Greatest first-surface distance over the pixels a column's bilinear support can reach.
    limits = np.empty(COLUMNS)
    for c in range(COLUMNS):
        low = max(0, (c - 1) * DIVISOR + DIVISOR // 2 - 1)
        high = min(PIXELS, (c + 1) * DIVISOR + DIVISOR // 2 + 1)
        limits[c] = distances[low:high].max()
    return limits


def column_transport(world, c, limit, distance):
    direction = pixel_direction((c + 0.5) / COLUMNS)
    edges = np.minimum(MAXIMUM * (np.arange(STEPS + 1) / STEPS) ** 2, limit)
    start, end = edges[:-1], edges[1:]
    source = world.lit(direction * ((start + end) / 2)[:, None]).astype(float)
    clipped = np.minimum(end, distance)
    scattering = np.sum(np.where(start < distance, source * (np.exp(-SIGMA * start) - np.exp(-SIGMA * clipped)), 0))
    return scattering, np.exp(-SIGMA * min(distance, limit))


def reference_transport(world, u, distance, count=8192):
    direction = pixel_direction(u)
    edges = np.linspace(0.0, distance, count + 1)
    source = world.lit(direction * ((edges[:-1] + edges[1:]) / 2)[:, None]).astype(float)
    return np.sum(source * (np.exp(-SIGMA * edges[:-1]) - np.exp(-SIGMA * edges[1:]))), np.exp(-SIGMA * distance)


def compose(world, depth_aware):
    distances = pixel_distances(world)
    limits = column_limits(distances)
    # With zero jitter the sample nearest a column's center ray is the pixel just right of that center.
    column_distances = distances[np.arange(COLUMNS) * DIVISOR + DIVISOR // 2]
    result = np.empty((PIXELS, 2))
    for k, distance in enumerate(distances):
        grid = (k + 0.5) / PIXELS * COLUMNS - 0.5
        base = int(np.floor(grid))
        blend = grid - base
        volume, total = np.zeros(2), 0.0
        for offset, bilinear in ((0, 1.0 - blend), (1, blend)):
            c = min(max(base + offset, 0), COLUMNS - 1)
            weight = bilinear
            if depth_aware:
                penetration = max(0.0, distance - column_distances[c]) / (PENETRATION * distance)
                weight /= 1.0 + penetration ** 4
            volume += weight * np.array(column_transport(world, c, limits[c], distance))
            total += weight
        result[k] = volume / total
    return result, distances


def reference(world):
    distances = pixel_distances(world)
    return np.array([reference_transport(world, (k + 0.5) / PIXELS, d) for k, d in enumerate(distances)])


class FogUpsampleTest(unittest.TestCase):
    def test_columns_through_a_foreground_occluder_no_longer_darken_the_background(self):
        # The occluder's right edge lies on the view axis; the columns just left of it run through its shadow.
        world = World(boxes=[(-1.5, 0.0, 5.0, 6.0)], background=40.0, dark_slab=(-1.5, 0.0), dark_from=5.0)
        exact = reference(world)
        bilinear, distances = compose(world, depth_aware=False)
        aware, _ = compose(world, depth_aware=True)
        background = distances > 30.0
        edge = background & (np.abs(np.arange(PIXELS) - PIXELS / 2) < 2 * DIVISOR)
        bilinear_error = np.abs(bilinear[edge, 0] - exact[edge, 0]).max()
        aware_error = np.abs(aware[edge, 0] - exact[edge, 0]).max()
        self.assertGreater(bilinear_error, 0.25 * exact[edge, 0].min())
        self.assertLess(aware_error, bilinear_error / 20)
        # Foreground pixels already evaluate clear neighbors inside their own short path.
        foreground = ~background
        self.assertLessEqual(np.abs(aware[foreground] - exact[foreground]).max(),
                             np.abs(bilinear[foreground] - exact[foreground]).max() + 1e-12)

    def test_columns_clear_to_the_pixel_endpoint_keep_bilinear_weights(self):
        # Environment everywhere: every column is clear, so the composition is exactly bilinear.
        sky = World(dark_slab=(-3.0, 0.5), dark_from=10.0)
        np.testing.assert_array_equal(compose(sky, True)[0], compose(sky, False)[0])
        # A camera-facing wall differs from its columns only by projection, well inside the tolerance.
        wall = World(background=40.0, dark_slab=(-3.0, 0.5), dark_from=10.0)
        np.testing.assert_allclose(compose(wall, True)[0], compose(wall, False)[0], rtol=2e-3, atol=0)

    def test_grazing_surface_weights_change_continuously(self):
        # On a plane receding toward +x, the nearer neighboring column meets the plane before the pixel's
        # endpoint. Its weight ramps with the penetrated fraction instead of switching, so adjacent pixels
        # differ no more than in the reference.
        world = World(receding=(6.0, 1.5), dark_slab=(0.3, 0.6), dark_from=0.0)
        exact = reference(world)
        aware, distances = compose(world, depth_aware=True)
        bilinear, _ = compose(world, depth_aware=False)
        surface = distances < MAXIMUM
        steps = np.abs(np.diff(aware[:, 0]))[surface[1:] & surface[:-1]]
        exact_steps = np.abs(np.diff(exact[:, 0]))[surface[1:] & surface[:-1]]
        self.assertLess(steps.max(), 2.0 * exact_steps.max())
        self.assertLessEqual(np.abs(aware[surface, 0] - exact[surface, 0]).mean(),
                             np.abs(bilinear[surface, 0] - exact[surface, 0]).mean())


if __name__ == "__main__":
    unittest.main()
