package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestCompressorStoreGraph {

    @Test
    void storeGraphRoundTrip() {
        byte[] payload = "store-graph-roundtrip".getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.STORE)) {
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed);
            byte[] restored = compressor.decompress(compressed);
            assertArrayEquals(payload, restored);
        }
    }
}
