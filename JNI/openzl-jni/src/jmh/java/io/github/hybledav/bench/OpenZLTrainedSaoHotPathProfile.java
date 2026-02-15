package io.github.hybledav.bench;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.github.hybledav.OpenZLProtobuf;
import io.github.hybledav.SchemaFixtures;
import io.github.hybledav.TrainOptions;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

public final class OpenZLTrainedSaoHotPathProfile {

    private OpenZLTrainedSaoHotPathProfile() {}

    public static void main(String[] args) throws Exception {
        String saoPath = System.getProperty("sao.path", "/tmp/sao.raw");
        byte[] sao = Files.readAllBytes(Path.of(saoPath));

        OpenZLProtobuf.registerSchema(SchemaFixtures.descriptorBytes());
        Descriptors.Descriptor descriptor = SchemaFixtures.descriptor();

        byte[][] trainingSamples = buildTrainingSamples(descriptor, sao, 256, 4096);
        byte[] evalProto = buildSample(descriptor, sao, 11_000, 8192);

        TrainOptions opts = new TrainOptions();
        opts.maxTimeSecs = 20;
        opts.threads = 4;
        opts.numSamples = 256;
        opts.paretoFrontier = false;

        long trainStart = System.nanoTime();
        byte[][] trained = OpenZLProtobuf.train(trainingSamples, OpenZLProtobuf.Protocol.PROTO, opts, descriptor);
        long trainNs = System.nanoTime() - trainStart;
        byte[] compressor = trained[0];

        System.out.printf(Locale.ROOT,
                "SAO bytes=%d, train samples=%d x %d B, trained compressors=%d, selected=%d B, train=%.2f ms%n",
                sao.length,
                trainingSamples.length,
                trainingSamples[0].length,
                trained.length,
                compressor.length,
                trainNs / 1_000_000.0);

        int warmup = 2_000;
        int iterations = 20_000;

        ByteBuffer protoInput = ByteBuffer.allocateDirect(evalProto.length);
        protoInput.put(evalProto).flip();
        ByteBuffer zlOutput = ByteBuffer.allocateDirect(evalProto.length * 2);

        for (int i = 0; i < warmup; ++i) {
            encodeOnce(protoInput, evalProto.length, descriptor, compressor, zlOutput);
        }

        long[] beforeEncodeCounters = OpenZLProtobuf.directIntoProfileValues();
        long encodeStart = System.nanoTime();
        int totalEncoded = 0;
        for (int i = 0; i < iterations; ++i) {
            totalEncoded += encodeOnce(protoInput, evalProto.length, descriptor, compressor, zlOutput);
        }
        long encodeNs = System.nanoTime() - encodeStart;
        long[] afterEncodeCounters = OpenZLProtobuf.directIntoProfileValues();

        byte[] zlPayload = OpenZLProtobuf.convert(evalProto,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                compressor,
                descriptor);
        ByteBuffer zlInput = ByteBuffer.allocateDirect(zlPayload.length);
        zlInput.put(zlPayload).flip();

        for (int i = 0; i < warmup; ++i) {
            decodeOnce(zlPayload, descriptor, compressor);
        }

        long[] beforeDecodeCounters = OpenZLProtobuf.directIntoProfileValues();
        long decodeStart = System.nanoTime();
        int totalDecoded = 0;
        for (int i = 0; i < iterations; ++i) {
            totalDecoded += decodeOnce(zlPayload, descriptor, compressor);
        }
        long decodeNs = System.nanoTime() - decodeStart;
        long[] afterDecodeCounters = OpenZLProtobuf.directIntoProfileValues();

        report("PROTO->ZL", encodeNs, iterations, evalProto.length, totalEncoded / iterations, beforeEncodeCounters, afterEncodeCounters);
        report("ZL->PROTO", decodeNs, iterations, zlPayload.length, totalDecoded / iterations, beforeDecodeCounters, afterDecodeCounters);
    }

