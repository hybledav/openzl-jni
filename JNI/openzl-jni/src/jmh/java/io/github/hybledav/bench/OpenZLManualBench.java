package io.github.hybledav.bench;

import io.github.hybledav.OpenZLProtobuf;
import io.github.hybledav.SchemaFixtures;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lightweight benchmark harness for JNI conversions. This is intentionally simple to avoid the
 * long startup/warmup overhead we hit with full JMH runs under the current environment.
 */
public final class OpenZLManualBench {

    private OpenZLManualBench() {}

    public static void main(String[] args) throws Exception {
        OpenZLProtobuf.registerSchema(SchemaFixtures.descriptorBytes());
        Payload payload = new Payload(256);

        int warmup = 50;
        int iterations = 200;

        report("proto -> proto", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE));

        report("proto -> ZL", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                SchemaFixtures.MESSAGE_TYPE));

        report("proto -> gzip", warmup, iterations, () -> gzipCompress(payload.protoPayload));

        report("ZL -> proto", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.zlPayload,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE));

        report("gzip -> proto", warmup, iterations, () -> gzipDecompress(payload.gzipPayload));

        report("proto -> JSON", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.JSON,
                SchemaFixtures.MESSAGE_TYPE));

        report("JSON -> proto", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.jsonUtf8,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE));

        report("JSON -> ZL", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.jsonUtf8,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL,
                SchemaFixtures.MESSAGE_TYPE));

        report("ZL -> JSON", warmup, iterations, () -> OpenZLProtobuf.convert(
                payload.zlPayload,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.JSON,
                SchemaFixtures.MESSAGE_TYPE));
    }

    private static void report(String label, int warmup, int iterations, Callable<byte[]> op)
            throws Exception {
        // Warm-up
        for (int i = 0; i < warmup; ++i) {
            op.call();
        }
        long start = System.nanoTime();
        int accumulated = 0;
        for (int i = 0; i < iterations; ++i) {
            accumulated += op.call().length;
        }
        long elapsedNs = System.nanoTime() - start;
        double perOpUs = (elapsedNs / 1_000.0) / iterations;
        double throughputKops = (iterations * 1_000_000.0) / elapsedNs;
        System.out.printf(Locale.ROOT,
                "%-15s  %8.3f µs/op  %8.2f Kops/s  (avg payload %d B)%n",
                label,
                perOpUs,
                throughputKops,
                accumulated / iterations);
    }

    private static byte[] gzipCompress(byte[] payload) throws Exception {
        try (java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(payload);
            gzip.finish();
            return buffer.toByteArray();
        }
    }

    private static byte[] gzipDecompress(byte[] payload) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(payload))) {
            return gzip.readAllBytes();
        }
    }

    private static final class Payload {
        final byte[] protoPayload;
        final byte[] zlPayload;
        final byte[] gzipPayload;
        final byte[] jsonUtf8;

        Payload(int targetBytes) {
            String json = buildJson(targetBytes);
            String canonical = OpenZLProtobuf.convertJson(
                    json,
                    OpenZLProtobuf.Protocol.JSON,
                    OpenZLProtobuf.Protocol.JSON,
                    SchemaFixtures.MESSAGE_TYPE);
            jsonUtf8 = canonical.getBytes(StandardCharsets.UTF_8);
            protoPayload = OpenZLProtobuf.convert(
                    jsonUtf8,
                    OpenZLProtobuf.Protocol.JSON,
                    OpenZLProtobuf.Protocol.PROTO,
                    SchemaFixtures.MESSAGE_TYPE);
            zlPayload = OpenZLProtobuf.convert(
                    protoPayload,
                    OpenZLProtobuf.Protocol.PROTO,
                    OpenZLProtobuf.Protocol.ZL,
                    SchemaFixtures.MESSAGE_TYPE);
            try {
                gzipPayload = gzipCompress(protoPayload);
                // sanity check
                byte[] restored = gzipDecompress(gzipPayload);
                if (restored.length != protoPayload.length) {
                    throw new IllegalStateException("gzip round-trip length mismatch");
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to prepare gzip payload", ex);
            }
        }

        private static String buildJson(int targetBytes) {
            int base = Math.max(1, targetBytes / 64);
            String ascii = generateAscii(targetBytes);
            String base64 = Base64.getEncoder().encodeToString(ascii.getBytes(StandardCharsets.UTF_8));
            String repeatedInts = String.format("[%d,%d,%d]", base, base + 1, base + 2);
            String nested = String.format("{\"optional_int32\":%d}", base * 2);
            String repeatedNested = String.format("[%s,{\"optional_int32\":%d}]", nested, base * 3);

            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"optional_int32\":").append(base).append(',');
            sb.append("\"optional_int64\":\"").append(200L + base).append("\",");
            sb.append("\"optional_uint32\":").append(300 + base).append(',');
            sb.append("\"optional_uint64\":\"").append(400L + base).append("\",");
            sb.append("\"optional_sint32\":").append(-500 - base).append(',');
            sb.append("\"optional_sint64\":\"").append(-600L - base).append("\",");
            sb.append("\"optional_fixed32\":").append(700 + base).append(',');
            sb.append("\"optional_fixed64\":\"").append(800L + base).append("\",");
            sb.append("\"optional_sfixed32\":").append(-900 - base).append(',');
            sb.append("\"optional_sfixed64\":\"").append(-1000L - base).append("\",");
            sb.append("\"optional_float\":").append(1.5f + base).append(',');
            sb.append("\"optional_double\":").append(2.5 + base).append(',');
            sb.append("\"optional_bool\":").append(base % 2 == 0).append(',');
            sb.append("\"optional_string\":\"").append(ascii).append("\",");
            sb.append("\"optional_bytes\":\"").append(base64).append("\",");
            sb.append("\"optional_nested\":").append(nested).append(',');
            sb.append("\"optional_enum\":\"").append(base % 2 == 0 ? "ONE" : "ZERO").append("\",");
            sb.append("\"repeated_int32\":").append(repeatedInts).append(',');
            sb.append("\"repeated_nested\":").append(repeatedNested).append(',');
            sb.append("\"repeated_enum\":[\"ZERO\",\"ONE\"]");
            sb.append('}');
            return sb.toString();
        }

        private static String generateAscii(int length) {
            if (length <= 0) {
                return "";
            }
            char[] chars = new char[length];
            for (int i = 0; i < length; ++i) {
                chars[i] = (char) ('a' + (i % 26));
            }
            return new String(chars);
        }
    }
}
