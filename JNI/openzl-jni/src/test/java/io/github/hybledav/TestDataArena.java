package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TestDataArena {

    @Test
    void heapArenaRoundTrip() {
        byte[] payload = new byte[] { 10, 20, 30, 40, 50 };
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.setDataArena(OpenZLCompressor.OpenZLDataArena.HEAP);
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] compressed = c.compress(payload);
            assertNotNull(compressed);
            byte[] restored = c.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }

    @Test
    void stackArenaRoundTrip() {
        byte[] payload = new byte[] { 1, 2, 3, 4, 5, 6, 7 };
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.setDataArena(OpenZLCompressor.OpenZLDataArena.STACK);
            c.configureProfile(OpenZLProfile.SERIAL, java.util.Map.of());
            byte[] compressed = c.compress(payload);
            assertNotNull(compressed);
            byte[] restored = c.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }
}
