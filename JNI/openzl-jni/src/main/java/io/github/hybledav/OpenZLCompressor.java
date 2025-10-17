package io.github.hybledav;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class OpenZLCompressor implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final OpenZLGraph graph;
    private long nativeHandle;
    private final Cleaner.Cleanable cleanable;
    private static final int META_ORIGINAL_SIZE = 0;
    private static final int META_COMPRESSED_SIZE = 1;
    private static final int META_OUTPUT_TYPE = 2;
    private static final int META_GRAPH_ID = 3;
    private static final int META_ELEMENT_COUNT = 4;
    private static final int META_FORMAT_VERSION = 5;
    private static final int META_LENGTH = 6;

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
        cleanable = CLEANER.register(this, new Releaser(this));
    }

    public OpenZLGraph graph() {
        return graph;
    }

    private void ensureOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("Compressor already closed");
        }
    }

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
    public native byte[] decompress(byte[] input);
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
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        long bound = maxCompressedSize(src.remaining());
        ByteBuffer dst = buffers.acquire(checkedCapacity(bound));
        int written = compress(src, dst);
        dst.limit(written);
        dst.position(0);
        return dst;
    }

    public int decompress(ByteBuffer src, ByteBuffer dst) {
        requireDirect(src, "src");
        requireDirect(dst, "dst");
        int srcPos = src.position();
        int dstPos = dst.position();
        int written = decompressDirect(src, srcPos, src.remaining(), dst, dstPos, dst.remaining());
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        src.position(src.limit());
        dst.position(dstPos + written);
        return written;
    }

    public ByteBuffer decompress(ByteBuffer src, OpenZLBufferManager buffers) {
        Objects.requireNonNull(buffers, "buffers");
        requireDirect(src, "src");
        ByteBuffer srcView = src.duplicate();
        long expected = getDecompressedSize(srcView);
        ByteBuffer dst = buffers.acquire(checkedCapacity(expected));
        int written = decompress(srcView, dst);
        src.position(src.limit());
        dst.limit(written);
        dst.position(0);
        return dst;
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
        int written = decompressIntoNative(input, inputOffset, inputLength, output, outputOffset, outputLength);
        if (written < 0) {
            throw new IllegalStateException("Decompression failed");
        }
        return written;
    }

    public byte[] compressInts(int[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        byte[] result = compressIntsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress int array");
        }
        return result;
    }

    public byte[] compressLongs(long[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        byte[] result = compressLongsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress long array");
        }
        return result;
    }

    public byte[] compressFloats(float[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        byte[] result = compressFloatsNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress float array");
        }
        return result;
    }

    public byte[] compressDoubles(double[] data) {
        ensureOpen();
        Objects.requireNonNull(data, "data");
        byte[] result = compressDoublesNative(data);
        if (result == null) {
            throw new IllegalStateException("Failed to compress double array");
        }
        return result;
    }

    public int[] decompressInts(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        int[] result = decompressIntsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress int array");
        }
        return result;
    }

    public long[] decompressLongs(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        long[] result = decompressLongsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress long array");
        }
        return result;
    }

    public float[] decompressFloats(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        float[] result = decompressFloatsNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress float array");
        }
        return result;
    }

    public double[] decompressDoubles(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
        double[] result = decompressDoublesNative(compressed);
        if (result == null) {
            throw new IllegalStateException("Failed to decompress double array");
        }
        return result;
    }

    public OpenZLCompressionInfo inspect(byte[] compressed) {
        ensureOpen();
        Objects.requireNonNull(compressed, "compressed");
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

    private static int checkedCapacity(long size) {
        if (size < 0) {
            throw new IllegalStateException("Native reported negative capacity: " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Required capacity exceeds ByteBuffer limit: " + size);
        }
        return (int) size;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private synchronized void cleanup() {
        if (nativeHandle != 0) {
            destroyCompressor();
            nativeHandle = 0;
        }
    }

    private static final class Releaser implements Runnable {
        private final OpenZLCompressor owner;

        private Releaser(OpenZLCompressor owner) {
            this.owner = owner;
        }

        @Override
        public void run() {
            owner.cleanup();
        }
    }
}
