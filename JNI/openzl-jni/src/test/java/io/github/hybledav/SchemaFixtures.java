package io.github.hybledav;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Shared protobuf schema fixtures for tests and benchmarks.
 * The descriptor is defined programmatically to avoid depending on generated artifacts.
 */
public final class SchemaFixtures {

    private SchemaFixtures() {}

    private static final String PACKAGE = "io.github.hybledav.bench";
    public static final String MESSAGE_TYPE = PACKAGE + ".BenchMessage";

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
                    .addField(fieldBuilder("optional_int32", 1,
                            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32,
                            DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .build();

            DescriptorProtos.DescriptorProto.Builder message = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("BenchMessage")
                    .addField(fieldBuilder("optional_int32", 1, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_int64", 2, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_uint32", 3, DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_uint64", 4, DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_sint32", 5, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_sint64", 6, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_fixed32", 7, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_fixed64", 8, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_sfixed32", 9, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_sfixed64", 10, DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_float", 11, DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_double", 12, DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_bool", 13, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_string", 14, DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_bytes", 15, DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).build())
                    .addField(fieldBuilder("optional_nested", 16, DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).setTypeName("Nested").build())
                    .addField(fieldBuilder("optional_enum", 17, DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM, DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL).setTypeName("State").build())
                    .addField(fieldBuilder("repeated_int32", 18, DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).build())
                    .addField(fieldBuilder("repeated_nested", 19, DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).setTypeName("Nested").build())
                    .addField(fieldBuilder("repeated_enum", 20, DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM, DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED).setTypeName("State").build())
                    .addNestedType(nested)
                    .addEnumType(stateEnum);

            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("bench_message.proto")
                    .setPackage(PACKAGE)
                    .addMessageType(message)
                    .build();

            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                    file, new Descriptors.FileDescriptor[0]);
            DESCRIPTOR = fileDescriptor.findMessageTypeByName("BenchMessage");
            DescriptorProtos.FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.newBuilder()
                    .addFile(fileDescriptor.toProto())
                    .build();
            DESCRIPTOR_BYTES = set.toByteArray();
        } catch (Descriptors.DescriptorValidationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder fieldBuilder(
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

    public static Descriptors.Descriptor descriptor() {
        return DESCRIPTOR;
    }

    public static byte[] descriptorBytes() {
        return DESCRIPTOR_BYTES.clone();
    }

    public static DescriptorProtos.FileDescriptorSet descriptorSet() {
        try {
            return DescriptorProtos.FileDescriptorSet.parseFrom(DESCRIPTOR_BYTES);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unable to parse descriptor set", e);
        }
    }
}
