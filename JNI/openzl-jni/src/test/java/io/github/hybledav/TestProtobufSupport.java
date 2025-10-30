package io.github.hybledav;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class TestProtobufSupport {
    private static final String MESSAGE_TYPE = "openzl.protobuf.Schema";
    private static final byte[] DESCRIPTOR_SET = Base64.getDecoder().decode(
            "CusLCgxzY2hlbWEucHJvdG8SD29wZW56bC5wcm90b2J1ZiJNCgxOZXN0ZWRTY2hlbWESKgoOb3B0"
                    + "aW9uYWxfaW50MzIYFiABKAVIAFINb3B0aW9uYWxJbnQzMogBAUIRCg9fb3B0aW9uYWxfaW50MzIi"
                    + "0QoKBlNjaGVtYRIqCg5vcHRpb25hbF9pbnQzMhgBIAEoBUgAUg1vcHRpb25hbEludDMyiAEBEioK"
                    + "Dm9wdGlvbmFsX2ludDY0GAIgASgDSAFSDW9wdGlvbmFsSW50NjSIAQESLAoPb3B0aW9uYWxfdWlu"
                    + "dDMyGAMgASgNSAJSDm9wdGlvbmFsVWludDMyiAEBEiwKD29wdGlvbmFsX3VpbnQ2NBgEIAEoBEgD"
                    + "Ug5vcHRpb25hbFVpbnQ2NIgBARIsCg9vcHRpb25hbF9zaW50MzIYBSABKBFIBFIOb3B0aW9uYWxT"
                    + "aW50MzKIAQESLAoPb3B0aW9uYWxfc2ludDY0GAYgASgSSAVSDm9wdGlvbmFsU2ludDY0iAEBEi4K"
                    + "EG9wdGlvbmFsX2ZpeGVkMzIYByABKAdIBlIPb3B0aW9uYWxGaXhlZDMyiAEBEi4KEG9wdGlvbmFs"
                    + "X2ZpeGVkNjQYCCABKAZIB1IPb3B0aW9uYWxGaXhlZDY0iAEBEjAKEW9wdGlvbmFsX3NmaXhlZDMy"
                    + "GAkgASgPSAhSEG9wdGlvbmFsU2ZpeGVkMzKIAQESMAoRb3B0aW9uYWxfc2ZpeGVkNjQYCiABKBBI"
                    + "CVIQb3B0aW9uYWxTZml4ZWQ2NIgBARIqCg5vcHRpb25hbF9mbG9hdBgLIAEoAkgKUg1vcHRpb25h"
                    + "bEZsb2F0iAEBEiwKD29wdGlvbmFsX2RvdWJsZRgMIAEoAUgLUg5vcHRpb25hbERvdWJsZYgBARIo"
                    + "Cg1vcHRpb25hbF9ib29sGA0gASgISAxSDG9wdGlvbmFsQm9vbIgBARIsCg9vcHRpb25hbF9zdHJp"
                    + "bmcYDiABKAlIDVIOb3B0aW9uYWxTdHJpbmeIAQESKgoOb3B0aW9uYWxfYnl0ZXMYDyABKAxIDlIN"
                    + "b3B0aW9uYWxCeXRlc4gBARJLCg9vcHRpb25hbF9uZXN0ZWQYECABKAsyHS5vcGVuemwucHJvdG9i"
                    + "dWYuTmVzdGVkU2NoZW1hSA9SDm9wdGlvbmFsTmVzdGVkiAEBEkUKDW9wdGlvbmFsX2VudW0YESAB"
                    + "KA4yGy5vcGVuemwucHJvdG9idWYuRW51bVNjaGVtYUgQUgxvcHRpb25hbEVudW2IAQESJQoOcmVw"
                    + "ZWF0ZWRfaW50MzIYEiADKAVSDXJlcGVhdGVkSW50MzISRgoPcmVwZWF0ZWRfbmVzdGVkGBMgAygL"
                    + "Mh0ub3BlbnpsLnByb3RvYnVmLk5lc3RlZFNjaGVtYVIOcmVwZWF0ZWROZXN0ZWQSQAoNcmVwZWF0"
                    + "ZWRfZW51bRgUIAMoDjIbLm9wZW56bC5wcm90b2J1Zi5FbnVtU2NoZW1hUgxyZXBlYXRlZEVudW1C"
                    + "EQoPX29wdGlvbmFsX2ludDMyQhEKD19vcHRpb25hbF9pbnQ2NEISChBfb3B0aW9uYWxfdWludDMy"
                    + "QhIKEF9vcHRpb25hbF91aW50NjRCEgoQX29wdGlvbmFsX3NpbnQzMkISChBfb3B0aW9uYWxfc2lu"
                    + "dDY0QhMKEV9vcHRpb25hbF9maXhlZDMyQhMKEV9vcHRpb25hbF9maXhlZDY0QhQKEl9vcHRpb25h"
                    + "bF9zZml4ZWQzMkIUChJfb3B0aW9uYWxfc2ZpeGVkNjRCEQoPX29wdGlvbmFsX2Zsb2F0QhIKEF9v"
                    + "cHRpb25hbF9kb3VibGVCEAoOX29wdGlvbmFsX2Jvb2xCEgoQX29wdGlvbmFsX3N0cmluZ0IRCg9f"
                    + "b3B0aW9uYWxfYnl0ZXNCEgoQX29wdGlvbmFsX25lc3RlZEIQCg5fb3B0aW9uYWxfZW51bSofCgpF"
                    + "bnVtU2NoZW1hEggKBFpFUk8QABIHCgNPTkUQAWIGcHJvdG8z");

    static {
        OpenZLProtobuf.registerSchema(DESCRIPTOR_SET);
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
                () -> "trained compressor should produce smaller payload than baseline: "
                        + improved.length + " vs " + baseline.length);
    }

    @Test
    public void convertProtoRoundTripMatchesBytes() {
        byte[] proto = toProtoBytes(sampleJson(2));
        byte[] zl = convert(proto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL);
        byte[] back = convert(zl,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO);
        assertArrayEquals(proto, back);
    }

    @Test
    public void convertJsonCanonicalisesWhitespace() {
        String compact = "{\"optional_int32\":1,\"optional_string\":\" spaced \",\"repeated_enum\":[\"ZERO\",\"ONE\"]}";
        String spaced = " { \"optional_int32\" : 1 , \"optional_string\" : \" spaced \", \"repeated_enum\" : [ \"ZERO\" , \"ONE\" ] } ";
        String canonical = convertJson(compact,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.JSON);
        String canonicalSpaced = convertJson(spaced,
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.JSON);
        assertEquals(canonical, canonicalSpaced);
    }

    @Test
    public void convertRejectsNullPayload() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(null,
                        OpenZLProtobuf.Protocol.JSON,
                        OpenZLProtobuf.Protocol.ZL,
                        MESSAGE_TYPE));
    }

    @Test
    public void convertRejectsNullInputProtocol() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(new byte[0],
                        null,
                        OpenZLProtobuf.Protocol.ZL,
                        MESSAGE_TYPE));
    }

    @Test
    public void convertRejectsNullOutputProtocol() {
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.convert(new byte[0],
                        OpenZLProtobuf.Protocol.JSON,
                        null,
                        MESSAGE_TYPE));
    }

    @Test
    public void convertJsonInvalidPayloadThrows() {
        byte[] invalidJson = "{not-json}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> convert(invalidJson,
                        OpenZLProtobuf.Protocol.JSON,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void convertProtoInvalidPayloadThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> convert(randomBytes(),
                        OpenZLProtobuf.Protocol.PROTO,
                        OpenZLProtobuf.Protocol.ZL));
    }

    @Test
    public void trainWithJsonSamplesProducesCompressors() {
        byte[][] jsonSamples = new byte[3][];
        for (int i = 0; i < jsonSamples.length; ++i) {
            jsonSamples[i] = canonicalJson(sampleJson(i)).getBytes(StandardCharsets.UTF_8);
        }
        byte[][] trained = train(jsonSamples, OpenZLProtobuf.Protocol.JSON, null);
        assertNotNull(trained);
        assertTrue(trained.length > 0);

        byte[] zl = convert(jsonSamples[0],
                OpenZLProtobuf.Protocol.JSON,
                OpenZLProtobuf.Protocol.ZL,
                trained[0]);
        byte[] restoredJson = convert(zl,
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
                        opts,
                        MESSAGE_TYPE));
        assertTrue(ex.getMessage().contains("samples"), "expected message to mention samples");
    }

    @Test
    public void trainRejectsNullElement() {
        byte[][] samples = new byte[][] { toProtoBytes(sampleJson(0)), null };
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.train(samples,
                        OpenZLProtobuf.Protocol.PROTO,
                        null,
                        MESSAGE_TYPE));
        assertTrue(ex.getMessage().contains("samples"), "expected message to reference samples");
    }

    @Test
    public void trainRejectsNullProtocol() {
        byte[][] samples = new byte[][] { toProtoBytes(sampleJson(0)) };
        assertThrows(NullPointerException.class,
                () -> OpenZLProtobuf.train(samples, null, null, MESSAGE_TYPE));
    }
}
