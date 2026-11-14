package io.github.hybledav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** An immutable snapshot of one input stream, including binary string boundaries.
 * Buffer factories copy remaining bytes and preserve the caller's position and limit.
 * Numeric bytes must use the native byte order; width alone does not distinguish floats.
 */
public final class TypedInput {
    final int type;
    final int width;
    final byte[] data;
    final int[] lengths;

    private TypedInput(OpenZLType type, byte[] data, int width, int[] lengths) {
        this.type = type.ordinal();
        this.data = Objects.requireNonNull(data, "data").clone();
        if (width <= 0 || data.length % width != 0) {
            throw new IllegalArgumentException("Positive element width must divide the byte length");
        }
        this.width = width;
        this.lengths = lengths.clone();
    }

    public static TypedInput serial(byte[] data) {
        return new TypedInput(OpenZLType.SERIAL, data, 1, new int[0]);
    }
    public static TypedInput serial(ByteBuffer data) { return serial(copy(data)); }
    public static TypedInput structs(byte[] data, int width) {
        return new TypedInput(OpenZLType.STRUCT, data, width, new int[0]);
    }
    public static TypedInput structs(ByteBuffer data, int width) { return structs(copy(data), width); }
    public static TypedInput numeric(byte[] data, int width) {
        if (width != 1 && width != 2 && width != 4 && width != 8) {
            throw new IllegalArgumentException("Numeric width must be 1, 2, 4 or 8");
        }
        return new TypedInput(OpenZLType.NUMERIC, data, width, new int[0]);
    }
    public static TypedInput numeric(ByteBuffer data, int width) {
        Objects.requireNonNull(data, "data");
        if (data.order() != ByteOrder.nativeOrder()) {
            throw new IllegalArgumentException("Numeric buffer must use native byte order");
        }
        return numeric(copy(data), width);
    }
    public static TypedInput strings(byte[] data, int[] lengths) {
        Objects.requireNonNull(data, "data");
        int[] snapshot = Objects.requireNonNull(lengths, "lengths").clone();
        long total = 0;
        for (int length : snapshot) {
            if (length < 0) throw new IllegalArgumentException("Negative string length");
            total += length;
        }
        if (total != data.length) throw new IllegalArgumentException("String lengths must sum to byte length");
        return new TypedInput(OpenZLType.STRING, data, 1, snapshot);
    }
    public static TypedInput strings(ByteBuffer data, int[] lengths) { return strings(copy(data), lengths); }
    public static TypedInput ints(int[] values) {
        ByteBuffer data = allocate(values.length, Integer.BYTES);
        data.asIntBuffer().put(values);
        return numeric(data.array(), Integer.BYTES);
    }
    public static TypedInput longs(long[] values) {
        ByteBuffer data = allocate(values.length, Long.BYTES);
        data.asLongBuffer().put(values);
        return numeric(data.array(), Long.BYTES);
    }
    public static TypedInput floats(float[] values) {
        ByteBuffer data = allocate(values.length, Float.BYTES);
        data.asFloatBuffer().put(values);
        return numeric(data.array(), Float.BYTES);
    }
    public static TypedInput doubles(double[] values) {
        ByteBuffer data = allocate(values.length, Double.BYTES);
        data.asDoubleBuffer().put(values);
        return numeric(data.array(), Double.BYTES);
    }
    private static ByteBuffer allocate(int count, int width) {
        return ByteBuffer.allocate(Math.multiplyExact(count, width)).order(ByteOrder.nativeOrder());
    }
    static byte[] copy(ByteBuffer data) {
        ByteBuffer view = Objects.requireNonNull(data, "data").duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }
}