    private static void report(String label,
                               long elapsedNs,
                               int iterations,
                               int avgInBytes,
                               int avgOutBytes,
                               long[] before,
                               long[] after) {
        long calls = Math.max(1, after[4] - before[4]);
        long parse = Math.max(0, after[1] - before[1]);
        long serialize = Math.max(0, after[2] - before[2]);
        long write = Math.max(0, after[3] - before[3]);
        long staged = parse + serialize + write;

        double wallUs = (elapsedNs / 1_000.0) / iterations;
        double kops = (iterations * 1_000_000.0) / elapsedNs;
        double parseUs = parse / (double) calls / 1_000.0;
        double serializeUs = serialize / (double) calls / 1_000.0;
        double writeUs = write / (double) calls / 1_000.0;
        double stageTotalUs = staged / (double) calls / 1_000.0;

        double parsePct = staged == 0 ? 0 : 100.0 * parse / staged;
        double serializePct = staged == 0 ? 0 : 100.0 * serialize / staged;
        double writePct = staged == 0 ? 0 : 100.0 * write / staged;

        System.out.printf(Locale.ROOT,
                "%n%s%n  wall: %.3f us/op, %.2f Kops/s%n  payload: in=%d B, out=%d B%n"
                        + "  native stage avg (us/op): parse=%.3f (%.1f%%), serialize=%.3f (%.1f%%), write=%.3f (%.1f%%), sum=%.3f%n"
                        + "  calls sampled=%d%n",
                label,
                wallUs,
                kops,
                avgInBytes,
                avgOutBytes,
                parseUs,
                parsePct,
                serializeUs,
                serializePct,
                writeUs,
                writePct,
                stageTotalUs,
                calls);
    }

    private static int encodeOnce(ByteBuffer input,
                                  int inputLength,
                                  Descriptors.Descriptor descriptor,
                                  byte[] compressor,
                                  ByteBuffer output) {
        input.position(0);
        output.clear();
        int written = OpenZLProtobuf.convertInto(
                input,
                inputLength,
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                compressor,
                descriptor,
                output);
        if (written < 0) {
            throw new IllegalStateException("Output buffer too small for encode: " + written);
        }
        return written;
    }

    private static int decodeOnce(byte[] input,
                                  Descriptors.Descriptor descriptor,
                                  byte[] compressor) {
        byte[] decoded = OpenZLProtobuf.convert(
                input,
                OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,
                compressor,
                descriptor);
        return decoded.length;
    }

    private static byte[][] buildTrainingSamples(Descriptors.Descriptor descriptor,
                                                 byte[] sao,
                                                 int sampleCount,
                                                 int bytesPerSample) {
        byte[][] out = new byte[sampleCount][];
        for (int i = 0; i < sampleCount; ++i) {
            out[i] = buildSample(descriptor, sao, i * 997, bytesPerSample);
        }
        return out;
    }

    private static byte[] buildSample(Descriptors.Descriptor descriptor,
                                      byte[] source,
                                      int offsetSeed,
                                      int chunkBytes) {
        int length = Math.min(chunkBytes, source.length);
        int maxOffset = Math.max(1, source.length - length);
        int offset = Math.floorMod(offsetSeed, maxOffset);
        byte[] chunk = new byte[length];
        System.arraycopy(source, offset, chunk, 0, length);

        Descriptors.FieldDescriptor intField = descriptor.findFieldByName("optional_int32");
        Descriptors.FieldDescriptor stringField = descriptor.findFieldByName("optional_string");
        Descriptors.FieldDescriptor bytesField = descriptor.findFieldByName("optional_bytes");
        Descriptors.FieldDescriptor repIntField = descriptor.findFieldByName("repeated_int32");

        String token = Base64.getEncoder().encodeToString(slice(chunk, 96));

        DynamicMessage.Builder b = DynamicMessage.newBuilder(descriptor);
        b.setField(intField, offsetSeed);
        b.setField(stringField, "sao-" + offsetSeed + "-" + token);
        b.setField(bytesField, ByteString.copyFrom(chunk));
        for (int i = 0; i < 8; ++i) {
            b.addRepeatedField(repIntField, (offsetSeed + i * 31) & 0x7fffffff);
        }
        return b.build().toByteArray();
    }

    private static byte[] slice(byte[] data, int n) {
        int len = Math.min(n, data.length);
        byte[] out = new byte[len];
        System.arraycopy(data, 0, out, 0, len);
        return out;
    }
}
