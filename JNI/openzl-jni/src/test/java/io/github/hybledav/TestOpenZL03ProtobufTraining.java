package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

class TestOpenZL03ProtobufTraining {
    private static Descriptors.Descriptor schema(String name) throws Exception {
        var message = DescriptorProtos.DescriptorProto.newBuilder().setName(name)
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder().setName("numbers").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED));
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName(name + ".proto")
                .setPackage("jni.release03").addMessageType(message).build();
        var descriptor = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
                .findMessageTypeByName(name);
        OpenZLProtobuf.registerSchema(DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build().toByteArray());
        return descriptor;
    }

    private static byte[] sample(Descriptors.Descriptor descriptor, int seed) {
        var message = DynamicMessage.newBuilder(descriptor);
        for (int i = 0; i < 4096; i++) {
            message.addRepeatedField(descriptor.findFieldByNumber(1), i % 41 == 0 ? i + seed : 0);
        }
        return message.build().toByteArray();
    }

    @Test
    void automaticTrainingActuallyReplacesTheInitialGraphAndKeepsFramesReadable() throws Exception {
        var descriptor = schema("AutomaticTraining");
        String name = descriptor.getFullName();
        OpenZLProtobuf.configureTraining(name, 2);
        String initialGraph = OpenZLProtobuf.graphJson(name);
        byte[] first = sample(descriptor, 0);
        byte[] second = sample(descriptor, 1);
        byte[] before = OpenZLProtobuf.convert(first, OpenZLProtobuf.Protocol.PROTO, OpenZLProtobuf.Protocol.ZL, name);
        OpenZLProtobuf.convert(second, OpenZLProtobuf.Protocol.PROTO, OpenZLProtobuf.Protocol.ZL, name);
        assertNotEquals(initialGraph, OpenZLProtobuf.graphJson(name), "Training must not silently fail and keep the initial graph");
        byte[] after = OpenZLProtobuf.convert(first, OpenZLProtobuf.Protocol.PROTO, OpenZLProtobuf.Protocol.ZL, name);
        assertArrayEquals(first, OpenZLProtobuf.convert(before, OpenZLProtobuf.Protocol.ZL, OpenZLProtobuf.Protocol.PROTO, name));
        assertArrayEquals(first, OpenZLProtobuf.convert(after, OpenZLProtobuf.Protocol.ZL, OpenZLProtobuf.Protocol.PROTO, name));
    }

    @Test
    void explicitTrainingRetainsItsTargetFormatDuringConversion() throws Exception {
        var descriptor = schema("ExplicitTraining");
        String name = descriptor.getFullName();
        byte[][] samples = {sample(descriptor, 0), sample(descriptor, 1), sample(descriptor, 2)};
        TrainOptions options = new TrainOptions();
        options.formatVersion = 24;
        options.maxNumCandidates = 1;
        options.paretoFrontier = true;
        byte[][] trained = OpenZLProtobuf.train(samples, OpenZLProtobuf.Protocol.PROTO, options, name);
        assertEquals(1, trained.length);
        byte[] frame = OpenZLProtobuf.convert(samples[0], OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL, trained[0], name);
        try (OpenZLCompressor inspector = new OpenZLCompressor()) {
            assertEquals(24, inspector.inspect(frame).formatVersion(), "The serializer context must not override the trained format");
        }
        assertArrayEquals(samples[0], OpenZLProtobuf.convert(frame,
                OpenZLProtobuf.Protocol.ZL, OpenZLProtobuf.Protocol.PROTO, name));
        options.dictionaryTraining = true;
        assertThrows(IllegalArgumentException.class,
                () -> OpenZLProtobuf.train(samples, OpenZLProtobuf.Protocol.PROTO, options, name));
    }
}
