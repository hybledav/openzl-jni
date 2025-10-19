package io.github.hybledav.benchmark;

import com.github.luben.zstd.Zstd;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;

public final class SilesiaSaoZstdMain {
    public static void main(String[] args) throws Exception {
        Path saoPath = SilesiaSaoHelper.ensureSao();
        MappedByteBuffer src = SilesiaSaoHelper.mapReadOnly(saoPath);
        long n = src.remaining();
        if (n == 0) throw new IllegalStateException("SAO file is empty: " + saoPath);

        final int level = 3; // match combined benchmark
        final int warmup = 100;

        for (int i = 0; i < warmup; i++) {
            MappedByteBuffer v = src.duplicate();
            v.clear();
            int bound = (int) Zstd.compressBound(v.remaining());
            ByteBuffer dst = ByteBuffer.allocateDirect(bound);
            dst.clear();
            long written = Zstd.compress(dst, v, level);
            if (written <= 0) throw new IllegalStateException("zstd warmup failed: " + written);
        }

        long acc = 0L;
        ByteBuffer last = null;
        final int measure = 10000;
        int lastSize = 0;
        for (int i = 0; i < measure; i++) {
            MappedByteBuffer v = src.duplicate();
            v.clear();
            int bound = (int) Zstd.compressBound(v.remaining());
            ByteBuffer dst = ByteBuffer.allocateDirect(bound);
            dst.clear();
            long t0 = System.nanoTime();
            long written = Zstd.compress(dst, v, level);
            long t1 = System.nanoTime();
            if (written <= 0) throw new IllegalStateException("zstd compress failed: " + written);
            acc += (t1 - t0);
            dst.limit((int) written);
            dst.position(0);
            last = dst;
            lastSize = (int) written;
        }

        double mbps = SilesiaSaoHelper.mbps(n, acc / (double) measure);
        double ratio = (double) lastSize / n;
        System.out.printf(java.util.Locale.ROOT,
                "[Zstd l=%d] SAO bytes=%d | size=%d (%.3f%%), speed=%.2f MB/s%n",
                level, n, lastSize, ratio * 100.0, mbps);

        // Roundtrip validation using direct buffers
        ByteBuffer restored = ByteBuffer.allocateDirect((int) n);
        long dec = Zstd.decompress(restored, last.duplicate());
        if (dec != n) {
            throw new IllegalStateException("zstd decompress size mismatch: " + dec + " != " + n);
        }
        restored.flip();
        // Compare content to original
        MappedByteBuffer orig = src.duplicate();
        orig.clear();
        for (int i = 0; i < n; i++) {
            if (orig.get(i) != restored.get(i)) {
                throw new IllegalStateException("zstd roundtrip mismatch at byte " + i);
            }
        }
    }
}
