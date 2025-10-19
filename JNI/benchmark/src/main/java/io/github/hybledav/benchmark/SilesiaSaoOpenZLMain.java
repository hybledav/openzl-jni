package io.github.hybledav.benchmark;

import io.github.hybledav.OpenZLBufferManager;
import io.github.hybledav.OpenZLCompressionLevel;
import io.github.hybledav.OpenZLCompressor;
import io.github.hybledav.OpenZLProfile;

import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class SilesiaSaoOpenZLMain {
    public static void main(String[] args) throws Exception {
        Path saoPath = SilesiaSaoHelper.ensureSao();
        MappedByteBuffer src = SilesiaSaoHelper.mapReadOnly(saoPath);
        long n = src.remaining();
        if (n == 0) throw new IllegalStateException("SAO file is empty: " + saoPath);

        OpenZLCompressor openzl = new OpenZLCompressor();
        openzl.configureProfile(OpenZLProfile.LITTLE_ENDIAN_UNSIGNED_64, Map.of());
        openzl.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);

        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build()) {
            final int warmup = 3, measure = 5;
            for (int i = 0; i < warmup; i++) {
                MappedByteBuffer v = src.duplicate(); v.clear();
                if (openzl.compress(v, buffers).remaining() <= 0) throw new IllegalStateException("OpenZL warmup failed");
            }

            long acc = 0L;
            java.nio.ByteBuffer last = null;
            for (int i = 0; i < measure; i++) {
                MappedByteBuffer v = src.duplicate(); v.clear();
                long t0 = System.nanoTime();
                last = openzl.compress(v, buffers);
                long t1 = System.nanoTime();
                acc += (t1 - t0);
            }

            double mbps = SilesiaSaoHelper.mbps(n, acc / (double) measure);
            double ratio = (double) last.remaining() / n;
            System.out.printf(java.util.Locale.ROOT,
                    "[OpenZL le-u64] SAO bytes=%d | size=%d (%.3f%%), speed=%.2f MB/s%n",
                    n, last.remaining(), ratio * 100.0, mbps);

            // Roundtrip validation using direct buffers
            java.nio.ByteBuffer restored = openzl.decompress(last.duplicate(), buffers);
            // Compare content to original
            byte[] orig = Files.readAllBytes(saoPath);
            byte[] back = new byte[restored.remaining()];
            restored.get(back);
            if (orig.length != back.length || !java.util.Arrays.equals(orig, back)) {
                throw new IllegalStateException("OpenZL roundtrip mismatch");
            }
        }
    }
}
