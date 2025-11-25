package io.github.hybledav;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenZLTrainingE2ETest {

    @Test
    void trainedCompressorShrinksSensorTelemetry() throws Exception {
        Descriptors.Descriptor descriptor = telemetryDescriptor();
        Descriptors.FieldDescriptor sensorId = descriptor.findFieldByName("sensor_id");
        Descriptors.FieldDescriptor readings = descriptor.findFieldByName("reading");

        // Register schema so JNI side can resolve the dynamic message.
        OpenZLProtobuf.registerSchema(descriptor.getFile());

        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            DynamicMessage msg = sample(descriptor, sensorId, readings, i);
            samples.add(msg.toByteArray());
        }

        byte[][] trained = OpenZLProtobuf.train(
                samples.toArray(new byte[0][]),
                OpenZLProtobuf.Protocol.PROTO,
                null,
                descriptor);
        assertFalse(trained == null || trained.length == 0, "training must yield compressors");

        DynamicMessage validationMessage = sample(descriptor, sensorId, readings, 99);
        byte[] baseline = OpenZLProtobuf.convert(
                validationMessage.toByteArray(),
                OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,
                descriptor);

        int bestSize = baseline.length;
        for (byte[] candidate : trained) {
            byte[] encoded = OpenZLProtobuf.convert(
                    validationMessage.toByteArray(),
                    OpenZLProtobuf.Protocol.PROTO,
                    OpenZLProtobuf.Protocol.ZL,
                    candidate,
                    descriptor);
            bestSize = Math.min(bestSize, encoded.length);
        }

        System.out.printf(
                "telemetry baseline=%d bytes, best trained=%d bytes%n",
                baseline.length,
                bestSize);

        // Trained compressors should beat or match the stock serializer; we expect a visible drop.
        assertTrue(bestSize < baseline.length, "trained compressor should reduce payload size");
    }

    private static Descriptors.Descriptor telemetryDescriptor() throws Descriptors.DescriptorValidationException {
        DescriptorProtos.DescriptorProto telemetry = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Telemetry")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("sensor_id")
                        .setNumber(1)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("reading")
                        .setNumber(2)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32))
                .build();

        DescriptorProtos.FileDescriptorProto fdProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("telemetry.proto")
                .setPackage("bench")
                .addMessageType(telemetry)
                .build();
        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(
                fdProto,
                new Descriptors.FileDescriptor[0]);
        return fd.findMessageTypeByName("Telemetry");
    }

    private static DynamicMessage sample(
            Descriptors.Descriptor descriptor,
            Descriptors.FieldDescriptor sensorId,
            Descriptors.FieldDescriptor readings,
            int variant) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        builder.setField(sensorId, "sensor-" + (variant % 3));
        // Create a predictable, compressible waveform: long plateaus with occasional spikes.
        // 16K readings pushes the baseline encoded payload into low-KiB territory.
        for (int i = 0; i < 16_384; i++) {
            int value = (i / 96) * (variant % 5 + 1);
            if ((i + variant) % 211 == 0) {
                value = 4096 + variant; // sparse outlier to keep entropy modest
            }
            builder.addRepeatedField(readings, value);
        }
        return builder.build();
    }
}
