package dev.comfyfluffy.caustica.rt.entity;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The visibility algebra behind coexisting player representations. An instance is visible to a ray when
 * its TLAS mask AND the ray's domain is non-zero, so the whole feature reduces to which bits each side
 * sets. The masks are read reflectively out of {@link RtEntities} rather than restated here: a test that
 * declared its own copies would keep passing after someone changed the real ones.
 */
final class RtVisibilityDomainTest {
    private static final int CULL_SECONDARY = 0x01;
    private static final int CULL_PRIMARY = 0x02;
    private static final int CULL_LOCAL_VIEW_SECONDARY = 0x04;
    private static final int CULL_REFLECTION = 0x08;

    private static int mask(String name) throws ReflectiveOperationException {
        Field field = RtEntities.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void maskConstantsMatchTheShaderDomainBits() throws ReflectiveOperationException {
        assertEquals(CULL_SECONDARY, mask("MASK_SECONDARY"));
        assertEquals(CULL_PRIMARY, mask("MASK_PRIMARY"));
        assertEquals(CULL_LOCAL_VIEW_SECONDARY, mask("MASK_LOCAL_VIEW_SECONDARY"));
        assertEquals(0xFF, mask("MASK_ALL"));
        // The particle mask is primary-only; a new domain must not have widened it.
        assertEquals(CULL_PRIMARY, mask("PARTICLE_MASK"));
    }

    @Test
    void visibilityMatchesTheFrozenDomainTable() throws ReflectiveOperationException {
        int terrain = mask("MASK_ALL");
        int particle = mask("PARTICLE_MASK");
        int worldStandIn = mask("MASK_SECONDARY");
        int localView = mask("MASK_PRIMARY") | mask("MASK_LOCAL_VIEW_SECONDARY");

        // One row per ray domain: camera, world secondary, local-view secondary, reflection.
        assertVisibility(CULL_PRIMARY, terrain, true, particle, true, worldStandIn, false, localView, true);
        assertVisibility(CULL_SECONDARY, terrain, true, particle, false, worldStandIn, true, localView, false);
        assertVisibility(CULL_LOCAL_VIEW_SECONDARY,
                terrain, true, particle, false, worldStandIn, false, localView, true);
        assertVisibility(CULL_REFLECTION,
                terrain, true, particle, false, worldStandIn, false, localView, false);
    }

    /**
     * The two representations never both answer one secondary ray. This disjointness is the entire
     * mathematical basis for the player's shadow keeping its head while the visible body has no dark patch.
     */
    @Test
    void theTwoRepresentationsAreDisjointOnSecondaryRays() throws ReflectiveOperationException {
        int worldStandIn = mask("MASK_SECONDARY");
        int localView = mask("MASK_PRIMARY") | mask("MASK_LOCAL_VIEW_SECONDARY");

        assertEquals(0, worldStandIn & CULL_LOCAL_VIEW_SECONDARY, "stand-in must not answer local-view rays");
        assertEquals(0, localView & CULL_SECONDARY, "local view must not answer world secondary rays");
        assertTrue((worldStandIn & CULL_SECONDARY) != 0, "the stand-in owns the world secondary domain");
        assertTrue((localView & CULL_LOCAL_VIEW_SECONDARY) != 0, "local view owns its own secondary domain");
    }

    /**
     * A reflection leaving a local-view surface must contain only scene geometry. Neither player
     * representation nor particles may answer a reflection-domain ray — that purity is the reason the
     * domain exists.
     */
    @Test
    void reflectionDomainSeesNoPlayerRepresentation() throws ReflectiveOperationException {
        int worldStandIn = mask("MASK_SECONDARY");
        int localView = mask("MASK_PRIMARY") | mask("MASK_LOCAL_VIEW_SECONDARY");

        assertEquals(0, worldStandIn & CULL_REFLECTION, "stand-in must not answer reflection rays");
        assertEquals(0, localView & CULL_REFLECTION, "local view must not answer reflection rays");
        assertEquals(0, mask("PARTICLE_MASK") & CULL_REFLECTION, "particles must not answer reflection rays");
        assertTrue((mask("MASK_ALL") & CULL_REFLECTION) != 0, "scene geometry answers reflection rays");
    }

    private static void assertVisibility(int domain, int mask0, boolean expected0, int mask1, boolean expected1,
                                         int mask2, boolean expected2, int mask3, boolean expected3) {
        assertCell(domain, mask0, expected0);
        assertCell(domain, mask1, expected1);
        assertCell(domain, mask2, expected2);
        assertCell(domain, mask3, expected3);
    }

    private static void assertCell(int domain, int instanceMask, boolean expected) {
        assertEquals(expected, (instanceMask & domain) != 0,
                () -> "instance mask 0x" + Integer.toHexString(instanceMask)
                        + " against domain 0x" + Integer.toHexString(domain));
    }
}
