package io.github.hybledav.bench.protobuf;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class BenchSchema {
    private static final String PACKAGE = "io.github.hybledav.bench";
    private static final String MESSAGE = "BenchMessage";

    private static final Descriptors.Descriptor DESCRIPTOR;
    private static final byte[] DESCRIPTOR_BYTES;

    static {
        try {
            DescriptorProtos.EnumDescriptorProto stateEnum = DescriptorProtos.EnumDescriptorProto.newBuilder()
                    .setName("State")
                    .addValue(enumValue("ZERO", 0))
                    .addValue(enumValue("ONE", 1))
                    .build();

            DescriptorProtos.DescriptorProto nested = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("Nested")
                    .addField(field("optional_int32", 1,
                            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32,
                            DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .build();

            DescriptorProtos.DescriptorProto.Builder message = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName(MESSAGE)
                    .addField(field("optional_int32", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_int64", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_uint32", 3, DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_uint64", 4, DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_sint32", 5, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_sint64", 6, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_fixed32", 7, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_fixed64", 8, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_sfixed32", 9, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_sfixed64", 10, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_float", 11, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_double", 12, DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_bool", 13, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_string", 14, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_bytes", 15, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(field("optional_nested", 16, DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).setTypeName("Nested").build())
                    .addField(field("optional_enum", 17, DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).setTypeName("State").build())
                    .addField(field("repeated_int32", 18, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).build())
                    .addField(field("repeated_nested", 19, DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).setTypeName("Nested").build())
                    .addField(field("repeated_enum", 20, DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).setTypeName("State").build())
                    .addNestedType(nested)
                    .addEnumType(stateEnum);

            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("bench_message.proto")
                    .setPackage(PACKAGE)
                    .addMessageType(message)
                    .build();

            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]);
            DESCRIPTOR = fileDescriptor.findMessageTypeByName(MESSAGE);
            DESCRIPTOR_BYTES = DescriptorProtos.FileDescriptorSet.newBuilder()
                    .addFile(fileDescriptor.toProto())
                    .build()
                    .toByteArray();
        } catch (Descriptors.DescriptorValidationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private BenchSchema() {}

    static Descriptors.Descriptor descriptor() {
        return DESCRIPTOR;
    }

    static String messageType() {
        return PACKAGE + "." + MESSAGE;
    }

    static byte[] descriptorBytes() {
        return DESCRIPTOR_BYTES.clone();
    }

    static byte[] buildProtoPayload(byte[] saoBytes, int offsetSeed, int chunkSize) {
        int length = Math.max(64, chunkSize);
        int offset = Math.floorMod(offsetSeed, Math.max(1, saoBytes.length));
        byte[] chunk = new byte[length];
        fillCyclic(saoBytes, offset, chunk);

        Descriptors.Descriptor descriptor = descriptor();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(descriptor);
        b.setField(descriptor.findFieldByName("optional_int32"), offsetSeed);
        b.setField(descriptor.findFieldByName("optional_int64"), (long) offsetSeed * 13L);
        b.setField(descriptor.findFieldByName("optional_uint32"), Math.abs(offsetSeed));
        b.setField(descriptor.findFieldByName("optional_uint64"), Math.abs((long) offsetSeed) * 37L);
        b.setField(descriptor.findFieldByName("optional_sint32"), -Math.abs(offsetSeed));
        b.setField(descriptor.findFieldByName("optional_sint64"), -Math.abs((long) offsetSeed) * 17L);
        b.setField(descriptor.findFieldByName("optional_fixed32"), offsetSeed ^ 0x55AA55AA);
        b.setField(descriptor.findFieldByName("optional_fixed64"), ((long) offsetSeed << 32) ^ 0x55AA55AA55AA55AAL);
        b.setField(descriptor.findFieldByName("optional_sfixed32"), offsetSeed * -7);
        b.setField(descriptor.findFieldByName("optional_sfixed64"), (long) offsetSeed * -11L);
        b.setField(descriptor.findFieldByName("optional_float"), (float) (offsetSeed % 1024) / 7.0f);
        b.setField(descriptor.findFieldByName("optional_double"), (double) (offsetSeed % 8192) / 13.0d);
        b.setField(descriptor.findFieldByName("optional_bool"), (offsetSeed & 1) == 0);

        String token = Base64.getEncoder().encodeToString(slice(chunk, 96));
        b.setField(descriptor.findFieldByName("optional_string"),
                "sao-bench-" + offsetSeed + '-' + token + '-' + new String(slice(chunk, 48), StandardCharsets.ISO_8859_1));
        b.setField(descriptor.findFieldByName("optional_bytes"), com.google.protobuf.ByteString.copyFrom(chunk));

        Descriptors.Descriptor nested = descriptor.findNestedTypeByName("Nested");
        DynamicMessage nestedMsg = DynamicMessage.newBuilder(nested)
                .setField(nested.findFieldByName("optional_int32"), offsetSeed * 3)
                .build();
        b.setField(descriptor.findFieldByName("optional_nested"), nestedMsg);

        Descriptors.EnumValueDescriptor enumValue = descriptor.findEnumTypeByName("State")
                .findValueByName((offsetSeed & 1) == 0 ? "ONE" : "ZERO");
        b.setField(descriptor.findFieldByName("optional_enum"), enumValue);

        Descriptors.FieldDescriptor repeatedInt = descriptor.findFieldByName("repeated_int32");
        Descriptors.FieldDescriptor repeatedNested = descriptor.findFieldByName("repeated_nested");
        Descriptors.FieldDescriptor repeatedEnum = descriptor.findFieldByName("repeated_enum");
        for (int i = 0; i < 12; ++i) {
            b.addRepeatedField(repeatedInt, offsetSeed + i * 31);
            b.addRepeatedField(repeatedNested, DynamicMessage.newBuilder(nested)
                    .setField(nested.findFieldByName("optional_int32"), offsetSeed + i)
                    .build());
            b.addRepeatedField(repeatedEnum, descriptor.findEnumTypeByName("State")
                    .findValueByName((i & 1) == 0 ? "ZERO" : "ONE"));
        }

        return b.build().toByteArray();
    }

    private static byte[] slice(byte[] data, int length) {
        int n = Math.min(length, data.length);
        byte[] out = new byte[n];
        System.arraycopy(data, 0, out, 0, n);
        return out;
    }

    private static void fillCyclic(byte[] src, int startOffset, byte[] dst) {
        if (src.length == 0) {
            return;
        }
        int srcPos = Math.floorMod(startOffset, src.length);
        int dstPos = 0;
        while (dstPos < dst.length) {
            int copy = Math.min(src.length - srcPos, dst.length - dstPos);
            System.arraycopy(src, srcPos, dst, dstPos, copy);
            dstPos += copy;
            srcPos = 0;
        }
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder field(
            String name,
            int number,
            DescriptorProtos.FieldDescriptorProto.Type type,
            DescriptorProtos.FieldDescriptorProto.Label label) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(label);
    }

    private static DescriptorProtos.EnumValueDescriptorProto enumValue(String name, int number) {
        return DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .build();
    }
}
