package io.github.hybledav;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
     * Converts the payload using the supplied message type.
     *
     * @param payload       input data, interpreted by {@code inputProtocol}
     * @param inputProtocol protocol describing {@code payload}
     * @param outputProtocol target protocol
     * @param messageType   fully-qualified protobuf message name
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            String messageType) {
        return convert(payload, inputProtocol, outputProtocol, null, messageType);
    }

    /**
     * Converts the payload, optionally supplying a trained compressor.
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            String messageType) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        byte[] converted = convertNative(payload,
                inputProtocol.id(),
                outputProtocol.id(),
                compressor,
                messageType);
        if (converted == null) {
            throw new IllegalStateException("Native protobuf conversion failed");
        }
        return converted;
    }

    /**
     * Convenience wrapper for descriptor-backed conversions. The descriptor's defining file is
     * registered with the native bridge before conversion.
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        return convert(payload, inputProtocol, outputProtocol, null, descriptor);
    }

    /**
     * Descriptor-backed conversion with optional compressor.
     */
    public static byte[] convert(byte[] payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            byte[] compressor,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return convert(payload,
                inputProtocol,
                outputProtocol,
                compressor,
                descriptor.getFullName());
    }

    /**
     * Convenience for JSON payloads.
     */
    public static String convertJson(String payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            String messageType) {
        Objects.requireNonNull(payload, "payload");
        byte[] bytes = convert(payload.getBytes(StandardCharsets.UTF_8),
                inputProtocol,
                outputProtocol,
                messageType);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static String convertJson(String payload,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return convertJson(payload,
                inputProtocol,
                outputProtocol,
                descriptor.getFullName());
    }

    /**
     * Trains a compressor using the provided message type.
     */
    public static byte[][] train(byte[][] samples,
            Protocol inputProtocol,
            TrainOptions options,
            String messageType) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(messageType, "messageType");
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
                opts.paretoFrontier,
                messageType);
        if (trained == null) {
            throw new IllegalStateException("Native protobuf training failed");
        }
        return trained;
    }

    public static byte[][] train(byte[][] samples,
            Protocol inputProtocol,
            TrainOptions options,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return train(samples,
                inputProtocol,
                options,
                descriptor.getFullName());
    }

    /**
     * Registers all descriptors reachable from the supplied descriptor.
     */
    public static void registerSchema(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
    }

    public static void registerSchema(Descriptors.FileDescriptor fileDescriptor) {
        Objects.requireNonNull(fileDescriptor, "fileDescriptor");
        DescriptorProtos.FileDescriptorSet descriptorSet = buildDescriptorSet(fileDescriptor);
        registerSchema(descriptorSet.toByteArray());
    }

    public static void registerSchema(byte[] descriptorSet) {
        Objects.requireNonNull(descriptorSet, "descriptorSet");
        OpenZLNative.load();
        registerSchemaNative(descriptorSet);
    }

    private static DescriptorProtos.FileDescriptorSet buildDescriptorSet(
            Descriptors.FileDescriptor file) {
        DescriptorProtos.FileDescriptorSet.Builder builder =
                DescriptorProtos.FileDescriptorSet.newBuilder();
        Set<String> seen = new HashSet<>();
        collectFileDescriptors(file, builder, seen);
        return builder.build();
    }

    private static void collectFileDescriptors(Descriptors.FileDescriptor file,
            DescriptorProtos.FileDescriptorSet.Builder builder,
            Set<String> seen) {
        if (file == null) {
            return;
        }
        if (!seen.add(file.getName())) {
            return;
        }
        builder.addFile(file.toProto());
        for (Descriptors.FileDescriptor dependency : file.getDependencies()) {
            collectFileDescriptors(dependency, builder, seen);
        }
    }

    private static native byte[] convertNative(byte[] payload,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);

    private static native byte[][] trainNative(byte[][] samples,
            int inputProtocol,
            int maxTimeSecs,
            int threads,
            int numSamples,
            boolean pareto,
            String messageType);

    private static native void registerSchemaNative(byte[] descriptorSet);
}
