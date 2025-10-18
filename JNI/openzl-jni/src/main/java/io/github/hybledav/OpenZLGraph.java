package io.github.hybledav;

import java.util.Locale;

/**
 * Enumerates the standard compression graphs exposed by OpenZL.
 * <p>
 * The values map to the builtin graphs provided by the native library. The {@code id}
 * associated with each entry is used on the JNI boundary; the actual {@code ZL_GraphID}
 * values are resolved on the native side so we are resilient to upstream renumbering.
 */
public enum OpenZLGraph {
    AUTO(-1, "auto", "Allow OpenZL to pick the default graph (currently zstd)."),
    ZSTD(0, "zstd", "General purpose LZ77 + entropy combination."),
    GENERIC(1, "generic", "Hybrid graph that applies lightweight transforms before entropy coding."),
    NUMERIC(2, "numeric", "Pipeline specialised for arrays of numeric primitives."),
    STORE(3, "store", "Passthrough graph: inputs kept verbatim (no compression)."),
    BITPACK(4, "bitpack", "Bitpacking graph optimised for values with small effective range."),
    FSE(5, "fse", "Finite State Entropy only pipeline."),
    HUFFMAN(6, "huffman", "Huffman entropy coding pipeline."),
    ENTROPY(7, "entropy", "Generic entropy stage without transforms."),
    CONSTANT(8, "constant", "Fast path for nearly constant inputs.");

    private final int id;
    private final String displayName;
    private final String description;

    OpenZLGraph(int id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Identifier used for JNI dispatch. The returned value is stable for the lifetime
     * of this release and is interpreted on the native side.
     */
    int id() {
        return id;
    }

    /**
     * @return Human readable name of the graph.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * @return Short description describing what the graph optimises for.
     */
    public String description() {
        return description;
    }

    /**
     * Maps the ordinal supplied by native code back to an enum instance. Unknown ids
     * are mapped to {@link #AUTO}, signalling that the graph could not be inferred.
     */
    static OpenZLGraph fromNativeId(int nativeId) {
        for (OpenZLGraph graph : values()) {
            if (graph.id == nativeId) {
                return graph;
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s(%s)", name(), displayName);
    }
}
