package io.github.hybledav.benchmark;

import com.github.luben.zstd.Zstd;
import io.github.hybledav.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Standalone benchmark comparing OpenZL (profile le-u64) vs zstd-jni level 3
 * on the SAO file from the Silesia corpus.
 *
 * This test will try to locate the SAO file via one of:
 * - System property or env SAO_PATH (path to the extracted "sao" file)
 * - System property or env SILESIA_ZIP (path to silesia.zip) then extract "sao"
 * - Best-effort download of silesia.zip to target/silesia/silesia.zip and extract "sao"
 *
 * If none succeed, the test is skipped with a helpful message.
 */
public class SilesiaSaoBenchmarkTest {
    private static final String SAO_BASENAME = "sao";
    private static final URI SILESIA_URL = URI.create("http://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip");

    private static Path workDir() {
        return Paths.get(System.getProperty("user.dir")).resolve("target").resolve("silesia");
    }

    private static Optional<Path> envPath(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) v = System.getenv(key);
        if (v == null || v.isEmpty()) return Optional.empty();
        return Optional.of(Paths.get(v));
    }

    private static Path ensureSao() throws IOException, InterruptedException {
        // 1) Direct SAO path
        Optional<Path> sao = envPath("SAO_PATH");
        if (sao.isPresent() && Files.isRegularFile(sao.get())) {
            return sao.get();
        }
        // 2) Zip path and extract
        Optional<Path> zipPathOpt = envPath("SILESIA_ZIP");
        if (zipPathOpt.isPresent() && Files.isRegularFile(zipPathOpt.get())) {
            return extractSaoFromZip(zipPathOpt.get());
        }
        // 3) Attempt to download zip
        Path dir = workDir();
        Files.createDirectories(dir);
        Path zip = dir.resolve("silesia.zip");
        if (!Files.isRegularFile(zip)) {
            try {
                downloadTo(SILESIA_URL, zip, Duration.ofMinutes(5));
            } catch (Exception e) {
                // Download failed; propagate in a way that lets the test skip
                throw new IOException("Unable to download Silesia corpus from " + SILESIA_URL + ": " + e, e);
            }
        }
        return extractSaoFromZip(zip);
    }

    private static Path extractSaoFromZip(Path zip) throws IOException {
        Path out = workDir().resolve(SAO_BASENAME);
        if (Files.isRegularFile(out) && Files.size(out) > 0) {
            return out;
        }
        try (InputStream fis = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && (entry.getName().endsWith("/" + SAO_BASENAME) || entry.getName().equals(SAO_BASENAME))) {
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    break;
                }
            }
        }
        if (!Files.isRegularFile(out) || Files.size(out) == 0) {
            throw new IOException("Failed to extract '" + SAO_BASENAME + "' from " + zip);
        }
        return out;
    }

    private static void downloadTo(URI uri, Path target, Duration timeout) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " downloading " + uri);
        }
    }

    private static byte[] readAll(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    @Test
    public void compareOpenZLvsZstdOnSao() throws Exception {
        Path saoPath;
        try {
            saoPath = ensureSao();
        } catch (Exception e) {
            assumeTrue(false, "SAO data unavailable: " + e.getMessage());
            return; // unreachable but keeps compiler happy
        }
        byte[] data = readAll(saoPath);
        assertTrue(data.length > 0, "SAO file is empty");

        // Prepare OpenZL with profile le-u64 and level 3, using NUMERIC graph
        OpenZLCompressor openzl = new OpenZLCompressor(OpenZLGraph.NUMERIC);
        openzl.configureProfile(OpenZLProfile.LITTLE_ENDIAN_UNSIGNED_64, java.util.Map.of());

        // Warmup counts
        final int warmupIters = 3;
        final int measureIters = 5;

        // Warmup both
        for (int i = 0; i < warmupIters; i++) {
            byte[] cz = openzl.compress(data);
            assertTrue(cz.length > 0);
            byte[] zz = Zstd.compress(data, 3);
            assertTrue(zz.length > 0);
        }

        // Measure OpenZL
        long openzlTimeNanos = 0;
        byte[] openzlLast = null;
        for (int i = 0; i < measureIters; i++) {
            long t0 = System.nanoTime();
            byte[] cz = openzl.compress(data);
            long t1 = System.nanoTime();
            openzlTimeNanos += (t1 - t0);
            openzlLast = cz;
        }
        assertNotNull(openzlLast);

        // Measure Zstd JNI LVL3
        long zstdTimeNanos = 0;
        byte[] zstdLast = null;
        for (int i = 0; i < measureIters; i++) {
            long t0 = System.nanoTime();
            byte[] cz = Zstd.compress(data, 3);
            long t1 = System.nanoTime();
            zstdTimeNanos += (t1 - t0);
            zstdLast = cz;
        }
        assertNotNull(zstdLast);

        long dataSize = data.length;
        long openzlSize = openzlLast.length;
        long zstdSize = zstdLast.length;

        double openzlMbps = mbps(dataSize, openzlTimeNanos / (double) measureIters);
        double zstdMbps = mbps(dataSize, zstdTimeNanos / (double) measureIters);
        double openzlRatio = (double) openzlSize / (double) dataSize;
        double zstdRatio = (double) zstdSize / (double) dataSize;

        System.out.printf(Locale.ROOT,
                "SAO bytes=%d | OpenZL: size=%d (%.3f%%), speed=%.2f MB/s | Zstd-l3: size=%d (%.3f%%), speed=%.2f MB/s%n",
                dataSize,
                openzlSize, openzlRatio * 100.0, openzlMbps,
                zstdSize, zstdRatio * 100.0, zstdMbps);

        boolean strict = Boolean.parseBoolean(System.getProperty("BENCH_STRICT_ASSERT",
                System.getenv().getOrDefault("BENCH_STRICT_ASSERT", "false")));

        if (strict) {
            assertTrue(openzlSize <= zstdSize,
                    "Expected OpenZL to have equal or better compression ratio");
            assertTrue(openzlMbps >= zstdMbps,
                    "Expected OpenZL to be equal or faster in compression throughput");
        }

        // Validate decompression restores original data
        byte[] openzlDecomp = openzl.decompress(openzlLast);
        assertArrayEquals(data, openzlDecomp, "OpenZL decompressed payload must match input");

        byte[] zstdRestored = new byte[data.length];
        long zstdDecompressed = Zstd.decompress(zstdRestored, zstdLast);
        assertEquals(data.length, zstdDecompressed, "Zstd decompressed size should equal input size");
        assertArrayEquals(data, zstdRestored, "Zstd decompressed payload must match input");
    }

    private static double mbps(long bytes, double nanos) {
        if (nanos <= 0) return 0.0;
        double seconds = nanos / 1_000_000_000.0;
        return (bytes / (1024.0 * 1024.0)) / seconds;
    }
}
