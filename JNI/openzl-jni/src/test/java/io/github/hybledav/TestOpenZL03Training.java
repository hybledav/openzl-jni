package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestOpenZL03Training {
    private static TrainedCompressor dictionaryCandidate;
    private static byte[][] samples;

    @BeforeAll
    static void trainDictionary() {
        samples = new byte[128][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = ("{\"account\":\"customer-" + i + "\",\"region\":\"eu-central\","
                    + "\"status\":\"accepted\",\"currency\":\"EUR\",\"sequence\":" + i
                    + ",\"items\":[{\"sku\":\"openzl-sample-product\",\"quantity\":3,\"price\":19.99}],"
                    + "\"description\":\"Reusable dictionary compression sample for a purchase order\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        TrainOptions options = new TrainOptions();
        options.dictionaryTraining = true;
        TrainedCompressor[] trained = OpenZLCompressor.trainCandidates("zstd", samples, options);
        assertTrue(trained.length > 0);
        dictionaryCandidate = trained[0];
        assertTrue(dictionaryCandidate.hasDictionaryBundle(), "Dictionary bytes must not be dropped by JNI");
    }

    @Test
    void dictionaryCandidateSurvivesSavingAndLoadingInFreshInstances(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("graph.zc"), dictionaryCandidate.serializedCompressor());
        Files.write(directory.resolve("dict.zd"), dictionaryCandidate.dictionaryBundle());
        TrainedCompressor restored = new TrainedCompressor("zstd",
                Files.readAllBytes(directory.resolve("graph.zc")), Files.readAllBytes(directory.resolve("dict.zd")));
        byte[] input = samples[17];
        byte[] frame;
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureTrained(restored);
            frame = compressor.compress(input);
            assertArrayEquals(input, compressor.decompress(frame));
            assertEquals(27, compressor.inspect(frame).formatVersion());
            compressor.reset();
            assertArrayEquals(input, compressor.decompress(frame));
            assertArrayEquals(input, compressor.decompress(compressor.compress(input)));

            ByteBuffer source = ByteBuffer.allocateDirect(input.length).put(input).flip();
            ByteBuffer destination = ByteBuffer.allocateDirect((int) OpenZLCompressor.maxCompressedSize(input.length));
            compressor.compress(source, destination);
            destination.flip();
            ByteBuffer result = ByteBuffer.allocateDirect(input.length);
            compressor.decompress(destination, result);
            byte[] bytes = new byte[result.position()];
            result.flip().get(bytes);
            assertArrayEquals(input, bytes);
        }
        try (OpenZLCompressor decoder = new OpenZLCompressor()) {
            assertNull(decoder.decompress(frame), "A fresh decoder must not inherit pooled dictionary state");
            decoder.setDictionaryBundle(restored.dictionaryBundle());
            assertArrayEquals(input, decoder.decompress(frame));
            assertThrows(IllegalArgumentException.class, () -> decoder.setDictionaryBundle(new byte[] {1, 2, 3}));
            assertArrayEquals(input, decoder.decompress(frame), "A rejected bundle must preserve the valid one");
            decoder.reset();
            assertArrayEquals(input, decoder.decompress(frame));
            decoder.setDictionaryBundle(new byte[0]);
            assertNull(decoder.decompress(frame));
        }
    }

    @Test
    void incompleteOrCorruptCandidatesDoNotReplaceAWorkingConfiguration() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureTrained(dictionaryCandidate);
            byte[] frame = compressor.compress(samples[0]);
            TrainedCompressor missing = new TrainedCompressor("zstd",
                    dictionaryCandidate.serializedCompressor(), new byte[0]);
            assertThrows(IllegalArgumentException.class, () -> compressor.configureTrained(missing));
            assertThrows(IllegalArgumentException.class, () -> compressor.configureTrained(
                    new TrainedCompressor("zstd", new byte[] {1, 2, 3}, new byte[0])));
            assertArrayEquals(samples[0], compressor.decompress(frame));
            assertArrayEquals(samples[1], compressor.decompress(compressor.compress(samples[1])));
        }
    }

    @Test
    void legacyTrainingCannotSilentlyDiscardDictionaryData() {
        TrainOptions options = new TrainOptions();
        options.dictionaryTraining = true;
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.train("zstd", samples, options));
        options.formatVersion = 24;
        assertThrows(IllegalStateException.class, () -> OpenZLCompressor.trainCandidates("zstd", samples, options));
    }

    @Test
    void candidateArraysAreDefensivelyCopied() {
        byte[] serialized = dictionaryCandidate.serializedCompressor();
        byte[] bundle = dictionaryCandidate.dictionaryBundle();
        TrainedCompressor copy = new TrainedCompressor("zstd", serialized, bundle);
        Arrays.fill(serialized, (byte) 0);
        Arrays.fill(bundle, (byte) 0);
        Arrays.fill(copy.serializedCompressor(), (byte) 0);
        Arrays.fill(copy.dictionaryBundle(), (byte) 0);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureTrained(copy);
            assertArrayEquals(samples[0], compressor.decompress(compressor.compress(samples[0])));
        }
    }

    @Test
    void dictionaryCandidatesCanBeSharedAcrossIndependentThreads() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int seed = worker;
                jobs.add(workers.submit(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < 50; i++) {
                        byte[] payload = Arrays.copyOf(samples[i % samples.length], 1024 + i);
                        for (int j = 300; j < payload.length; j++) payload[j] = (byte) random.nextInt(256);
                        try (OpenZLCompressor writer = new OpenZLCompressor(); OpenZLCompressor reader = new OpenZLCompressor()) {
                            writer.configureTrained(dictionaryCandidate);
                            reader.setDictionaryBundle(dictionaryCandidate.dictionaryBundle());
                            byte[] frame = writer.compress(payload);
                            writer.reset();
                            reader.reset();
                            assertArrayEquals(payload, reader.decompress(frame));
                        }
                    }
                }));
            }
            for (var job : jobs) job.get();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void trainingHonorsTargetFormatAndCandidateLimit(@TempDir Path directory) throws Exception {
        TrainOptions options = new TrainOptions();
        options.formatVersion = 24;
        options.maxNumCandidates = 1;
        options.paretoFrontier = true;
        for (int i = 0; i < 4; i++) Files.write(directory.resolve("sample-" + i), samples[i]);
        byte[][] graphs = OpenZLCompressor.trainFromDirectory("serial", directory.toString(), options);
        assertEquals(1, graphs.length);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            byte[] legacyFrame = OpenZLCompressor.compressWithSerializedNative("serial", graphs[0], samples[0]);
            assertEquals(24, compressor.inspect(legacyFrame).formatVersion(), "Legacy helpers must retain the trained format too");
            compressor.configureTrained(new TrainedCompressor("serial", graphs[0], new byte[0]));
            assertEquals(24, compressor.getFormatVersion());
            byte[] frame = compressor.compress(samples[0]);
            assertEquals(24, compressor.inspect(frame).formatVersion());
            assertArrayEquals(samples[0], compressor.decompress(frame));
        }
    }

    @Test
    void rejectsInvalidTrainingSettingsAndEmptyInputs(@TempDir Path directory) {
        TrainOptions options = new TrainOptions();
        options.maxNumCandidates = -1;
        assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.train("serial", samples, options));
        assertThrows(IllegalArgumentException.class,
                () -> OpenZLCompressor.trainCandidates("serial", new byte[0][], null));
        assertThrows(IllegalArgumentException.class,
                () -> OpenZLCompressor.trainCandidatesFromDirectory("serial", directory.toString(), null));
    }

    @Test
    void standaloneTransformerHandlesSparseDenseAndExtremeNumericStreams() {
        Random random = new Random(3001);
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.TRANSFORMER_NUMERIC)) {
            for (int pattern = 0; pattern < 4; pattern++) {
                long[] values = new long[8192];
                for (int i = 0; i < values.length; i++) {
                    switch (pattern) {
                        case 0: values[i] = i % 97 == 0 ? -i : 0; break;
                        case 1: values[i] = random.nextLong(); break;
                        case 2: values[i] = i % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE; break;
                        default: values[i] = i;
                    }
                }
                assertArrayEquals(values, compressor.decompressLongs(compressor.compressLongs(values)));
            }
            int[] bits = {0, 0x80000000, 0x7f800000, 0xff800000, 0x7fc00001, 0x7fa12345, 0x00000001, 0x7f7fffff};
            float[] floats = new float[8192];
            for (int i = 0; i < floats.length; i++) floats[i] = Float.intBitsToFloat(bits[i % bits.length]);
            float[] restored = compressor.decompressFloats(compressor.compressFloats(floats));
            for (int i = 0; i < floats.length; i++) {
                assertEquals(Float.floatToRawIntBits(floats[i]), Float.floatToRawIntBits(restored[i]));
            }
        }
    }

    @Test
    void bruteForceGraphCanBeSerializedAndReloaded() {
        byte[] graph;
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertThrows(IllegalArgumentException.class, compressor::configureBruteForce);
            compressor.configureBruteForce(OpenZLGraph.ZSTD, OpenZLGraph.HUFFMAN, OpenZLGraph.STORE);
            graph = compressor.serializeCompressor();
            assertArrayEquals(samples[0], compressor.decompress(compressor.compress(samples[0])));
        }
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureTrained(new TrainedCompressor("serial", graph, new byte[0]));
            assertArrayEquals(samples[1], compressor.decompress(compressor.compress(samples[1])));
        }
    }
}
