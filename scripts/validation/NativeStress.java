import io.github.hybledav.*;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;

/** Deterministic black-box validation of the real JNI library, outside Maven. */
public class NativeStress {
    private static final byte SENTINEL = 0x5a;
    private static final int GUARD = 32;
    private static final int[] BOUNDARIES = {0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33,
            63, 64, 65, 127, 128, 129, 255, 256, 257, 1023, 1024, 1025, 65535, 65536, 65537};

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] payload(Random random, int iteration) {
        int size = iteration < BOUNDARIES.length ? BOUNDARIES[iteration] : random.nextInt(65537);
        byte[] data = new byte[size];
        random.nextBytes(data);
        switch (iteration % 4) {
            case 0: Arrays.fill(data, (byte) iteration); break;
            case 1: for (int i = 0; i < size; i++) data[i] = (byte) (i % 17); break;
            case 2: for (int i = 0; i < size; i++) if (i % 41 != 0) data[i] = 0; break;
            default: break;
        }
        return data;
    }

    private static byte[] guarded(int capacity) {
        byte[] bytes = new byte[capacity + 2 * GUARD];
        Arrays.fill(bytes, SENTINEL);
        return bytes;
    }

    private static ByteBuffer direct(int capacity) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(capacity + 2 * GUARD);
        for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, SENTINEL);
        bytes.position(GUARD).limit(GUARD + capacity);
        return bytes;
    }

    private static void guards(byte[] bytes) {
        for (int i = 0; i < GUARD; i++) {
            check(bytes[i] == SENTINEL && bytes[bytes.length - 1 - i] == SENTINEL, "Array guard overwritten");
        }
    }

    private static void guards(ByteBuffer bytes) {
        ByteBuffer all = bytes.duplicate().clear();
        for (int i = 0; i < GUARD; i++) {
            check(all.get(i) == SENTINEL && all.get(all.capacity() - 1 - i) == SENTINEL, "Direct guard overwritten");
        }
    }

    private static void roundTrips(int count, long seed) {
        Random random = new Random(seed);
        OpenZLGraph[] graphs = {OpenZLGraph.ZSTD, OpenZLGraph.GENERIC, OpenZLGraph.STORE,
                OpenZLGraph.ENTROPY, OpenZLGraph.FSE, OpenZLGraph.HUFFMAN};
        for (int iteration = 0; iteration < count; iteration++) {
            byte[] input = payload(random, iteration);
            int bound = Math.toIntExact(OpenZLCompressor.maxCompressedSize(input.length));
            try (OpenZLCompressor compressor = new OpenZLCompressor(graphs[iteration % graphs.length])) {
                int version = iteration % 2 == 0 ? 24 : 27;
                compressor.setFormatVersion(version);
                compressor.setCompressionLevel(OpenZLCompressionLevel.fromLevel(1 + iteration % 7));
                byte[] source = guarded(input.length);
                System.arraycopy(input, 0, source, GUARD, input.length);
                byte[] encoded = guarded(bound);
                int size = compressor.compress(source, GUARD, input.length, encoded, GUARD, bound);
                guards(source); guards(encoded);
                check(Arrays.equals(input, Arrays.copyOfRange(source, GUARD, GUARD + input.length)), "Input modified");
                byte[] frame = Arrays.copyOfRange(encoded, GUARD, GUARD + size);
                byte[] restored = guarded(input.length);
                int written = compressor.decompress(encoded, GUARD, size, restored, GUARD, input.length);
                check(written == input.length, "Wrong decoded length");
                guards(restored);
                check(Arrays.equals(input, Arrays.copyOfRange(restored, GUARD, GUARD + written)), "Array round trip failed");
                check(compressor.inspect(frame).formatVersion() == version, "Wrong frame format");

                ByteBuffer src = direct(input.length);
                src.put(input).position(GUARD);
                ByteBuffer dst = direct(bound);
                int directSize = compressor.compress(src.asReadOnlyBuffer(), dst);
                guards(src); guards(dst);
                check(directSize == size, "Array/direct encoded sizes differ");
                byte[] directFrame = new byte[directSize];
                dst.position(GUARD).limit(GUARD + directSize);
                dst.duplicate().get(directFrame);
                check(Arrays.equals(frame, directFrame), "Array/direct frames differ");
                ByteBuffer decoded = direct(input.length);
                compressor.decompress(dst.asReadOnlyBuffer(), decoded);
                guards(dst); guards(decoded);
                byte[] decodedBytes = new byte[input.length];
                decoded.position(GUARD).get(decodedBytes);
                check(Arrays.equals(input, decodedBytes), "Direct round trip failed");
                compressor.reset();
                check(Arrays.equals(input, compressor.decompress(frame)), "Reset changed decoding");
                if (iteration % 37 == 0) {
                    byte[] saved = compressor.serializeCompressor();
                    compressor.configureTrained(new TrainedCompressor("serial", saved, new byte[0]));
                    check(Arrays.equals(input, compressor.decompress(compressor.compress(input))), "Graph reload failed");
                }
            }
        }
    }

    private static void numeric(int count, long seed) {
        Random random = new Random(seed);
        for (int iteration = 0; iteration < count; iteration++) {
            int length = 1 + random.nextInt(4096);
            OpenZLGraph graph = (iteration / 4) % 2 == 0 ? OpenZLGraph.NUMERIC : OpenZLGraph.TRANSFORMER_NUMERIC;
            try (OpenZLCompressor compressor = new OpenZLCompressor(graph)) {
                compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_7);
                if (graph == OpenZLGraph.NUMERIC && (iteration / 8) % 2 == 0) compressor.setFormatVersion(24);
                if (iteration % 4 == 0) {
                    int[] input = new int[length];
                    for (int i = 0; i < length; i++) input[i] = i % 41 == 0 ? random.nextInt() : 0;
                    check(Arrays.equals(input, compressor.decompressInts(compressor.compressInts(input))), "Int round trip failed");
                } else if (iteration % 4 == 1) {
                    long[] input = new long[length];
                    for (int i = 0; i < length; i++) input[i] = i % 2 == 0 ? Long.MIN_VALUE + i : random.nextLong();
                    check(Arrays.equals(input, compressor.decompressLongs(compressor.compressLongs(input))), "Long round trip failed");
                } else if (iteration % 4 == 2) {
                    float[] input = new float[length];
                    for (int i = 0; i < length; i++) input[i] = Float.intBitsToFloat(random.nextInt());
                    float[] output = compressor.decompressFloats(compressor.compressFloats(input));
                    check(input.length == output.length, "Float length mismatch");
                    for (int i = 0; i < length; i++) check(Float.floatToRawIntBits(input[i]) == Float.floatToRawIntBits(output[i]), "Float bits changed");
                } else {
                    double[] input = new double[length];
                    for (int i = 0; i < length; i++) input[i] = Double.longBitsToDouble(random.nextLong());
                    double[] output = compressor.decompressDoubles(compressor.compressDoubles(input));
                    check(input.length == output.length, "Double length mismatch");
                    for (int i = 0; i < length; i++) check(Double.doubleToRawLongBits(input[i]) == Double.doubleToRawLongBits(output[i]), "Double bits changed");
                }
            }
        }
    }

    private static void malformed(int count, long seed) {
        Random random = new Random(seed);
        byte[] input = payload(random, 100);
        try (OpenZLCompressor decoder = new OpenZLCompressor()) {
            byte[] valid = decoder.compress(input);
            ArrayList<byte[]> corpus = new ArrayList<>();
            for (OpenZLGraph graph : new OpenZLGraph[] {OpenZLGraph.ZSTD, OpenZLGraph.GENERIC,
                    OpenZLGraph.STORE, OpenZLGraph.HUFFMAN, OpenZLGraph.FSE, OpenZLGraph.ENTROPY}) {
                try (OpenZLCompressor encoder = new OpenZLCompressor(graph)) {
                    for (int pattern = 100; pattern < 104; pattern++) corpus.add(encoder.compress(payload(random, pattern)));
                }
            }
            try (OpenZLCompressor encoder = new OpenZLCompressor(OpenZLGraph.TRANSFORMER_NUMERIC)) {
                long[] sparse = new long[4096];
                for (int i = 0; i < sparse.length; i += 41) sparse[i] = i;
                corpus.add(encoder.compressLongs(sparse));
            }
            try (OpenZLCompressor encoder = new OpenZLCompressor()) {
                encoder.configureProfile(OpenZLProfile.LZ);
                corpus.add(encoder.compress(input));
            }
            for (int iteration = 0; iteration < count; iteration++) {
                byte[] original = corpus.get(iteration % corpus.size());
                byte[] mutated;
                if (iteration % 3 == 0) mutated = Arrays.copyOf(original, random.nextInt(original.length));
                else {
                    mutated = original.clone();
                    for (int bit = 0; bit < 1 + iteration % 8; bit++) mutated[random.nextInt(mutated.length)] ^= (byte) (1 << random.nextInt(8));
                }
                ByteBuffer src = direct(mutated.length);
                src.put(mutated).position(GUARD);
                ByteBuffer output = direct(131072);
                try { decoder.decompress(src, output); } catch (IllegalStateException | IllegalArgumentException expected) { }
                guards(src); guards(output);
                try { decoder.inspect(mutated); } catch (IllegalStateException | IllegalArgumentException expected) { }
                // Never let mutated size fields request an unbounded Java/native
                // allocation in the typed convenience API.
                try {
                    long size = decoder.getDecompressedSize(mutated);
                    if (size >= 0 && size <= 131072) decoder.decompressLongs(mutated);
                } catch (IllegalStateException | IllegalArgumentException expected) { }
                check(Arrays.equals(input, decoder.decompress(valid)), "Failure poisoned the decoder");
                decoder.reset();
            }
        }
    }

    private static TrainedCompressor dictionary() {
        byte[][] samples = new byte[128][];
        for (int i = 0; i < samples.length; i++) samples[i] = ("{\"account\":\"customer-" + i
                + "\",\"status\":\"accepted\",\"currency\":\"EUR\",\"description\":\"Native validation dictionary sample payload\"}")
                .getBytes(StandardCharsets.UTF_8);
        TrainOptions options = new TrainOptions();
        options.dictionaryTraining = true;
        TrainedCompressor result = OpenZLCompressor.trainCandidates("zstd", samples, options)[0];
        check(result.hasDictionaryBundle(), "Training lost dictionaries");
        return result;
    }

    private static void trained(int count, long seed) throws Exception {
        TrainedCompressor candidate = dictionary();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] jobs = new Future<?>[8];
            for (int worker = 0; worker < jobs.length; worker++) {
                final int index = worker;
                jobs[worker] = pool.submit(() -> {
                    Random random = new Random(seed + index);
                    for (int iteration = index; iteration < count; iteration += jobs.length) {
                        byte[] input = payload(random, iteration);
                        try (OpenZLCompressor writer = new OpenZLCompressor(); OpenZLCompressor reader = new OpenZLCompressor()) {
                            writer.configureTrained(candidate);
                            reader.setDictionaryBundle(candidate.dictionaryBundle());
                            byte[] frame = writer.compress(input);
                            writer.reset(); reader.reset();
                            check(Arrays.equals(input, reader.decompress(frame)), "Concurrent dictionary round trip failed");
                            byte[] badGraph = candidate.serializedCompressor();
                            badGraph[random.nextInt(badGraph.length)] ^= 0x55;
                            try { writer.configureTrained(new TrainedCompressor("zstd", badGraph, candidate.dictionaryBundle())); }
                            catch (IllegalArgumentException expected) { }
                            byte[] badBundle = candidate.dictionaryBundle();
                            if (iteration % 2 == 0) badBundle = Arrays.copyOf(badBundle, iteration % badBundle.length);
                            else badBundle[random.nextInt(badBundle.length)] ^= 0x55;
                            try { reader.setDictionaryBundle(badBundle); } catch (IllegalArgumentException expected) { }
                            writer.configureTrained(candidate);
                            reader.setDictionaryBundle(candidate.dictionaryBundle());
                            check(Arrays.equals(input, reader.decompress(writer.compress(input))), "Candidate failure poisoned a context");
                        }
                    }
                });
            }
            for (Future<?> job : jobs) job.get();
        } finally { pool.shutdownNow(); }
    }

    private static void boundaries() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            ByteBuffer input = ByteBuffer.allocateDirect(32).putLong(123).flip();
            ByteBuffer output = ByteBuffer.allocateDirect(256).asReadOnlyBuffer();
            boolean rejected = false;
            try { compressor.compress(input, output); } catch (ReadOnlyBufferException expected) { rejected = true; }
            check(rejected && input.position() == 0 && output.position() == 0, "Read-only destination accepted");
            byte[] frame = compressor.compress(new byte[] {1, 2, 3});
            ByteBuffer src = ByteBuffer.allocateDirect(frame.length).put(frame).flip();
            rejected = false;
            try { compressor.decompress(src, output); } catch (ReadOnlyBufferException expected) { rejected = true; }
            check(rejected && src.position() == 0, "Read-only decompression destination accepted");
            byte[] shared = new byte[256];
            rejected = false;
            try { compressor.compress(shared, 0, 64, shared, 32, 200); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Overlapping array regions accepted");
            ByteBuffer storage = ByteBuffer.allocateDirect(256);
            rejected = false;
            try { compressor.compress(storage.duplicate().limit(64), storage.duplicate().position(32)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Overlapping direct views accepted");
            ByteBuffer source = direct(8);
            source.putLong(123).position(GUARD);
            ByteBuffer tiny = direct(1);
            rejected = false;
            try { compressor.compress(source, tiny); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected && source.position() == GUARD && tiny.position() == GUARD, "Failed compression consumed buffers");
            guards(tiny);
            check(Arrays.equals(new byte[] {1, 2, 3}, compressor.decompress(frame)), "Capacity failure poisoned context");
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int count = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        System.out.println("VALIDATION_START mode=" + mode + " iterations=" + count + " seed=" + seed);
        switch (mode) {
            case "roundtrip": roundTrips(count, seed); break;
            case "numeric": numeric(count, seed); break;
            case "malformed": malformed(count, seed); break;
            case "trained": trained(count, seed); break;
            case "boundaries": boundaries(); break;
            default: throw new IllegalArgumentException("Unknown validation mode: " + mode);
        }
        System.out.println("VALIDATION_OK mode=" + mode + " iterations=" + count + " seed=" + seed);
    }
}
