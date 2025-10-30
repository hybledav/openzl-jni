package io.github.hybledav;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bridging helpers for OpenZL's Protobuf support. The native implementation mirrors the
 * behaviour of the {@code protobuf_cli} utility: payloads can be converted between raw
 * protobuf, OpenZL, and JSON representations, and compressors can be trained directly
 * from protobuf samples.
 */
public final class OpenZLProtobuf {
    private OpenZLProtobuf() {}

    public enum Protocol {
        PROTO(0),
        ZL(1),
        JSON(2);

        private final int id;

        Protocol(int id) {
            this.id = id;
        }

        int id() {
            return id;
        }
    }

    /**
     * Converts the payload from the given input protocol to the requested output protocol.
     *
     * @param payload        input data, interpreted according to {@code inputProtocol}
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @return converted bytes (JSON is returned as UTF-8)
     */
    public static byte[] convert(byte[] payload, Protocol inputProtocol, Protocol outputProtocol) {
        return convert(payload, inputProtocol, outputProtocol, null);
    }

    /**
     * Converts the payload from the given input protocol to the requested output protocol,
     * optionally using a previously trained compressor to drive the serialization.
     *
     * @param payload        input data
     * @param inputProtocol  protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param compressor     optional serialized compressor produced by {@link #train(byte[][], Protocol, TrainOptions)}
     * @return converted bytes (JSON is returned as UTF-8)
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        OpenZLNative.load();
        byte[] converted = convertNative(payload, inputProtocol.id(), outputProtocol.id(), compressor);
        if (converted == null) {
            throw new IllegalStateException("Native protobuf conversion failed");
        }
        return converted;
    }

    /**
     * Convenience wrapper around {@link #convert(byte[], Protocol, Protocol)} when working with JSON strings.
     */
    public static String convertJson(String payload,
            Protocol inputProtocol,
            Protocol outputProtocol) {
        Objects.requireNonNull(payload, "payload");
        byte[] bytes = convert(payload.getBytes(StandardCharsets.UTF_8), inputProtocol, outputProtocol);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Trains a protobuf-aware compressor from the supplied samples.
     *
     * @param samples       protobuf messages encoded according to {@code inputProtocol}
     * @param inputProtocol protocol describing each entry in {@code samples}
     * @param options       optional tuning parameters (a fresh instance is created when null)
     * @return serialized compressors (non-empty when training succeeds)
     */
    public static byte[][] train(byte[][] samples, Protocol inputProtocol, TrainOptions options) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        for (int i = 0; i < samples.length; ++i) {
            if (samples[i] == null) {
                throw new NullPointerException("samples[" + i + "]");
            }
        }

        TrainOptions opts = options == null ? new TrainOptions() : options;
        OpenZLNative.load();
        byte[][] trained = trainNative(samples,
                inputProtocol.id(),
                opts.maxTimeSecs,
                opts.threads,
                opts.numSamples,
                opts.paretoFrontier);
        if (trained == null) {
            throw new IllegalStateException("Native protobuf training failed");
        }
        return trained;
    }

    private static native byte[] convertNative(byte[] payload,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor);

    private static native byte[][] trainNative(byte[][] samples,
            int inputProtocol,
            int maxTimeSecs,
            int threads,
            int numSamples,
            boolean pareto);
}
