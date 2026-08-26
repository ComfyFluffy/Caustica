package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtToneMappingTest {
    @Test
    void defaultConfigUsesAces20ForSdrAndHdr() {
        assertEquals(RtToneMapping.SdrMode.ACES_2_0, RtToneMapping.SdrMode.parse(null));
        assertEquals(RtToneMapping.SdrMode.ACES_2_0, RtToneMapping.SdrMode.parse("unknown"));
        assertEquals(RtToneMapping.HdrMode.ACES_2_0, RtToneMapping.HdrMode.parse(null));
        assertEquals(RtToneMapping.HdrMode.ACES_2_0, RtToneMapping.HdrMode.parse("unknown"));
        assertEquals("aces2.0", CausticaConfig.Rt.Sdr.TONE_MAPPER.defaultValue());
        assertEquals("aces2.0", CausticaConfig.Rt.Hdr.TONE_MAPPER.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Sdr.PSYCHOV24_COMPRESSION.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Sdr.PSYCHOV24_GAMUT_COMPRESSION.defaultValue());
        assertEquals(0.0f, CausticaConfig.Rt.Hdr.PSYCHOV24_COMPRESSION.defaultValue());
        assertEquals(0.50f, CausticaConfig.Rt.Exposure.LOW_PERCENTILE.defaultValue());
        assertEquals(0.95f, CausticaConfig.Rt.Exposure.HIGH_PERCENTILE.defaultValue());
    }

    @Test
    void psychov24AndCompatibilityAliasesSelectTheSameMode() {
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychov24"));
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychovisual"));
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psycho-visual"));
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychov"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psychovisual"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psycho-visual"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psychov"));
        assertEquals(RtToneMapping.HdrMode.BT2390,
                RtToneMapping.HdrMode.parse(" bt.2390 "));
    }

    @Test
    void legacyPsychoNamesRemainCompatibilityAliases() {
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychov11"));
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychov23"));
        assertEquals(RtToneMapping.SdrMode.PSYCHOV24,
                RtToneMapping.SdrMode.parse("psychov24-experimental"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psychov11"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psychov23"));
        assertEquals(RtToneMapping.HdrMode.PSYCHOV24,
                RtToneMapping.HdrMode.parse("psychov24-experimental"));
    }

    @Test
    void modeIdsAreUniqueAndStable() {
        assertEquals(0, RtToneMapping.SdrMode.ACES_2_0.id());
        assertEquals(8, RtToneMapping.SdrMode.PSYCHOV24.id());
        assertEquals(0, RtToneMapping.HdrMode.ACES_2_0.id());
        assertEquals(3, RtToneMapping.HdrMode.BT2390.id());
        assertEquals(2, RtToneMapping.HdrMode.PSYCHOV24.id());
        assertFalse(RtToneMapping.hdrConfigNames().contains("caustica"));
    }

    @Test
    void psychov24LeavesUnusedPushConstantsZero() {
        String previousSdr = CausticaConfig.Rt.Sdr.TONE_MAPPER.get();
        String previousHdr = CausticaConfig.Rt.Hdr.TONE_MAPPER.get();
        try {
            CausticaConfig.Rt.Sdr.TONE_MAPPER.set("psychov24");
            CausticaConfig.Rt.Hdr.TONE_MAPPER.set("psychov24");
            RtToneMapping.Settings settings = RtToneMapping.current();
            assertEquals(0.0f, settings.sdrParameters().param6());
            assertEquals(0.0f, settings.sdrParameters().param7());
            assertEquals(0.0f, settings.hdrParameters().param6());
            assertEquals(0.0f, settings.hdrParameters().param7());
        } finally {
            CausticaConfig.Rt.Sdr.TONE_MAPPER.set(previousSdr);
            CausticaConfig.Rt.Hdr.TONE_MAPPER.set(previousHdr);
            RtToneMapping.current();
        }
    }

    @Test
    void unchangedSnapshotIsReusedAndModeChangesInvalidateIt() {
        String previous = CausticaConfig.Rt.Sdr.TONE_MAPPER.get();
        try {
            CausticaConfig.Rt.Sdr.TONE_MAPPER.set("aces2.0");
            RtToneMapping.Settings first = RtToneMapping.current();
            assertSame(first, RtToneMapping.current());

            CausticaConfig.Rt.Sdr.TONE_MAPPER.set("agx");
            RtToneMapping.Settings changed = RtToneMapping.current();
            assertNotSame(first, changed);
            assertSame(changed, RtToneMapping.current());
        } finally {
            CausticaConfig.Rt.Sdr.TONE_MAPPER.set(previous);
            RtToneMapping.current();
        }
    }
}
