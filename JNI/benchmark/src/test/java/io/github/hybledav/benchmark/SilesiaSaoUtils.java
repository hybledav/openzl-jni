package io.github.hybledav.benchmark;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class SilesiaSaoUtils {
    private static final String SAO_BASENAME = "sao";
    private static final URI SILESIA_URL = URI.create("http://sun.aei.polsl.pl/~sdeor/corpus/silesia.zip");

    private SilesiaSaoUtils() {}

    static Path ensureSaoOrSkip() throws Exception {
        try {
            return ensureSao();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "SAO data unavailable: " + e.getMessage());
            throw e; // unreachable
        }
    }

    static Path ensureSao() throws IOException, InterruptedException {
        Optional<Path> sao = envPath("SAO_PATH");
        if (sao.isPresent() && Files.isRegularFile(sao.get())) {
            return sao.get();
        }
        Optional<Path> zipPathOpt = envPath("SILESIA_ZIP");
        if (zipPathOpt.isPresent() && Files.isRegularFile(zipPathOpt.get())) {
            return extractSaoFromZip(zipPathOpt.get());
        }
        Path dir = workDir();
        Files.createDirectories(dir);
        Path zip = dir.resolve("silesia.zip");
        if (!Files.isRegularFile(zip)) {
            downloadTo(SILESIA_URL, zip, Duration.ofMinutes(5));
        }
        return extractSaoFromZip(zip);
    }

    static double mbps(long bytes, double nanos) {
        if (nanos <= 0) return 0.0;
        double seconds = nanos / 1_000_000_000.0;
        return (bytes / (1024.0 * 1024.0)) / seconds;
    }

    private static Path workDir() {
        return Paths.get(System.getProperty("user.dir")).resolve("target").resolve("silesia");
    }

    private static Optional<Path> envPath(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isEmpty()) v = System.getenv(key);
        if (v == null || v.isEmpty()) return Optional.empty();
        return Optional.of(Paths.get(v));
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
}

