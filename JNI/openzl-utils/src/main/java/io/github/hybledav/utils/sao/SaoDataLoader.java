package io.github.hybledav.utils.sao;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SaoDataLoader {
    private SaoDataLoader() {}

    public static byte[] loadRaw(String explicitPath) throws IOException {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return readFile(Path.of(explicitPath));
        }

        String env = System.getenv("SILESIA_SAO_PATH");
        if (env != null && !env.isBlank()) {
            return readFile(Path.of(env));
        }

        for (Path candidate : List.of(
                Path.of("..", "openzl-quarkus-grpc", "runtime", "src", "test", "resources", "openzl", "sao.bz2"),
                Path.of("..", "openzl-quarkus-grpc", "demo-app", "src", "main", "resources", "openzl", "sao.bz2"),
                Path.of("..", "openzl-quarkus-grpc", "integration-tests", "src", "test", "resources", "openzl", "sao.bz2"),
                Path.of("openzl-quarkus-grpc", "runtime", "src", "test", "resources", "openzl", "sao.bz2"),
                Path.of("openzl-quarkus-grpc", "demo-app", "src", "main", "resources", "openzl", "sao.bz2"),
                Path.of("openzl-quarkus-grpc", "integration-tests", "src", "test", "resources", "openzl", "sao.bz2"))) {
            if (Files.isRegularFile(candidate)) {
                return readFile(candidate);
            }
        }

        throw new IllegalStateException("Unable to locate SAO dataset. Use -Dsao.path=<path> or SILESIA_SAO_PATH.");
    }

    private static byte[] readFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return readAllBytes(in, path.toString());
        }
    }

    private static byte[] readAllBytes(InputStream stream, String source) throws IOException {
        try (BufferedInputStream buffered = new BufferedInputStream(stream)) {
            buffered.mark(4);
            int first = buffered.read();
            int second = buffered.read();
            buffered.reset();
            if (first == 'B' && second == 'Z') {
                try (BZip2CompressorInputStream bz = new BZip2CompressorInputStream(buffered, true)) {
                    return bz.readAllBytes();
                }
            }
            return buffered.readAllBytes();
        } catch (IOException ex) {
            throw new IOException("Failed reading SAO from " + source, ex);
        }
    }

    /**
     * Parses raw SAO catalog bytes into a list of star records.
     * The binary format is 32 bytes per record in big-endian order.
     */
    public static List<SaoStarRecord> parseCatalog(byte[] saoRaw) {
        List<SaoStarRecord> records = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(saoRaw).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 32) {
            int catalog = Short.toUnsignedInt(buf.getShort());
            int saoNum = Short.toUnsignedInt(buf.getShort());
            int ra = buf.getInt();
            int dec = buf.getInt();
            int mag = (int) Math.round(buf.getFloat() * 100.0f);
            byte spec0 = buf.get();
            byte spec1 = buf.get();
            int raPm = (int) Math.round(buf.getFloat() * 1_000_000.0f);
            int decPm = (int) Math.round(buf.getFloat() * 1_000_000.0f);
            int hd = buf.getInt();
            short dm = buf.getShort();
            short gc = buf.getShort();

            if (catalog == 0 && saoNum == 0 && ra == 0 && dec == 0 && mag == 0 && hd == 0 && dm == 0 && gc == 0) {
                continue;
            }

            int catalogNumber = saoNum > 0 ? saoNum : catalog;
            records.add(new SaoStarRecord(
                    catalogNumber,
                    ra,
                    dec,
                    spec0,
                    spec1,
                    mag,
                    raPm,
                    decPm));
        }
        return records;
    }
}
