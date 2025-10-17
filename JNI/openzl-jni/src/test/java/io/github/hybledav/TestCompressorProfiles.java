package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestCompressorProfiles {

    @Test
    void serialProfileRoundTrip() {
        byte[] payload = "profile-serial-roundtrip".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);
            byte[] restored = compressor.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }

    @Test
    void unknownProfileThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OpenZLProfile.fromName("does-not-exist"));
        assertTrue(ex.getMessage().contains("Unknown compression profile"));
    }

    @Test
    void sddlProfileRequiresDescription() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> compressor.configureProfile(OpenZLProfile.SDDL, Map.of()));
            assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("description"));
        }
    }

    @Test
    void sddlProfileRoundTripWithProgramFile() throws IOException {
        Path program = Files.createTempFile("openzl-profile", ".sddl");
        Files.writeString(program, ": Byte[_rem];\n");
        byte[] payload = new byte[] { 1, 2, 3, 4 };
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SDDL, Map.of("TBD", program.toString()));
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);
            byte[] restored = compressor.decompress(compressed);
            assertArrayEquals(payload, restored);
        } finally {
            Files.deleteIfExists(program);
        }
    }

    @Test
    void csvProfileBeatsSerialForStructuredData() {
        byte[] payload = generateCsvPayload(1_000).getBytes(StandardCharsets.UTF_8);

        int serialSize;
        try (OpenZLCompressor serial = new OpenZLCompressor()) {
            serial.configureProfile(OpenZLProfile.SERIAL, Map.of());
            byte[] serialCompressed = serial.compress(payload);
            assertNotNull(serialCompressed);
            serialSize = serialCompressed.length;
            byte[] restored = serial.decompress(serialCompressed);
            assertArrayEquals(payload, restored, "Serial profile should remain lossless");
        }

        try (OpenZLCompressor csv = new OpenZLCompressor()) {
            csv.configureProfile(OpenZLProfile.CSV, Map.of());
            byte[] csvCompressed = csv.compress(payload);
            assertNotNull(csvCompressed);
            byte[] restored = csv.decompress(csvCompressed);
            assertArrayEquals(payload, restored, "CSV profile should decompress to the original payload");

            double improvement = 1.0 - (double) csvCompressed.length / (double) serialSize;
            assertTrue(csvCompressed.length < serialSize,
                    () -> String.format(
                            java.util.Locale.ROOT,
                            "Expected CSV profile to compress better than serial (csv=%d, serial=%d, improvement=%.2f%%)",
                            csvCompressed.length,
                            serialSize,
                            improvement * 100.0));
            assertTrue(improvement > 0.15,
                    () -> String.format(
                            java.util.Locale.ROOT,
                            "Expected at least 15%% improvement but observed %.2f%% (csv=%d, serial=%d)",
                            improvement * 100.0,
                            csvCompressed.length,
                            serialSize));
        }
    }

    @Test
    void leUnsigned16ProfileBeatsSerialForMonotonicSignal() {
        byte[] payload = generateLeU16Payload(2_048);

        int serialSize;
        try (OpenZLCompressor serial = new OpenZLCompressor()) {
            serial.configureProfile(OpenZLProfile.SERIAL, Map.of());
            byte[] serialCompressed = serial.compress(payload);
            assertNotNull(serialCompressed);
            serialSize = serialCompressed.length;
            byte[] restored = serial.decompress(serialCompressed);
            assertArrayEquals(payload, restored, "Serial profile should remain lossless");
        }

        try (OpenZLCompressor profile = new OpenZLCompressor()) {
            profile.configureProfile(OpenZLProfile.LITTLE_ENDIAN_UNSIGNED_16, Map.of());
            byte[] profCompressed = profile.compress(payload);
            assertNotNull(profCompressed);
            byte[] restored = profile.decompress(profCompressed);
            assertArrayEquals(payload, restored, "le-u16 profile should decompress to the original payload");

            double improvement = 1.0 - (double) profCompressed.length / (double) serialSize;
            assertTrue(profCompressed.length < serialSize,
                    () -> String.format(
                            java.util.Locale.ROOT,
                            "Expected le-u16 profile to compress better than serial (le-u16=%d, serial=%d, improvement=%.2f%%)",
                            profCompressed.length,
                            serialSize,
                            improvement * 100.0));
            assertTrue(improvement > 0.10,
                    () -> String.format(
                            java.util.Locale.ROOT,
                            "Expected at least 10%% improvement but observed %.2f%% (le-u16=%d, serial=%d)",
                            improvement * 100.0,
                            profCompressed.length,
                            serialSize));
        }
    }

    private static String generateCsvPayload(int rows) {
        StringBuilder builder = new StringBuilder();
        builder.append("sensor_id,timestamp,value,status\n");
        for (int i = 0; i < rows; i++) {
            builder.append(i % 32)
                    .append(',')
                    .append(1_700_000_000L + (i * 60L))
                    .append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.3f", (i % 100) * 0.125))
                    .append(',')
                    .append((i % 5 == 0) ? "ACTIVE" : "IDLE")
                    .append('\n');
        }
        return builder.toString();
    }

    private static byte[] generateLeU16Payload(int count) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int base = 10_000;
        for (int i = 0; i < count; i++) {
            int value = base + ((i * 7) % 512) - ((i / 16) % 32);
            buffer.putShort((short) (value & 0xFFFF));
        }
        return buffer.array();
    }
}
