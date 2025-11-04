package io.github.hybledav.bench;

import io.github.hybledav.OpenZLProtobuf;
import io.github.hybledav.SchemaFixtures;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks for JNI conversion paths. This suite avoids gRPC plumbing so we can
 * compare raw PROTO/ZL/JSON transformations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class OpenZLConvertBenchmark {

    @State(Scope.Thread)
    public static class PayloadState {

        @Param({"256"})
        public int targetBytes;

        byte[] protoPayload;
        byte[] zlPayload;

        byte[] jsonUtf8;

        @Setup(Level.Trial)
        public void registerSchema() {
            OpenZLProtobuf.registerSchema(SchemaFixtures.descriptorBytes());
        }

        @Setup(Level.Iteration)
        public void preparePayloads() {
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
    }

    @Benchmark
    public byte[] protoToProtoArray(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] protoToZlArray(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] zlToProtoArray(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.zlPayload,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] protoToJson(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.protoPayload,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.JSON,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] jsonToProto(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.jsonUtf8,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.PROTO,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] jsonToZl(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.jsonUtf8,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL,
                SchemaFixtures.MESSAGE_TYPE);
    }

    @Benchmark
    public byte[] zlToJson(PayloadState state) {
        return OpenZLProtobuf.convert(
                state.zlPayload,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.JSON,
                SchemaFixtures.MESSAGE_TYPE);
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
