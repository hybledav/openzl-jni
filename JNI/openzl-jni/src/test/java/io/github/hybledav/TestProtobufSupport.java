package io.github.hybledav;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class TestProtobufSupport {
    private static final String MESSAGE_TYPE = SchemaFixtures.MESSAGE_TYPE;

    static {
        OpenZLProtobuf.registerSchema(SchemaFixtures.descriptorBytes());
    }

    private static String sampleJson(int seed) {
        int base = 10 + seed;
        String repeatedInts = String.format("[%d,%d,%d]", base, base + 1, base + 2);
        String nested = String.format("{\"optional_int32\":%d}", base * 2);
        String repeatedNested = String.format("[%s,{\"optional_int32\":%d}]", nested, base * 3);

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"optional_int32\":").append(100 + seed).append(',');
        sb.append("\"optional_int64\":\"").append(200L + seed).append("\",");
        sb.append("\"optional_uint32\":").append(300 + seed).append(',');
        sb.append("\"optional_uint64\":\"").append(400L + seed).append("\",");
        sb.append("\"optional_sint32\":").append(-500 - seed).append(',');
        sb.append("\"optional_sint64\":\"").append(-600L - seed).append("\",");
        sb.append("\"optional_fixed32\":").append(700 + seed).append(',');
        sb.append("\"optional_fixed64\":\"").append(800L + seed).append("\",");
        sb.append("\"optional_sfixed32\":").append(-900 - seed).append(',');
        sb.append("\"optional_sfixed64\":\"").append(-1000L - seed).append("\",");
        sb.append("\"optional_float\":").append(1.5f + seed).append(',');
        sb.append("\"optional_double\":").append(2.5 + seed).append(',');
        sb.append("\"optional_bool\":").append(seed % 2 == 0).append(',');
        sb.append("\"optional_string\":\"seed-").append(seed).append("\",");
        sb.append("\"optional_bytes\":\"").append(base64ForSeed(seed)).append("\",");
        sb.append("\"optional_nested\":").append(nested).append(',');
        sb.append("\"optional_enum\":\"").append(seed % 2 == 0 ? "ONE" : "ZERO").append("\",");
        sb.append("\"repeated_int32\":").append(repeatedInts).append(',');
        sb.append("\"repeated_nested\":").append(repeatedNested).append(',');
        sb.append("\"repeated_enum\":[\"ZERO\",\"ONE\"]");
        sb.append('}');
        return sb.toString();
    }

    private static String base64ForSeed(int seed) {
        byte[] data = ("seed-" + seed).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(data);
    }

    private static String canonicalJson(String rawJson) {
        return convertJson(rawJson,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.JSON);
    }

    private static byte[] toProtoBytes(String rawJson) {
        String canonical = canonicalJson(rawJson);
        return convert(canonical.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.PROTO);
    }

    private static byte[] randomBytes() {
        return "not-a-valid-proto".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] convert(byte[] payload,
            OpenZLProtobuf.Protocol input,
            OpenZLProtobuf.Protocol output) {
        return OpenZLProtobuf.convert(payload, input, output, MESSAGE_TYPE);
    }

    private static byte[] convert(byte[] payload,
            OpenZLProtobuf.Protocol input,
            OpenZLProtobuf.Protocol output,
            byte[] compressor) {
        return OpenZLProtobuf.convert(payload, input, output, compressor, MESSAGE_TYPE);
    }

    private static String convertJson(String payload,
            OpenZLProtobuf.Protocol input,
            OpenZLProtobuf.Protocol output) {
        return OpenZLProtobuf.convertJson(payload, input, output, MESSAGE_TYPE);
    }

    private static byte[][] train(byte[][] samples,
            OpenZLProtobuf.Protocol protocol,
            TrainOptions options) {
        return OpenZLProtobuf.train(samples, protocol, options, MESSAGE_TYPE);
    }

    @Test
    public void convertRoundTripJsonZlProto() {
        String canonical = canonicalJson(sampleJson(0));
        byte[] zl = convert(canonical.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL);
        String fromZl = new String(convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.JSON), StandardCharsets.UTF_8);
        assertEquals(canonical, fromZl);

        byte[] proto = convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO);
        String fromProto = new String(convert(proto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.JSON), StandardCharsets.UTF_8);
        assertEquals(canonical, fromProto);
    }

    @Test
    public void convertWithTrainedCompressorProducesComparableSize() {
        byte[][] protoSamples = new byte[4][];
        for (int i = 0; i < protoSamples.length; ++i) {
            protoSamples[i] = toProtoBytes(sampleJson(i));
        }

        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        opts.threads = 1;
        opts.numSamples = 0;
        opts.paretoFrontier = false;

        byte[][] trained = train(protoSamples, OpenZLProtobuf.Protocol.PROTO, opts);
        assertNotNull(trained);
        assertTrue(trained.length > 0, "expected at least one trained compressor");

        byte[] baseline = convert(protoSamples[0],
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] improved = convert(protoSamples[0],
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                trained[0]);
        assertNotNull(baseline);
        assertNotNull(improved);
        assertTrue(improved.length <= baseline.length,
                () -> "trained output should not be larger than baseline: "
                        + improved.length + " vs " + baseline.length);
    }

    @Test
    public void trainedCompressorBeatsDefaultOnRepetitivePayload() {
        String repetitiveJson = "{" +
                "\"optional_string\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"," +
                "\"optional_int32\":123," +
                "\"optional_bytes\":\"" + base64ForSeed(999) + "\"," +
                "\"repeated_int32\":[1,1,1,1,1]," +
                "\"repeated_enum\":[\"ZERO\",\"ZERO\",\"ZERO\"]" +
                "}";
        byte[] protoMessage = toProtoBytes(repetitiveJson);

        byte[][] trainingSamples = new byte[12][];
        for (int i = 0; i < trainingSamples.length; ++i) {
            trainingSamples[i] = protoMessage;
        }

        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 1;
        opts.threads = 1;
        opts.numSamples = 0;
        opts.paretoFrontier = false;

        byte[][] trained = train(trainingSamples, OpenZLProtobuf.Protocol.PROTO, opts);
        assertNotNull(trained);
        assertTrue(trained.length > 0, "expected trained compressors to be returned");

        byte[] baseline = convert(protoMessage,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] improved = convert(protoMessage,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                trained[0]);
        assertTrue(improved.length < baseline.length,
                () -> "trained compressor should shrink repetitive payload: "
                        + improved.length + " vs " + baseline.length);
    }

    @Test
    public void convertRejectsInvalidPayload() {
        assertThrows(IllegalArgumentException.class, () -> convert(randomBytes(),
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.JSON));
    }

    @Test
    public void convertersHandleEmptyPayload() {
        String emptyJson = "{}";
        byte[] emptyProto = convert(emptyJson.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.PROTO);
        assertNotNull(emptyProto);

        byte[] emptyZl = convert(emptyJson.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL);
        assertNotNull(emptyZl);
    }
}
