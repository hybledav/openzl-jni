package io.github.hybledav.utils.sao;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.util.ArrayList;
import java.util.List;

public final class SaoBatchPayloadGenerator {
    private static final String PACKAGE = "openzl.integration";
    private static final String MESSAGE = "SaoBatch";

    private static final Descriptors.Descriptor BATCH_DESCRIPTOR;
    private static final byte[] DESCRIPTOR_BYTES;

    static {
        try {
            DescriptorProtos.DescriptorProto header = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("SaoHeader")
                    .addField(uint32Field("star0", 1))
                    .addField(uint32Field("star1", 2))
                    .addField(uint32Field("star_count", 3))
                    .addField(uint32Field("star_id_flag", 4))
                    .addField(boolField("has_proper_motion", 5))
                    .addField(uint32Field("magnitude_count", 6))
                    .addField(uint32Field("bytes_per_entry", 7))
                    .build();

            DescriptorProtos.DescriptorProto batch = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName(MESSAGE)
                    .addField(messageField("header", 1, "SaoHeader", false))
                    .addField(uint32Field("batch_start_index", 2))
                    .addField(uint32FieldRepeated("catalog_numbers", 3))
                    .addField(sint32FieldRepeated("ra_milliarc_seconds", 4))
                    .addField(sint32FieldRepeated("dec_milliarc_seconds", 5))
                    .addField(bytesField("spectral_types", 6))
                    .addField(sint32FieldRepeated("magnitude_centimags", 7))
                    .addField(sint32FieldRepeated("ra_proper_motion_microarcsec_per_year", 8))
                    .addField(sint32FieldRepeated("dec_proper_motion_microarcsec_per_year", 9))
                    .build();

            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("sao_bench.proto")
                    .setPackage(PACKAGE)
                    .addMessageType(header)
                    .addMessageType(batch)
                    .build();

            Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]);
            BATCH_DESCRIPTOR = fd.findMessageTypeByName(MESSAGE);
            DESCRIPTOR_BYTES = DescriptorProtos.FileDescriptorSet.newBuilder()
                    .addFile(fd.toProto())
                    .build()
                    .toByteArray();
        } catch (Descriptors.DescriptorValidationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private SaoBatchPayloadGenerator() {}

    public static String messageType() {
        return PACKAGE + "." + MESSAGE;
    }

    public static byte[] descriptorBytes() {
        return DESCRIPTOR_BYTES.clone();
    }

    public static List<byte[]> makePayloads(byte[] saoRaw, int payloadCount, int targetBytes) {
        List<SaoStarRecord> stars = SaoDataLoader.parseCatalog(saoRaw);
        if (stars.isEmpty()) {
            throw new IllegalStateException("No parsed SAO records available");
        }

        int starsPerBatch = estimateStarsPerBatch(stars, Math.max(8 * 1024, targetBytes));
        int maxStart = Math.max(0, stars.size() - starsPerBatch);

        List<byte[]> out = new ArrayList<>(payloadCount);
        for (int i = 0; i < payloadCount; i++) {
            int start = maxStart == 0 ? 0 : Math.floorMod(i * 3571, maxStart + 1);
            out.add(buildBatch(stars, start, starsPerBatch).toByteArray());
        }
        return out;
    }

    private static DynamicMessage buildBatch(List<SaoStarRecord> stars, int startIndex, int count) {
        int total = Math.min(count, stars.size() - startIndex);
        if (total <= 0) {
            total = Math.min(count, stars.size());
            startIndex = 0;
        }

        Descriptors.Descriptor headerDescriptor = BATCH_DESCRIPTOR.findFieldByName("header").getMessageType();
        DynamicMessage.Builder header = DynamicMessage.newBuilder(headerDescriptor);
        header.setField(headerDescriptor.findFieldByName("star0"), stars.get(startIndex).catalogNumber);
        header.setField(headerDescriptor.findFieldByName("star1"), stars.get(startIndex + total - 1).catalogNumber);
        header.setField(headerDescriptor.findFieldByName("star_count"), total);
        header.setField(headerDescriptor.findFieldByName("star_id_flag"), 1);
        header.setField(headerDescriptor.findFieldByName("has_proper_motion"), true);
        header.setField(headerDescriptor.findFieldByName("magnitude_count"), total);
        header.setField(headerDescriptor.findFieldByName("bytes_per_entry"), 2);

        DynamicMessage.Builder batch = DynamicMessage.newBuilder(BATCH_DESCRIPTOR);
        batch.setField(BATCH_DESCRIPTOR.findFieldByName("header"), header.build());
        batch.setField(BATCH_DESCRIPTOR.findFieldByName("batch_start_index"), startIndex);

        Descriptors.FieldDescriptor catalog = BATCH_DESCRIPTOR.findFieldByName("catalog_numbers");
        Descriptors.FieldDescriptor ra = BATCH_DESCRIPTOR.findFieldByName("ra_milliarc_seconds");
        Descriptors.FieldDescriptor dec = BATCH_DESCRIPTOR.findFieldByName("dec_milliarc_seconds");
        Descriptors.FieldDescriptor magnitudes = BATCH_DESCRIPTOR.findFieldByName("magnitude_centimags");
        Descriptors.FieldDescriptor raPm = BATCH_DESCRIPTOR.findFieldByName("ra_proper_motion_microarcsec_per_year");
        Descriptors.FieldDescriptor decPm = BATCH_DESCRIPTOR.findFieldByName("dec_proper_motion_microarcsec_per_year");

        byte[] spectral = new byte[total * 2];
        for (int i = 0; i < total; i++) {
            SaoStarRecord r = stars.get(startIndex + i);
            batch.addRepeatedField(catalog, r.catalogNumber);
            batch.addRepeatedField(ra, r.raMilliarcSec);
            batch.addRepeatedField(dec, r.decMilliarcSec);
            batch.addRepeatedField(magnitudes, r.magnitudeCentimags);
            batch.addRepeatedField(raPm, r.raPmMicroArcsecPerYear);
            batch.addRepeatedField(decPm, r.decPmMicroArcsecPerYear);
            spectral[i * 2] = r.spectral0;
            spectral[i * 2 + 1] = r.spectral1;
        }
        batch.setField(BATCH_DESCRIPTOR.findFieldByName("spectral_types"), ByteString.copyFrom(spectral));
        return batch.build();
    }

    private static int estimateStarsPerBatch(List<SaoStarRecord> stars, int targetBytes) {
        int sample = Math.min(256, stars.size());
        byte[] payload = buildBatch(stars, 0, sample).toByteArray();
        int perStar = Math.max(1, payload.length / sample);
        return Math.max(1, targetBytes / perStar);
    }

    private static DescriptorProtos.FieldDescriptorProto uint32Field(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static DescriptorProtos.FieldDescriptorProto uint32FieldRepeated(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }

    private static DescriptorProtos.FieldDescriptorProto sint32FieldRepeated(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }

    private static DescriptorProtos.FieldDescriptorProto boolField(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static DescriptorProtos.FieldDescriptorProto bytesField(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static DescriptorProtos.FieldDescriptorProto messageField(String name, int number, String typeName, boolean repeated) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(repeated
                        ? DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED
                        : DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
