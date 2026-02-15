package io.github.hybledav.utils.sao;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates SAO payloads in row-oriented format (one message per star record).
 * This preserves the natural data layout without columnar transposition,
 * which would artificially improve compression ratios.
 * 
 * Use this for realistic benchmark comparisons against the OpenZL paper results.
 */
public final class SaoRowPayloadGenerator {
    private static final String PACKAGE = "openzl.integration";
    private static final String MESSAGE = "SaoRecord";

    private static final Descriptors.Descriptor RECORD_DESCRIPTOR;
    private static final Descriptors.Descriptor BATCH_DESCRIPTOR;
    private static final byte[] DESCRIPTOR_BYTES;

    static {
        try {
            // Single star record - row oriented, no columnar grouping
            DescriptorProtos.DescriptorProto record = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("SaoRecord")
                    .addField(uint32Field("catalog_number", 1))
                    .addField(sint32Field("ra_milliarc_seconds", 2))
                    .addField(sint32Field("dec_milliarc_seconds", 3))
                    .addField(bytesField("spectral_type", 4))  // 2 bytes per record
                    .addField(sint32Field("magnitude_centimags", 5))
                    .addField(sint32Field("ra_proper_motion", 6))
                    .addField(sint32Field("dec_proper_motion", 7))
                    .build();

            // Batch is just repeated records - row oriented
            DescriptorProtos.DescriptorProto batch = DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("SaoRecordBatch")
                    .addField(messageFieldRepeated("records", 1, "SaoRecord"))
                    .build();

            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("sao_row.proto")
                    .setPackage(PACKAGE)
                    .addMessageType(record)
                    .addMessageType(batch)
                    .build();

            Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]);
            RECORD_DESCRIPTOR = fd.findMessageTypeByName("SaoRecord");
            BATCH_DESCRIPTOR = fd.findMessageTypeByName("SaoRecordBatch");
            DESCRIPTOR_BYTES = DescriptorProtos.FileDescriptorSet.newBuilder()
                    .addFile(fd.toProto())
                    .build()
                    .toByteArray();
        } catch (Descriptors.DescriptorValidationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private SaoRowPayloadGenerator() {}

    public static String messageType() {
        return PACKAGE + ".SaoRecordBatch";
    }

    public static byte[] descriptorBytes() {
        return DESCRIPTOR_BYTES.clone();
    }

    /**
     * Creates payloads where each star is a separate nested message (row-oriented).
     * This does NOT transpose data into columnar format.
     */
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
            out.add(buildRowBatch(stars, start, starsPerBatch).toByteArray());
        }
        return out;
    }

    private static DynamicMessage buildRowBatch(List<SaoStarRecord> stars, int startIndex, int count) {
        int total = Math.min(count, stars.size() - startIndex);
        if (total <= 0) {
            total = Math.min(count, stars.size());
            startIndex = 0;
        }

        DynamicMessage.Builder batch = DynamicMessage.newBuilder(BATCH_DESCRIPTOR);
        Descriptors.FieldDescriptor recordsField = BATCH_DESCRIPTOR.findFieldByName("records");

        for (int i = 0; i < total; i++) {
            SaoStarRecord r = stars.get(startIndex + i);
            
            DynamicMessage.Builder record = DynamicMessage.newBuilder(RECORD_DESCRIPTOR);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("catalog_number"), r.catalogNumber);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("ra_milliarc_seconds"), r.raMilliarcSec);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("dec_milliarc_seconds"), r.decMilliarcSec);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("spectral_type"), 
                    ByteString.copyFrom(new byte[]{r.spectral0, r.spectral1}));
            record.setField(RECORD_DESCRIPTOR.findFieldByName("magnitude_centimags"), r.magnitudeCentimags);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("ra_proper_motion"), r.raPmMicroArcsecPerYear);
            record.setField(RECORD_DESCRIPTOR.findFieldByName("dec_proper_motion"), r.decPmMicroArcsecPerYear);
            
            batch.addRepeatedField(recordsField, record.build());
        }

        return batch.build();
    }

    private static int estimateStarsPerBatch(List<SaoStarRecord> stars, int targetBytes) {
        int sample = Math.min(256, stars.size());
        byte[] payload = buildRowBatch(stars, 0, sample).toByteArray();
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

    private static DescriptorProtos.FieldDescriptorProto sint32Field(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32)
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

    private static DescriptorProtos.FieldDescriptorProto messageFieldRepeated(String name, int number, String typeName) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }
}
