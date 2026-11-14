package io.github.hybledav;

/** Native operation failure with a stable upstream error code and diagnostic context. */
public final class OpenZLException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final int errorCode;
    private final String operation;
    private final String nativeContext;

    public OpenZLException(int errorCode, String operation, String message, String nativeContext) {
        super(operation + ": " + message);
        this.errorCode = errorCode;
        this.operation = operation;
        this.nativeContext = nativeContext;
    }
    /** Upstream ZL_ErrorCode, or -1 when no upstream error code is available. */
    public int errorCode() { return errorCode; }
    public String operation() { return operation; }
    public String nativeContext() { return nativeContext; }
}
