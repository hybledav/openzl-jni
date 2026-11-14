package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestCodecOutputCache {
    @Test
    void cachedTrialsPreserveFramesAcrossResetProfileAndBudgetChanges() {
        byte[] input = "cache deterministic codec trials, without changing frames\n".repeat(4096)
                .getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            assertEquals(0, c.getTryGraphCacheBudget());
            c.configureBruteForce(OpenZLGraph.ZSTD, OpenZLGraph.HUFFMAN,
                    OpenZLGraph.FSE, OpenZLGraph.ENTROPY, OpenZLGraph.STORE);
            byte[] expected = c.compress(input);
            for (long budget : new long[] {1, 64 << 10, 4 << 20, 0}) {
                c.setTryGraphCacheBudget(budget);
                for (int i = 0; i < 3; i++) {
                    assertArrayEquals(expected, c.compress(input));
                    assertArrayEquals(input, c.decompress(expected));
                }
                c.reset();
                assertEquals(budget, c.getTryGraphCacheBudget());
                assertArrayEquals(expected, c.compress(input));
            }
            c.setTryGraphCacheBudget(1024);
            c.configureProfile(OpenZLProfile.SERIAL);
            assertEquals(1024, c.getTryGraphCacheBudget());
            assertThrows(IllegalArgumentException.class, () -> c.setTryGraphCacheBudget(-1));
            assertEquals(1024, c.getTryGraphCacheBudget());
        }
    }

    @Test
    void closeReleasesCacheBeforeNativeContextIsReused() {
        OpenZLCompressor previous = new OpenZLCompressor();
        previous.setTryGraphCacheBudget(8 << 20);
        previous.configureBruteForce(OpenZLGraph.ZSTD, OpenZLGraph.STORE);
        previous.compress(new byte[65536]);
        previous.close();
        assertThrows(IllegalStateException.class, previous::getTryGraphCacheBudget);
        assertThrows(IllegalStateException.class, () -> previous.setTryGraphCacheBudget(1));
        try (OpenZLCompressor next = new OpenZLCompressor()) {
            assertEquals(0, next.getTryGraphCacheBudget());
            assertArrayEquals(new byte[65536], next.decompress(next.compress(new byte[65536])));
        }
    }
}
