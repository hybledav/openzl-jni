package io.github.hybledav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One owned, immutable decompressed stream. Returned arrays are defensive copies. */
public final class TypedOutput {
    private final OpenZLType type;
    private final int width;
    private final byte[] data;
    private final int[] lengths;

    // Only JNI constructs outputs; arrays are newly allocated and owned by this object.
    private TypedOutput(int type, int width, byte[] data, int[] lengths) {
        this.type = OpenZLType.values()[type];
        this.width = width;
        this.data = data;
        this.lengths = lengths;
    }
    public OpenZLType type() { return type; }
    /** Fixed element width, or 0 for variable-length strings. */
    public int elementWidth() { return width; }
    public int elementCount() {
        return type == OpenZLType.STRING ? lengths.length : data.length / width;
    }
    public byte[] bytes() { return data.clone(); }
    public int[] stringLengths() {
        if (type != OpenZLType.STRING) throw new IllegalStateException("Output is not a string stream");
        return lengths.clone();
    }
    private ByteBuffer numeric(int expectedWidth) {
        if (type != OpenZLType.NUMERIC || width != expectedWidth) {
            throw new IllegalStateException("Output is not numeric with width " + expectedWidth);
        }
        return ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
    }
    public int[] ints() {
        int[] result = new int[data.length / Integer.BYTES];
        numeric(Integer.BYTES).asIntBuffer().get(result);
        return result;
    }
    public long[] longs() {
        long[] result = new long[data.length / Long.BYTES];
        numeric(Long.BYTES).asLongBuffer().get(result);
        return result;
    }
    public float[] floats() {
        float[] result = new float[data.length / Float.BYTES];
        numeric(Float.BYTES).asFloatBuffer().get(result);
        return result;
    }
    public double[] doubles() {
        double[] result = new double[data.length / Double.BYTES];
        numeric(Double.BYTES).asDoubleBuffer().get(result);
        return result;
    }
}
