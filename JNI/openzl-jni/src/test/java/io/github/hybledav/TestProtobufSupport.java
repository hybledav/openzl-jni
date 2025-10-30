package io.github.hybledav;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class TestProtobufSupport {
    private static String sampleJson(int seed) {
        int base = 10 + seed;
        String repeatedInts = String.format("[%d,%d,%d]", base, base + 1, base + 2);
        String nested = String.format("{\"optional_int32\":%d}", base * 2);
        String repeated_nested = String.format("[%s,{\"optional_int32\":%d}]", nested, base * 3);

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
        sb.append("\"repeated_nested\":").append(repeated_nested).append(',');
        sb.append("\"repeated_enum\":[\"ZERO\",\"ONE\"]");
        sb.append('}');
        return sb.toString();
    }

    private static String base64ForSeed(int seed) {
        byte[] data = ("seed-" + seed).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(data);
    }

    private static String canonicalJson(String rawJson) {
        return OpenZLProtobuf.convertJson(rawJson, OpenZLProtobuf.Protocol.JSON, OpenZLProtobuf.Protocol.JSON);
    }

    private static byte[] toProtoBytes(String rawJson) {
        String canonical = canonicalJson(rawJson);
        return OpenZLProtobuf.convert(canonical.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.PROTO);
    }

    private static byte[] randomBytes() {
        return "not-a-valid-proto".getBytes(StandardCharsets.UTF_8);
    }



    @Test
    public void convertRoundTripJsonZlProto() {
        String canonical = canonicalJson(sampleJson(0));
        byte[] zl = OpenZLProtobuf.convert(canonical.getBytes(StandardCharsets.UTF_8),
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL);
        String fromZl = new String(OpenZLProtobuf.convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.JSON), StandardCharsets.UTF_8);
        assertEquals(canonical, fromZl);

        byte[] proto = OpenZLProtobuf.convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO);
        String fromProto = new String(OpenZLProtobuf.convert(proto,
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

        byte[][] trained = OpenZLProtobuf.train(protoSamples, OpenZLProtobuf.Protocol.PROTO, opts);
        assertNotNull(trained);
        assertTrue(trained.length > 0, "expected at least one trained compressor");

        byte[] baseline = OpenZLProtobuf.convert(protoSamples[0],
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] improved = OpenZLProtobuf.convert(protoSamples[0],
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

        byte[][] trained = OpenZLProtobuf.train(trainingSamples, OpenZLProtobuf.Protocol.PROTO, opts);
        assertNotNull(trained);
        assertTrue(trained.length > 0, "expected trained compressors to be returned");

        byte[] baseline = OpenZLProtobuf.convert(protoMessage,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] improved = OpenZLProtobuf.convert(protoMessage,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                trained[0]);

        assertTrue(improved.length < baseline.length,
                () -> "trained compressor should produce smaller payload than baseline: "
                        + improved.length + " vs " + baseline.length);
    }

    @Test
    public void convertProtoRoundTripMatchesBytes() {
        byte[] proto = toProtoBytes(sampleJson(2));
        byte[] zl = OpenZLProtobuf.convert(proto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] back = OpenZLProtobuf.convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO);
        assertArrayEquals(proto, back);
    }

    @Test
    public void convertJsonCanonicalisesWhitespace() {
        String compact = "{\"optional_int32\":1,\"optional_string\":\" spaced \",\"repeated_enum\":[\"ZERO\",\"ONE\"]}";
        String spaced = " { \"optional_int32\" : 1 , \"optional_string\" : \" spaced \", \"repeated_enum\" : [ \"ZERO\" , \"ONE\" ] } ";
        String canonical = OpenZLProtobuf.convertJson(compact,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.JSON);
        String canonicalSpaced = OpenZLProtobuf.convertJson(spaced,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.JSON);
        assertEquals(canonical, canonicalSpaced);
    }

    @Test
    public void convertRejectsNullPayload() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(null,
                        OpenZLProtobuf.Protocol.JSON,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void convertRejectsNullInputProtocol() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(new byte[0],
                        null,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void convertRejectsNullOutputProtocol() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(new byte[0],
                        OpenZLProtobuf.Protocol.JSON,
                        null));
    }

    @Test
    public void convertJsonInvalidPayloadThrows() {
        byte[] invalidJson = "{not-json}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> OpenZLProtobuf.convert(invalidJson,
                        OpenZLProtobuf.Protocol.JSON,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void convertProtoInvalidPayloadThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenZLProtobuf.convert(randomBytes(),
                        OpenZLProtobuf.Protocol.PROTO,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void trainWithJsonSamplesProducesCompressors() {
        byte[][] jsonSamples = new byte[3][];
        for (int i = 0; i < jsonSamples.length; ++i) {
            jsonSamples[i] = canonicalJson(sampleJson(i)).getBytes(StandardCharsets.UTF_8);
        }
        byte[][] trained = OpenZLProtobuf.train(jsonSamples, OpenZLProtobuf.Protocol.JSON, null);
        assertNotNull(trained);
        assertTrue(trained.length > 0);

        byte[] zl = OpenZLProtobuf.convert(jsonSamples[0],
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL,
                trained[0]);
        byte[] restoredJson = OpenZLProtobuf.convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.JSON);
        assertEquals(new String(jsonSamples[0], StandardCharsets.UTF_8),
                new String(restoredJson, StandardCharsets.UTF_8));
    }

    @Test
    public void trainRejectsEmptySamples() {
        TrainOptions opts = new TrainOptions();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OpenZLProtobuf.train(new byte[0][],
                        OpenZLProtobuf.Protocol.PROTO,
                        opts));
        assertTrue(ex.getMessage().contains("samples"), "expected message to mention samples");
    }

    @Test
    public void trainRejectsNullElement() {
        byte[][] samples = new byte[][] { toProtoBytes(sampleJson(0)), null };
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.train(samples,
                        OpenZLProtobuf.Protocol.PROTO,
                        null));
        assertTrue(ex.getMessage().contains("samples"), "expected message to reference samples");
    }

    @Test
    public void trainRejectsNullProtocol() {
        byte[][] samples = new byte[][] { toProtoBytes(sampleJson(0)) };
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.train(samples, null, null));
    }
}
