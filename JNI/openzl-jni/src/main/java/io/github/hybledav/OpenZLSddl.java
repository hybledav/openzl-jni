package io.github.hybledav;

import java.util.Locale;
import java.util.Objects;

/**
 * Utilities for compiling Simple Data Description Language (SDDL) documents.
 */
public final class OpenZLSddl {
    private OpenZLSddl() {}

    /**
     * Compiles an SDDL description into the binary form accepted by the native engine.
     *
     * @param source human readable SDDL source
     * @param includeDebugInfo whether to embed debug metadata for better diagnostics
     * @param verbosity compiler verbosity level; higher values yield more logging
     * @return the compiled bytecode ready to be provided to {@link OpenZLCompressor#configureSddl(byte[])}
     */
    public static byte[] compile(String source, boolean includeDebugInfo, int verbosity) {
        Objects.requireNonNull(source, "source");
        OpenZLNative.load();
        byte[] compiled = compileNative(source, includeDebugInfo, verbosity);
        if (compiled == null || compiled.length == 0) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "SDDL compiler returned no data for input of length %d", source.length()));
        }
        return compiled;
    }

    private static native byte[] compileNative(String source, boolean includeDebugInfo, int verbosity);
}
