package io.github.hybledav;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridging helpers for OpenZL's Protobuf support. The native implementation mirrors the
 * behaviour of the {@code protobuf_cli} utility: payloads can be converted between raw
 * protobuf, OpenZL, and JSON representations, and compressors can be trained directly
 * from protobuf samples.
 */
public final class OpenZLProtobuf {
    private OpenZLProtobuf() {}

    private static final Set<String> REGISTERED_FILE_NAMES = ConcurrentHashMap.newKeySet();

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
     * Overrides the minimum number of protobuf samples collected before training a compressor for
     * the supplied descriptor. Values &lt;= 0 leave the default behaviour unchanged.
     */
    public static void configureTraining(Descriptors.Descriptor descriptor, int minSamples) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (minSamples <= 0) {
            return;
        }
        registerSchema(descriptor.getFile());
        configureTraining(descriptor.getFullName(), minSamples);
    }

    /**
     * Overrides the minimum number of protobuf samples collected before training a compressor for
     * the supplied message type. Values &lt;= 0 leave the default behaviour unchanged.
     */
    public static void configureTraining(String messageType, int minSamples) {
        Objects.requireNonNull(messageType, "messageType");
        if (minSamples <= 0) {
            return;
        }
        OpenZLNative.load();
        configureTrainingNative(messageType, minSamples);
    }

    /**
     * Converts a sub-range of the payload, avoiding an intermediate array copy when the caller can
     * expose the backing buffer directly.
     */
    public static byte[] convert(byte[] payload,
            int offset,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(descriptor, "descriptor");
        if (offset < 0 || length < 0 || offset + length > payload.length) {
            throw new IndexOutOfBoundsException(
                    "Invalid offset/length: offset=" + offset + ", length=" + length
                            + ", payloadLength=" + payload.length);
        }
        registerSchema(descriptor.getFile());
        return convertSliceNative(payload,
                offset,
                length,
                inputProtocol.id(),
                outputProtocol.id(),
                null,
                descriptor.getFullName());
    }

    /**
     * Converts a direct {@link ByteBuffer}-backed protobuf payload without copying into a
     * temporary Java array. The buffer must remain valid until the conversion completes.
     */
    public static byte[] convert(ByteBuffer payload,
            int length,
            Protocol inputProtocol,
            Protocol outputProtocol,
            Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(inputProtocol, "inputProtocol");
        Objects.requireNonNull(outputProtocol, "outputProtocol");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!payload.isDirect()) {
            throw new IllegalArgumentException("payload must be a direct ByteBuffer");
        }
        if (length < 0 || length > payload.capacity()) {
            throw new IllegalArgumentException("length out of bounds: " + length);
        }
        registerSchema(descriptor.getFile());
        return convertDirectNative(payload,
                length,
                inputProtocol.id(),
                outputProtocol.id(),
                null,
                descriptor.getFullName());
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
     * Returns a JSON representation of the native compressor graph used for the
     * provided protobuf message type.
     */
    public static String graphJson(String messageType) {
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        String json = graphJsonNative(messageType);
        if (json == null) {
            throw new IllegalStateException("Native protobuf graph export failed");
        }
        return json;
    }

    /**
     * Returns a JSON representation of a trained compressor instance.
     */
    public static String graphJsonFromCompressor(byte[] compressorBytes) {
        Objects.requireNonNull(compressorBytes, "compressorBytes");
        OpenZLNative.load();
        String json = graphJsonFromCompressorNative(compressorBytes);
        if (json == null) {
            throw new IllegalStateException("Native compressor graph export failed");
        }
        return json;
    }

    /**
     * Descriptor-backed graph export.
     */
    public static String graphJson(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return graphJson(descriptor.getFullName());
    }

    /**
     * Returns a detailed JSON structure describing the native graph topology.
     */
    public static String graphDetailJson(String messageType) {
        Objects.requireNonNull(messageType, "messageType");
        OpenZLNative.load();
        String json = graphDetailJsonNative(messageType);
        if (json == null) {
            throw new IllegalStateException("Native protobuf graph detail export failed");
        }
        return json;
    }

    /**
     * Descriptor-backed graph detail export.
     */
    public static String graphDetailJson(Descriptors.Descriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        registerSchema(descriptor.getFile());
        return graphDetailJson(descriptor.getFullName());
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
        Set<String> fileNames = new HashSet<>();
        collectFileNames(fileDescriptor, fileNames);
        if (fileNames.isEmpty()) {
            return;
        }
        synchronized (REGISTERED_FILE_NAMES) {
            if (REGISTERED_FILE_NAMES.containsAll(fileNames)) {
                return;
            }
        }
        DescriptorProtos.FileDescriptorSet descriptorSet = buildDescriptorSet(fileDescriptor);
        registerSchemaInternal(descriptorSet, fileNames);
    }

    public static void registerSchema(byte[] descriptorSet) {
        Objects.requireNonNull(descriptorSet, "descriptorSet");
        DescriptorProtos.FileDescriptorSet set;
        try {
            set = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorSet);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Invalid descriptor set", e);
        }
        if (set.getFileCount() == 0) {
            return;
        }
        Set<String> fileNames = new HashSet<>();
        for (DescriptorProtos.FileDescriptorProto file : set.getFileList()) {
            fileNames.add(file.getName());
        }
        registerSchemaInternal(set, fileNames);
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

    private static void collectFileNames(Descriptors.FileDescriptor file, Set<String> sink) {
        if (file == null) {
            return;
        }
        if (!sink.add(file.getName())) {
            return;
        }
        for (Descriptors.FileDescriptor dependency : file.getDependencies()) {
            collectFileNames(dependency, sink);
        }
    }

    private static void registerSchemaInternal(
            DescriptorProtos.FileDescriptorSet descriptorSet,
            Set<String> fileNames) {
        if (descriptorSet == null || descriptorSet.getFileCount() == 0 || fileNames.isEmpty()) {
            return;
        }
        synchronized (REGISTERED_FILE_NAMES) {
            if (REGISTERED_FILE_NAMES.containsAll(fileNames)) {
                return;
            }
            OpenZLNative.load();
            registerSchemaNative(descriptorSet.toByteArray());
            REGISTERED_FILE_NAMES.addAll(fileNames);
        }
    }

    private static native byte[] convertNative(byte[] payload,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);
    private static native byte[] convertSliceNative(byte[] payload,
            int offset,
            int length,
            int inputProtocol,
            int outputProtocol,
            byte[] compressor,
            String messageType);

    private static native byte[] convertDirectNative(ByteBuffer payload,
            int length,
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

    private static native void configureTrainingNative(String messageType, int minSamples);

    private static native void registerSchemaNative(byte[] descriptorSet);

    private static native String graphJsonNative(String messageType);
    private static native String graphJsonFromCompressorNative(byte[] compressorBytes);

    private static native String graphDetailJsonNative(String messageType);
}
