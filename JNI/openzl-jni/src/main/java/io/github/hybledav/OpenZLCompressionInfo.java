package io.github.hybledav;

import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Metadata describing a compressed frame. Instances are produced by
 * {@link OpenZLCompressor#inspect(byte[])} or its direct-buffer variant.
 */
public final class OpenZLCompressionInfo {
    /**
     * High level classification of the payload encoded in the frame.
     */
    public enum DataFlavor {
        SERIAL,
        STRUCT,
        NUMERIC,
        STRING,
        UNKNOWN;

        static DataFlavor fromNative(int code) {
            switch (code) {
                case 1:
                    return SERIAL;
                case 2:
                    return STRUCT;
                case 4:
                    return NUMERIC;
                case 8:
                    return STRING;
                default:
                    return UNKNOWN;
            }
        }
    }

    private final long originalSize;
    private final long compressedSize;
    private final OpenZLGraph graph;
    private final DataFlavor flavor;
    private final long elementCount;
    private final int formatVersion;

    OpenZLCompressionInfo(
            long originalSize,
            long compressedSize,
            OpenZLGraph graph,
            DataFlavor flavor,
            long elementCount,
            int formatVersion) {
        this.originalSize = originalSize;
        this.compressedSize = compressedSize;
        this.graph = Objects.requireNonNull(graph, "graph");
        this.flavor = Objects.requireNonNull(flavor, "flavor");
        this.elementCount = elementCount;
        this.formatVersion = formatVersion;
    }

    public long originalSize() {
        return originalSize;
    }

    public long compressedSize() {
        return compressedSize;
    }

    public OpenZLGraph graph() {
        return graph;
    }

    public DataFlavor flavor() {
        return flavor;
    }

    public OptionalLong elementCount() {
        return elementCount >= 0 ? OptionalLong.of(elementCount) : OptionalLong.empty();
    }

    public int formatVersion() {
        return formatVersion;
    }

    /**
     * Ratio of compressed bytes to original bytes. Zero when the original size is zero.
     */
    public double compressionRatio() {
        return originalSize == 0 ? 0.0d : (double) compressedSize / (double) originalSize;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "OpenZLCompressionInfo{original=%d, compressed=%d, ratio=%.2f%%, flavor=%s, graph=%s, elements=%s, version=%d}",
                originalSize,
                compressedSize,
                compressionRatio() * 100.0d,
                flavor,
                graph,
                elementCount >= 0 ? elementCount : "n/a",
                formatVersion);
    }
}
