package io.github.hybledav;

/** Snapshot of a native compression/decompression warning. */
public final class OpenZLWarning {
    private final int errorCode;
    private final String message;
    private final String nativeContext;
    private OpenZLWarning(int errorCode, String message, String nativeContext) {
        this.errorCode = errorCode;
        this.message = message;
        this.nativeContext = nativeContext;
    }
    public int errorCode() { return errorCode; }
    public String message() { return message; }
    public String nativeContext() { return nativeContext; }
}
