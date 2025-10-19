package io.github.hybledav.benchmark;

import com.github.luben.zstd.Zstd;
import io.github.hybledav.OpenZLBufferManager;
import io.github.hybledav.OpenZLCompressionLevel;
import io.github.hybledav.OpenZLCompressor;
import io.github.hybledav.OpenZLProfile;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;
import java.util.Map;

public final class SilesiaSaoProfileBenchmarkMain {

    public static void main(String[] args) throws Exception {
        Path saoPath = SilesiaSaoHelper.ensureSao();
        MappedByteBuffer src = SilesiaSaoHelper.mapReadOnly(saoPath);
        long n = src.remaining();
        if (n == 0) {
            throw new IllegalStateException("SAO file is empty: " + saoPath);
        }

        OpenZLCompressor openzl = new OpenZLCompressor();
        openzl.configureProfile(OpenZLProfile.SAO, Map.of());
        openzl.setCompressionLevel(OpenZLCompressionLevel.LEVEL_1);

        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().build()) {
            final int warmup = 1, measure = 100;
            for (int i = 0; i < warmup; i++) {
                MappedByteBuffer vo = src.duplicate(); vo.clear();
                if (openzl.compress(vo, buffers).remaining() <= 0) throw new IllegalStateException("OpenZL warmup failed");
                MappedByteBuffer vz = src.duplicate(); vz.clear();
                int bound = (int) Zstd.compressBound(vz.remaining());
                ByteBuffer dst = ByteBuffer.allocateDirect(bound);
                if (Zstd.compress(dst, vz, 5) <= 0) throw new IllegalStateException("zstd warmup failed");
            }

            long ozNanos = 0, zzNanos = 0;
            ByteBuffer ozLast = null, zzLast = null;
            for (int i = 0; i < measure; i++) {
                MappedByteBuffer v = src.duplicate(); v.clear();
                long t0 = System.nanoTime();
                ozLast = openzl.compress(v, buffers);
                long t1 = System.nanoTime();
                ozNanos += (t1 - t0);
            }
            for (int i = 0; i < measure; i++) {
                MappedByteBuffer v = src.duplicate(); v.clear();
                int bound = (int) Zstd.compressBound(v.remaining());
                ByteBuffer dst = ByteBuffer.allocateDirect(bound);
                long t0 = System.nanoTime();
                long written = Zstd.compress(dst, v, 5);
                long t1 = System.nanoTime();
                if (written <= 0) throw new IllegalStateException("zstd compress failed: " + written);
                dst.limit((int) written);
                dst.position(0);
                zzNanos += (t1 - t0);
                zzLast = dst;
            }

            double ozMbps = SilesiaSaoHelper.mbps(n, ozNanos / (double) measure);
            double zzMbps = SilesiaSaoHelper.mbps(n, zzNanos / (double) measure);
            double ozRatio = (double) ozLast.remaining() / n;
            double zzRatio = (double) zzLast.remaining() / n;

            System.out.printf(java.util.Locale.ROOT,
                    "[SAO profile le-u64] SAO bytes=%d | OpenZL: size=%d (%.3f%%), speed=%.2f MB/s | Zstd-l3: size=%d (%.3f%%), speed=%.2f MB/s%n",
                    n, ozLast.remaining(), ozRatio * 100.0, ozMbps, zzLast.remaining(), zzRatio * 100.0, zzMbps);

            // Sanity check decompression
            ByteBuffer ozRestored = openzl.decompress(ozLast.duplicate(), buffers);
            ByteBuffer zstdRestored = ByteBuffer.allocateDirect((int) n);
            long dec = Zstd.decompress(zstdRestored, zzLast.duplicate());
            if (dec != n) {
                throw new IllegalStateException("zstd decompress size mismatch: " + dec + " != " + n);
            }
            zstdRestored.flip();
            // Byte-wise compare
            MappedByteBuffer orig = src.duplicate(); orig.clear();
            for (int i = 0; i < n; i++) {
                byte a = orig.get(i);
                byte b = ozRestored.get(i);
                if (a != b) throw new IllegalStateException("OpenZL roundtrip mismatch at byte " + i);
            }
            orig.clear();
            for (int i = 0; i < n; i++) {
                byte a = orig.get(i);
                byte b = zstdRestored.get(i);
                if (a != b) throw new IllegalStateException("zstd roundtrip mismatch at byte " + i);
            }
        }
    }
}
