package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestCompressorSerialRoundTrip {

    @Test
    void serialProfileRoundTrip() {
        byte[] payload = "serial-graph-roundtrip".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SERIAL, Map.of());
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);
            byte[] restored = compressor.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }
}
