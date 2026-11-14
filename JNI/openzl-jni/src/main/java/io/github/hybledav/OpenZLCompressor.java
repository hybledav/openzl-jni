package io.github.hybledav;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class OpenZLCompressor implements AutoCloseable {
    private static final class CleanerHolder {
        private static final Cleaner INSTANCE = Cleaner.create();
    }

    private static Cleaner cleaner() {
        return CleanerHolder.INSTANCE;
    }

    private final OpenZLGraph graph;
    private long nativeHandle;
    private DictionaryBundleProvider dictionaryBundleProvider;
    private byte[] resolvedBundleId;
    private boolean resolvingDictionary;
    private final Cleaner.Cleanable cleanable;
    private final NativeHandleCleaner nativeHandleCleaner;
    private static final int META_ORIGINAL_SIZE = 0;
    private static final int META_COMPRESSED_SIZE = 1;
    private static final int META_OUTPUT_TYPE = 2;
    private static final int META_GRAPH_ID = 3;
    private static final int META_ELEMENT_COUNT = 4;
    private static final int META_FORMAT_VERSION = 5;
    private static final int META_LENGTH = 6;
    private static final int CPARAM_COMPRESSION_LEVEL = 2;
    private static final int CPARAM_FORMAT_VERSION = 4;

    public OpenZLCompressor() {
        this(OpenZLGraph.ZSTD);
    }

    public OpenZLCompressor(OpenZLGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        OpenZLNative.load();
        nativeHandle = nativeCreate(graph.id());
        if (nativeHandle == 0) {
            throw new IllegalStateException("Unable to initialise OpenZL native compressor");
        }
        nativeHandleCleaner = new NativeHandleCleaner(nativeHandle);
        cleanable = cleaner().register(this, nativeHandleCleaner);
    }

    public OpenZLGraph graph() {
        return graph;
    }

    private void ensureOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Compressor already closed");
        }
    }

    /** Compress typed streams in order using the configured graph (GENERIC accepts mixed streams).
     * Input factories snapshot arrays and buffers; outputs preserve type and stream order.
     */
    public byte[] compressInputs(TypedInput... inputs) {
        ensureOpen();
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.length == 0) throw new IllegalArgumentException("At least one input is required");
        TypedInput[] snapshot = inputs.clone();
        for (TypedInput input : snapshot) Objects.requireNonNull(input, "input");
        return compressInputsNative(snapshot);
    }
    public TypedOutput[] decompressOutputs(byte[] frame) {
        ensureOpen();
        Objects.requireNonNull(frame, "frame");
        resolveDictionary(frame, 0, frame.length);
        return decompressOutputsNative(frame);
    }
    /** Copies remaining frame bytes; accepts heap/direct/read-only buffers and preserves cursors. */
    public TypedOutput[] decompressOutputs(ByteBuffer frame) {
        return decompressOutputs(TypedInput.copy(frame));
    }
    /** Apply a complete options snapshot. A rejected update leaves all prior settings intact. */
    public void setCompressionOptions(CompressionOptions options) {
        ensureOpen();
        setOptionsNative(false, Objects.requireNonNull(options, "options").values);
    }
    public void setDecompressionOptions(DecompressionOptions options) {
        ensureOpen();
        setOptionsNative(true, Objects.requireNonNull(options, "options").values);
    }
    /** Select the LZ graph with local controls; global context options remain in effect. */
    public void configureLz(LzOptions options) {
        ensureOpen();
        configureLzNative(Objects.requireNonNull(options, "options").values);
    }
    /** Snapshot warnings from the most recent compression or decompression operation.
     * Inspect before reset or another operation invalidates upstream warning storage.
     */
    public OpenZLWarning[] getCompressionWarnings() {
        ensureOpen();
        return getWarningsNative(false);
    }
    /** Snapshot warnings from the most recent decompression. Empty after reset or on a new instance. */
    public OpenZLWarning[] getDecompressionWarnings() {
        ensureOpen();
        return getWarningsNative(true);
    }
    private native byte[] compressInputsNative(TypedInput[] inputs);
    private native TypedOutput[] decompressOutputsNative(byte[] frame);
    private native void setOptionsNative(boolean decompression, int[] values);
    private native void configureLzNative(int[] values);
    private native OpenZLWarning[] getWarningsNative(boolean decompression);

    private native long nativeCreate(int graphId);
    public native void setParameter(int param, int value);
    public native int getParameter(int param);
    public native String serialize();
    public native String serializeToJson();
    public static long maxCompressedSize(int inputSize) {
        if (inputSize < 0) {
            throw new IllegalArgumentException("inputSize must be non-negative");
        }
        OpenZLNative.load();
        long bound = maxCompressedSizeNative(inputSize);
        if (bound < 0) {
            throw new IllegalStateException("Failed to query compression bound");
        }
        return bound;
    }
    public native byte[] compress(byte[] input);
    public byte[] decompress(byte[] input) {
        // Preserve the original byte-array API's null result after close.
        if (nativeHandle != 0) {
            Objects.requireNonNull(input, "input");
            resolveDictionary(input, 0, input.length);
        }
        return decompressNative(input);
    }
    private native byte[] decompressNative(byte[] input);
    private static native long maxCompressedSizeNative(int inputSize);
    private native int compressDirect(ByteBuffer src, int srcPos, int srcLen,
                                      ByteBuffer dst, int dstPos, int dstLen);
    private native int decompressDirect(ByteBuffer src, int srcPos, int srcLen,
                                        ByteBuffer dst, int dstPos, int dstLen);
    private native long getDecompressedSizeNative(byte[] input);
    private native long getDecompressedSizeDirect(ByteBuffer src, int srcPos, int srcLen);
    private native void resetNative();
    private native int compressIntoNative(byte[] src, int srcOffset, int srcLength,
                                          byte[] dst, int dstOffset, int dstLength);
    private native int decompressIntoNative(byte[] src, int srcOffset, int srcLength,
                                            byte[] dst, int dstOffset, int dstLength);
    private native void destroyCompressor();
    private native void configureSddlNative(byte[] compiledDescription);
    private native void configureProfileNative(String profileName, String[] keys, String[] values);

    public int compress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        if (dst.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = compressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Compression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    public ByteBuffer compress(ByteBuffer src, OpenZLBufferManager buffers) {
        ensureOpen();
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        long bound = maxCompressedSize(src.remaining());
        ByteBuffer dst = buffers.acquire(checkedCapacity(bound));
        try {
            int written = compress(src, dst);
            dst.limit(written);
            dst.position(0);
            return dst;
        } catch (RuntimeException | Error failure) {
            buffers.release(dst);
            throw failure;
        }
    }

    public int decompress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        if (dst.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        int srcPos = src.position();
        int dstPos = dst.position();
        if (dictionaryBundleProvider != null && src.remaining() != 0) {
            resolveDictionary(DictionarySupport.bundleId(src));
        }
        int written = decompressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    public ByteBuffer decompress(ByteBuffer src, OpenZLBufferManager buffers) {
        ensureOpen();
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        ByteBuffer srcView = src.duplicate();
        long expected = getDecompressedSize(srcView);
        ByteBuffer dst = buffers.acquire(checkedCapacity(expected));
        try {
            int written = decompress(srcView, dst);
            src.position(src.limit());
            dst.limit(written);
            dst.position(0);
            return dst;
        } catch (RuntimeException | Error failure) {
            buffers.release(dst);
            throw failure;
        }
    }

    private static void requireDirect(ByteBuffer buffer, String name) {
        if (buffer == null) {
            throw new NullPointerException(name + " buffer is null");
        }
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException(name + " buffer must be direct");
        }
    }

    public long getDecompressedSize(byte[] input) {
        Objects.requireNonNull(input, "input");
        long result = getDecompressedSizeNative(input);
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    public long getDecompressedSize(ByteBuffer input) {
        requireDirect(input, "input");
        long result = getDecompressedSizeDirect(input, input.position(), input.remaining());
        if (result < 0) {
            throw new IllegalStateException("Failed to query decompressed size");
        }
        return result;
    }

    public void reset() {
        resetNative();
    }

    public void configureSddl(byte[] compiledDescription) {
        ensureOpen();
        Objects.requireNonNull(compiledDescription, "compiledDescription");
        if (compiledDescription.length == 0) {
            throw new IllegalArgumentException("compiledDescription must not be empty");
        }
        configureSddlNative(compiledDescription);
    }

    public void configureProfile(OpenZLProfile profile) {
        configureProfile(profile, Map.of());
    }

    public void configureProfile(OpenZLProfile profile, Map<String, String> arguments) {
        ensureOpen();
        Objects.requireNonNull(profile, "profile");

        Map<String, String> args = arguments == null ? Map.of() : arguments;
        String[] keys = new String[args.size()];
        String[] values = new String[args.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "arguments key");
            String value = Objects.requireNonNull(entry.getValue(),
                    () -> "arguments value for key " + key);
            keys[index] = key;
            values[index] = value;
            index++;
        }
        configureProfileNative(profile.profileName(), keys, values);
    }

    public void setCompressionLevel(OpenZLCompressionLevel level) {
        ensureOpen();
        Objects.requireNonNull(level, "level");
        setParameter(CPARAM_COMPRESSION_LEVEL, level.level());
    }

    public OpenZLCompressionLevel getCompressionLevel() {
        ensureOpen();
        int value = getParameter(CPARAM_COMPRESSION_LEVEL);
        return OpenZLCompressionLevel.fromLevel(value);
    }

    /**
     * Sets the frame format used for subsequent compression. Use 24 for
     * readers running OpenZL 0.2.0, or 0 to restore the native default.
     *
     * @throws IllegalArgumentException if the native library rejects the version
     */
    public void setFormatVersion(int version) {
        ensureOpen();
        setParameter(CPARAM_FORMAT_VERSION, version);
    }

    /** Returns the frame format version used for compression. */
    public int getFormatVersion() {
        ensureOpen();
        return getParameter(CPARAM_FORMAT_VERSION);
    }

    public int compress(byte[] input, byte[] output) {
        Objects.requireNonNull(output, "output");
        return compress(input, 0, input.length, output, 0, output.length);
    }

    public int compress(byte[] input, int inputOffset, int inputLength,
            byte[] output, int outputOffset, int outputLength) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        checkRange(input.length, inputOffset, inputLength, "input");
        checkRange(output.length, outputOffset, outputLength, "output");
        rejectArrayOverlap(input, inputOffset, inputLength, output, outputOffset, outputLength);
        int written = compressIntoNative(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (written < 0) {
            throw new IllegalStateException("Compression failed");
        }
        return written;
    }

    public int decompress(byte[] input, byte[] output) {
        Objects.requireNonNull(output, "output");
        return decompress(input, 0, input.length, output, 0, output.length);
    }

    public int decompress(byte[] input, int inputOffset, int inputLength,
            byte[] output, int outputOffset, int outputLength) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        checkRange(input.length, inputOffset, inputLength, "input");
        checkRange(output.length, outputOffset, outputLength, "output");
        rejectArrayOverlap(input, inputOffset, inputLength, output, outputOffset, outputLength);
        resolveDictionary(input, inputOffset, inputLength);
        int written = decompressIntoNative(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        return written;
    }

    public byte[] compressInts(int[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressIntsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress int array");
        }
        return result;
    }

    public byte[] compressLongs(long[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressLongsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress long array");
        }
        return result;
    }

    public byte[] compressFloats(float[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressFloatsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress float array");
        }
        return result;
    }

    public byte[] compressDoubles(double[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            return new byte[0];
        }
        byte[] result = compressDoublesNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress double array");
        }
        return result;
    }

    public int[] decompressInts(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        resolveDictionary(compressed, 0, compressed.length);
        int[] result = decompressIntsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress int array");
        }
        return result;
    }

    public long[] decompressLongs(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        resolveDictionary(compressed, 0, compressed.length);
        long[] result = decompressLongsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress long array");
        }
        return result;
    }

    public float[] decompressFloats(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        resolveDictionary(compressed, 0, compressed.length);
        float[] result = decompressFloatsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress float array");
        }
        return result;
    }

    public double[] decompressDoubles(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        resolveDictionary(compressed, 0, compressed.length);
        double[] result = decompressDoublesNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress double array");
        }
        return result;
    }

    public OpenZLCompressionInfo inspect(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        if (compressed.length == 0) {
            throw new IllegalArgumentException("compressed must not be empty");
        }
        long[] meta = describeFrameNative(compressed);
        return toCompressionInfo(meta);
    }

    public OpenZLCompressionInfo inspect(ByteBuffer compressed) {
        ensureOpen();
        requireDirect(compressed, "compressed");
        ByteBuffer view = compressed.duplicate();
        long[] meta = describeFrameDirectNative(view, view.position(), view.remaining());
        return toCompressionInfo(meta);
    }

    private OpenZLCompressionInfo toCompressionInfo(long[] meta) {
        if (meta == null || meta.length < META_LENGTH) {
            throw new IllegalStateException("Unable to inspect compressed frame");
        }
        OpenZLCompressionInfo.DataFlavor flavor =
                OpenZLCompressionInfo.DataFlavor.fromNative((int) meta[META_OUTPUT_TYPE]);
        OpenZLGraph inferredGraph = OpenZLGraph.fromNativeId((int) meta[META_GRAPH_ID]);
        long elementCount = meta[META_ELEMENT_COUNT];
        int formatVersion = (int) meta[META_FORMAT_VERSION];
        return new OpenZLCompressionInfo(
                meta[META_ORIGINAL_SIZE],
                meta[META_COMPRESSED_SIZE],
                inferredGraph,
                flavor,
                elementCount,
                formatVersion);
    }

    private native byte[] compressIntsNative(int[] data);
    private native byte[] compressLongsNative(long[] data);
    private native byte[] compressFloatsNative(float[] data);
    private native byte[] compressDoublesNative(double[] data);
    private native int[] decompressIntsNative(byte[] data);
    private native long[] decompressLongsNative(byte[] data);
    private native float[] decompressFloatsNative(byte[] data);
    private native double[] decompressDoublesNative(byte[] data);
    private native long[] describeFrameNative(byte[] data);
    private native long[] describeFrameDirectNative(ByteBuffer data, int position, int length);

    private static void checkRange(int arrayLength, int offset, int length, String name) {
        if (offset < 0 || length < 0 || offset > arrayLength - length) {
            throw new IndexOutOfBoundsException(
                    String.format(Locale.ROOT, "%s range [%d, %d) out of bounds", name, offset, offset + length));
        }
    }

    private static void rejectArrayOverlap(byte[] input, int inputOffset, int inputLength,
            byte[] output, int outputOffset, int outputLength) {
        if (input == output && inputLength > 0 && outputLength > 0
                && inputOffset < outputOffset + outputLength && outputOffset < inputOffset + inputLength) {
            throw new IllegalArgumentException("Input and output ranges must not overlap");
        }
    }

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalStateException("Native reported negative capacity: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    public enum OpenZLDataArena {
        HEAP,
        STACK
    }

    public void setDataArena(OpenZLDataArena arena) {
        ensureOpen();
        Objects.requireNonNull(arena, "arena");
        setDataArenaNative(arena.ordinal());
    }

    private native void setDataArenaNative(int arenaOrdinal);
    private static native String[] listProfilesNative();

    public static String[] listProfiles() {
        OpenZLNative.load();
        String[] arr = listProfilesNative();
        return arr == null ? new String[0] : arr;
    }

    private static native byte[][] trainNative(String profileName, byte[][] inputs,
            int maxTimeSecs, int threads, int numSamples, boolean pareto);

    // Helpers for tests: compress using a profile (untrained) and using a serialized (trained) compressor
    public static native byte[] compressWithProfileNative(String profileName, byte[] input);
    public static native byte[] compressWithSerializedNative(String profileName, byte[] serializedCompressor, byte[] input);

    /** Train self-contained graphs. Dictionary training requires {@link #trainCandidates}. */
    public static byte[][] train(String profileName, byte[][] inputs, TrainOptions opts) {
        TrainOptions options = opts == null ? new TrainOptions() : opts;
        options.requireSelfContained();
        return java.util.Arrays.stream(trainCandidates(profileName, inputs, options))
                .map(TrainedCompressor::serializedCompressor).toArray(byte[][]::new);
    }

    private static native byte[][] trainFromDirectoryNative(String profileName, String dirPath,
            int maxTimeSecs, int threads, int numSamples, boolean pareto);

    public static byte[][] trainFromDirectory(String profileName, String dirPath, TrainOptions opts) {
        TrainOptions options = opts == null ? new TrainOptions() : opts;
        options.requireSelfContained();
        return java.util.Arrays.stream(trainCandidatesFromDirectory(profileName, dirPath, options))
                .map(TrainedCompressor::serializedCompressor).toArray(byte[][]::new);
    }

    /** Train graphs while retaining all dictionary data needed to reload them. */
    public static TrainedCompressor[] trainCandidates(String profileName, byte[][] inputs, TrainOptions opts) {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(inputs, "inputs");
        TrainOptions options = opts == null ? new TrainOptions() : opts;
        options.validate();
        if (inputs.length == 0) throw new IllegalArgumentException("inputs must not be empty");
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("openzl-train");
            try {
                for (int i = 0; i < inputs.length; ++i) {
                    java.nio.file.Files.write(tmp.resolve(Integer.toString(i)),
                            inputs[i] == null ? new byte[0] : inputs[i]);
                }
                return trainCandidatesFromDirectory(profileName, tmp.toString(), options);
            } finally {
                try (var files = java.nio.file.Files.newDirectoryStream(tmp)) {
                    for (var file : files) java.nio.file.Files.deleteIfExists(file);
                }
                java.nio.file.Files.deleteIfExists(tmp);
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to prepare training inputs", ex);
        }
    }

    public static TrainedCompressor[] trainCandidatesFromDirectory(String profileName, String dirPath, TrainOptions opts) {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(dirPath, "dirPath");
        TrainOptions options = opts == null ? new TrainOptions() : opts;
        options.validate();
        OpenZLNative.load();
        return trainCandidatesFromDirectoryNative(profileName, dirPath,
                options.maxTimeSecs, options.threads, options.numSamples, options.paretoFrontier,
                options.maxNumCandidates, options.formatVersion, options.dictionaryTraining);
    }

    private static native TrainedCompressor[] trainCandidatesFromDirectoryNative(String profileName, String dirPath,
            int maxTimeSecs, int threads, int numSamples, boolean pareto,
            int maxNumCandidates, int formatVersion, boolean dictionaryTraining);

    /** Replace the current graph with a saved candidate, including its optional dictionaries. */
    public void configureTrained(TrainedCompressor candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate");
        configureTrainedNative(candidate.profileName(), candidate.serializedCompressor(), candidate.dictionaryBundle());
        resolvedBundleId = null;
    }

    private native void configureTrainedNative(String profileName, byte[] serialized, byte[] dictionaryBundle);

    /** Replace the decoding dictionary bundle. An empty array clears it; reset() retains it. */
    public void setDictionaryBundle(byte[] bundle) {
        ensureOpen();
        setDictionaryBundleNative(Objects.requireNonNull(bundle, "bundle"));
        resolvedBundleId = null;
    }

    private native void setDictionaryBundleNative(byte[] bundle);

    /**
     * Installs a lazy bundle provider for decoding. Null disables callbacks while
     * retaining the most recently loaded bundle. Only the most recent ID is
     * cached per compressor; reset retains it and close releases the provider.
     */
    public void setDictionaryBundleProvider(DictionaryBundleProvider provider) {
        ensureOpen();
        dictionaryBundleProvider = provider;
        resolvedBundleId = null;
    }

    /** Returns the frame's 32-byte bundle ID, or an empty array when absent. */
    public static byte[] getDictionaryBundleId(byte[] frame) {
        return DictionarySupport.bundleId(frame, 0, frame.length);
    }

    /** Reads a direct buffer's remaining frame without changing its position. */
    public static byte[] getDictionaryBundleId(ByteBuffer frame) {
        return DictionarySupport.bundleId(frame);
    }

    private void resolveDictionary(byte[] frame, int offset, int length) {
        if (dictionaryBundleProvider != null && length != 0) {
            resolveDictionary(DictionarySupport.bundleId(frame, offset, length));
        }
    }

    private void resolveDictionary(byte[] id) {
        ensureOpen();
        if (id.length == 0 || java.util.Arrays.equals(id, resolvedBundleId)) return;
        if (resolvingDictionary) throw new IllegalStateException("Recursive dictionary lookup");
        DictionaryBundleProvider provider = dictionaryBundleProvider;
        resolvingDictionary = true;
        try {
            byte[] bundle = DictionarySupport.resolve(provider, id);
            ensureOpen();
            if (provider != dictionaryBundleProvider) {
                throw new IllegalStateException("Dictionary provider changed during lookup");
            }
            setResolvedDictionaryBundleNative(bundle, id);
            resolvedBundleId = id;
        } finally {
            resolvingDictionary = false;
        }
    }

    private native void setResolvedDictionaryBundleNative(byte[] bundle, byte[] expectedId);

    /**
     * Enables the native tryGraph codec-output cache with an entry-payload budget.
     * Zero disables it (the default). The budget is not a limit on all native
     * memory. Only eligible deterministic single-input standard codecs are cached;
     * dictionary-backed/custom codecs are excluded. Changing the budget drops
     * existing entries. The setting survives reset and graph changes; close
     * releases the cache before the context can be reused by another instance.
     */
    public void setTryGraphCacheBudget(long bytes) {
        ensureOpen();
        if (bytes < 0) throw new IllegalArgumentException("Cache budget must not be negative");
        setTryGraphCacheBudgetNative(bytes);
    }

    /** Returns the configured entry-payload budget, or zero when disabled. */
    public long getTryGraphCacheBudget() {
        ensureOpen();
        return getTryGraphCacheBudgetNative();
    }

    private native void setTryGraphCacheBudgetNative(long bytes);
    private native long getTryGraphCacheBudgetNative();

    /** Select the smallest encoding among these graphs for each input. */
    public void configureBruteForce(OpenZLGraph... candidates) {
        ensureOpen();
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.length == 0) throw new IllegalArgumentException("At least one candidate graph is required");
        int[] ids = new int[candidates.length];
        for (int i = 0; i < ids.length; i++) ids[i] = Objects.requireNonNull(candidates[i], "candidate").id();
        configureBruteForceNative(ids);
    }

    private native void configureBruteForceNative(int[] candidateIds);

    /** Binary serialization suitable for saving and reloading a graph. Save dictionary bundles separately. */
    public byte[] serializeCompressor() {
        ensureOpen();
        return serializeCompressorNative();
    }

    private native byte[] serializeCompressorNative();

    @Override
    public void close() {
        cleanable.clean();
        nativeHandle = 0;
        dictionaryBundleProvider = null;
        resolvedBundleId = null;
    }

    private static synchronized void destroyCompressorHandle(long handle) {
        if (handle == 0) {
            return;
        }
        destroyCompressorHandleNative(handle);
    }

    private static native void destroyCompressorHandleNative(long handle);

    private static final class NativeHandleCleaner implements Runnable {
        private long handle;

        private NativeHandleCleaner(long handle) {
            this.handle = handle;
        }

        @Override
        public synchronized void run() {
            if (handle == 0) {
                return;
            }
            destroyCompressorHandle(handle);
            handle = 0;
        }
    }
}
