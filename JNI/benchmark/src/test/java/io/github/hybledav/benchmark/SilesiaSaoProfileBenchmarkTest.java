package io.github.hybledav.benchmark;

import com.github.luben.zstd.Zstd;
import io.github.hybledav.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.hybledav.benchmark.SilesiaSaoUtils.*;

/**
 * Benchmark OpenZL with SAO profile vs zstd level 3 on Silesia 'sao'.
 */
public class SilesiaSaoProfileBenchmarkTest {

    @Test
    public void compareOpenZLSaoProfileVsZstdOnSao() throws Exception {
        Path saoPath = ensureSaoOrSkip();
        byte[] data = Files.readAllBytes(saoPath);
        assertTrue(data.length > 0, "SAO file is empty");

        OpenZLCompressor openzl = new OpenZLCompressor();
        openzl.configureProfile(OpenZLProfile.SAO, java.util.Map.of());
        openzl.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);

        final int warmup = 3, measure = 5;
        for (int i = 0; i < warmup; i++) {
            assertTrue(openzl.compress(data).length > 0);
            assertTrue(Zstd.compress(data, 3).length > 0);
        }

        long ozNanos = 0, zzNanos = 0;
        byte[] ozLast = null, zzLast = null;
        for (int i = 0; i < measure; i++) {
            long t0 = System.nanoTime();
            ozLast = openzl.compress(data);
            long t1 = System.nanoTime();
            ozNanos += (t1 - t0);
        }
        for (int i = 0; i < measure; i++) {
            long t0 = System.nanoTime();
            zzLast = Zstd.compress(data, 3);
            long t1 = System.nanoTime();
            zzNanos += (t1 - t0);
        }

        long n = data.length;
        double ozMbps = mbps(n, ozNanos / (double) measure);
        double zzMbps = mbps(n, zzNanos / (double) measure);
        double ozRatio = (double) ozLast.length / n;
        double zzRatio = (double) zzLast.length / n;

        System.out.printf(java.util.Locale.ROOT,
                "[SAO profile] SAO bytes=%d | OpenZL: size=%d (%.3f%%), speed=%.2f MB/s | Zstd-l3: size=%d (%.3f%%), speed=%.2f MB/s%n",
                n, ozLast.length, ozRatio * 100.0, ozMbps, zzLast.length, zzRatio * 100.0, zzMbps);

        // Sanity check decompression
        assertArrayEquals(data, openzl.decompress(ozLast));
        byte[] restored = new byte[data.length];
        long dec = Zstd.decompress(restored, zzLast);
        assertEquals(data.length, dec);
        assertArrayEquals(data, restored);
    }
}

