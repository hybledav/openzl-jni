package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TestCompressionLevels {

    private static final int CPARAM_COMPRESSION_LEVEL = 2;

    @Test
    void setCompressionLevelReflectsInNativeParameter() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_12);

            assertEquals(OpenZLCompressionLevel.LEVEL_12, compressor.getCompressionLevel());
            assertEquals(OpenZLCompressionLevel.LEVEL_12.level(),
                    compressor.getParameter(CPARAM_COMPRESSION_LEVEL),
                    "Native parameter should match configured compression level");
        }
    }

    @Test
    void defaultCompressionLevelMatchesNativeDefault() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            int nativeValue = compressor.getParameter(CPARAM_COMPRESSION_LEVEL);
            assertEquals(OpenZLCompressionLevel.fromLevel(nativeValue),
                    compressor.getCompressionLevel(),
                    "Reported compression level should reflect native parameter");
        }
    }

    @Test
    void resetPreservesChosenCompressionLevel() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_18);
            assertEquals(OpenZLCompressionLevel.LEVEL_18.level(),
                    compressor.getParameter(CPARAM_COMPRESSION_LEVEL));

            compressor.reset();
            assertEquals(OpenZLCompressionLevel.LEVEL_18.level(),
                    compressor.getParameter(CPARAM_COMPRESSION_LEVEL),
                    "Reset should preserve previously configured compression level");
            assertEquals(OpenZLCompressionLevel.LEVEL_18, compressor.getCompressionLevel());
        }
    }

    @Test
    void configureProfileKeepsCustomCompressionLevel() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_14);
            compressor.configureProfile(OpenZLProfile.CSV, Map.of());

            assertEquals(OpenZLCompressionLevel.LEVEL_14,
                    compressor.getCompressionLevel(),
                    "Configuring profile should not reset compression level");
        }
    }

    @Test
    void setCompressionLevelRejectsNull() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(NullPointerException.class, () -> compressor.setCompressionLevel(null));
        }
    }

    @Test
    void fromLevelRejectsUnsupportedValues() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> OpenZLCompressionLevel.fromLevel(0));
        assertTrue(thrown.getMessage().contains("Unknown compression level"));
    }

    @Test
    void getCompressionLevelThrowsIfNativeReturnsUnsupportedValue() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setParameter(CPARAM_COMPRESSION_LEVEL, 99);
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, compressor::getCompressionLevel);
            assertTrue(thrown.getMessage().contains("Unknown compression level"));
        }
    }

    @Test
    void higherCompressionLevelImprovesStructuredLogCompression() {
        byte[] payload = generateLongDistanceDuplicatePayload(2 * 1024 * 1024, 3);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());

            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);
            String lowConfig = compressor.serializeToJson();
            assertTrue(lowConfig.contains("2: 1"),
                    () -> String.format(Locale.ROOT,
                            "Serialized configuration should capture compression level 1: %s",
                            lowConfig));
            byte[] fastCompressed = compressor.compress(payload);
            assertNotNull(fastCompressed);
            byte[] fastRestored = compressor.decompress(fastCompressed);
            assertArrayEquals(payload, fastRestored, "Compression should remain lossless at lower level");

            compressor.reset();
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_22);
            String highConfig = compressor.serializeToJson();
            assertTrue(highConfig.contains("2: 22"),
                    () -> String.format(Locale.ROOT,
                            "Serialized configuration should capture compression level 22: %s",
                            highConfig));
            byte[] highCompressed = compressor.compress(payload);
            assertNotNull(highCompressed);
            byte[] highRestored = compressor.decompress(highCompressed);
            assertArrayEquals(payload, highRestored, "Compression should remain lossless at higher level");

            assertNotEquals(lowConfig, highConfig, "Compression level should alter serialized configuration");

            assertTrue(highCompressed.length <= fastCompressed.length,
                    () -> String.format(Locale.ROOT,
                            "Expected higher compression level to be at least as compact (low=%d, high=%d)",
                            fastCompressed.length,
                            highCompressed.length));
        }
    }

    @Test
    void sddlProfileSupportsCompressionLevelAdjustments() {
        byte[] compiled = OpenZLSddl.compile(": Byte[_rem];\n", false, 0);
        byte[] payload = generateLongDistanceDuplicatePayload(512 * 1024, 2);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureSddl(compiled);

            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_16);
            String highConfig = compressor.serializeToJson();
            assertTrue(highConfig.contains("2: 16"),
                    () -> String.format(Locale.ROOT,
                            "Serialized configuration should capture compression level 16 for SDDL: %s",
                            highConfig));

            byte[] highCompressed = compressor.compress(payload);
            assertNotNull(highCompressed);
            byte[] highRestored = compressor.decompress(highCompressed);
            assertArrayEquals(payload, highRestored,
                    "SDDL compression should remain lossless at higher level");

            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_4);
            String lowConfig = compressor.serializeToJson();
            assertTrue(lowConfig.contains("2: 4"),
                    () -> String.format(Locale.ROOT,
                            "Serialized configuration should capture compression level 4 for SDDL: %s",
                            lowConfig));

            byte[] lowCompressed = compressor.compress(payload);
            assertNotNull(lowCompressed);
            byte[] lowRestored = compressor.decompress(lowCompressed);
            assertArrayEquals(payload, lowRestored,
                    "SDDL compression should remain lossless at lower level");

            assertNotEquals(highConfig, lowConfig,
                    "Compression level should alter SDDL serialized configuration");

            assertTrue(lowCompressed.length >= highCompressed.length,
                    () -> String.format(Locale.ROOT,
                            "Expected higher compression level to be at least as compact under SDDL (low=%d, high=%d)",
                            lowCompressed.length,
                            highCompressed.length));
        }
    }

    @Test
    void maxCompressionPayloadDecompressesAtLevelOne() {
        byte[] payload = generateLongDistanceDuplicatePayload(256 * 1024, 2);
        byte[] compressed;

        try (OpenZLCompressor high = new OpenZLCompressor()) {
            high.setCompressionLevel(OpenZLCompressionLevel.LEVEL_22);
            compressed = high.compress(payload);
            assertNotNull(compressed);
        }

        try (OpenZLCompressor low = new OpenZLCompressor()) {
            low.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);
            byte[] restored = low.decompress(compressed);
            assertArrayEquals(payload, restored,
                    "Payload compressed at max level should decompress with level 1 configuration");
        }
    }

    private static byte[] generateLongDistanceDuplicatePayload(int chunkSize, int copies) {
        if (chunkSize <= 0 || copies < 2) {
            throw new IllegalArgumentException("Need at least two chunks to generate duplicates");
        }
        byte[] payload = new byte[chunkSize * copies];
        byte[] base = new byte[chunkSize];
        Random rng = new Random(0x5EEDC0FFEEBEEFL);
        rng.nextBytes(base);
        for (int copy = 0; copy < copies; copy++) {
            System.arraycopy(base, 0, payload, copy * chunkSize, chunkSize);
            int offset = copy * chunkSize;
            int stride = Math.max(1, chunkSize / 1024);
            for (int pos = copy; pos < chunkSize; pos += stride * 137) {
                payload[offset + (pos % chunkSize)] ^= (byte) (copy * 17);
            }
        }
        return payload;
    }
}