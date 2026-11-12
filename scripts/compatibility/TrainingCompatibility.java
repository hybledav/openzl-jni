import io.github.hybledav.*;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Run only with 0.3.0-1; the old reader probe consumes the format-24 results. */
class TrainingCompatibility {
    private static void check(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Training compatibility round trip failed");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        byte[] payload = "Persistent OpenZL trained frame\n".repeat(1024).getBytes(StandardCharsets.UTF_8);
        if (args[0].equals("read-old")) {
            try (OpenZLCompressor compressor = new OpenZLCompressor()) {
                compressor.configureTrained(new TrainedCompressor("serial",
                        Files.readAllBytes(directory.resolve("old-trained.zc")), new byte[0]));
                check(payload, compressor.decompress(compressor.compress(payload)));
                check(payload, compressor.decompress(Files.readAllBytes(directory.resolve("old-trained.zl"))));
            }
            System.out.println("0.3.0 reloaded a graph trained by the old native release");
            return;
        }
        if (args[0].equals("read")) {
            TrainedCompressor saved = new TrainedCompressor("zstd",
                    Files.readAllBytes(directory.resolve("dictionary.zc")),
                    Files.readAllBytes(directory.resolve("dictionary.zd")));
            byte[] expected = Files.readAllBytes(directory.resolve("dictionary.input"));
            try (OpenZLCompressor compressor = new OpenZLCompressor(); OpenZLCompressor decoder = new OpenZLCompressor()) {
                compressor.configureTrained(saved);
                decoder.setDictionaryBundle(saved.dictionaryBundle());
                check(expected, decoder.decompress(Files.readAllBytes(directory.resolve("dictionary.zl"))));
                check(expected, decoder.decompress(compressor.compress(expected)));
            }
            System.out.println("Saved dictionary graph and bundle reloaded in a fresh JVM");
            return;
        }

        TrainOptions options = new TrainOptions();
        options.formatVersion = 24;
        options.maxNumCandidates = 1;
        byte[][] trained = OpenZLCompressor.train("serial", new byte[][] {payload, payload, payload}, options);
        Files.write(directory.resolve("trained24.input"), payload);
        Files.write(directory.resolve("trained24.zl"), OpenZLCompressor.compressWithSerializedNative("serial", trained[0], payload));

        var field = DescriptorProtos.FieldDescriptorProto.newBuilder().setName("numbers").setNumber(1)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED);
        var message = DescriptorProtos.DescriptorProto.newBuilder().setName("V3Frame").addField(field);
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("compatibility.proto")
                .setPackage("jni.compat").addMessageType(message).build();
        var descriptor = Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0])
                .findMessageTypeByName("V3Frame");
        byte[] schema = DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build().toByteArray();
        OpenZLProtobuf.registerSchema(schema);
        var builder = DynamicMessage.newBuilder(descriptor);
        for (int i = 0; i < 2048; i++) builder.addRepeatedField(descriptor.findFieldByNumber(1), i % 41 == 0 ? i : 0);
        byte[] proto = builder.build().toByteArray();
        byte[][] protoTrained = OpenZLProtobuf.train(new byte[][] {proto, proto, proto},
                OpenZLProtobuf.Protocol.PROTO, options, descriptor.getFullName());
        Files.write(directory.resolve("protobuf.schema"), schema);
        Files.write(directory.resolve("protobuf24.input"), proto);
        Files.write(directory.resolve("protobuf24.zl"), OpenZLProtobuf.convert(proto, OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL, protoTrained[0], descriptor.getFullName()));

        byte[][] samples = new byte[128][];
        for (int i = 0; i < samples.length; i++) samples[i] = ("{\"customer\":\"account-" + i
                + "\",\"status\":\"accepted\",\"region\":\"eu-central\",\"currency\":\"EUR\","
                + "\"description\":\"Persist this trained graph together with its dictionary bundle\"}\n")
                .getBytes(StandardCharsets.UTF_8);
        options.formatVersion = 0;
        options.dictionaryTraining = true;
        TrainedCompressor candidate = OpenZLCompressor.trainCandidates("zstd", samples, options)[0];
        if (!candidate.hasDictionaryBundle()) throw new AssertionError("Missing dictionary bundle");
        Files.write(directory.resolve("dictionary.zc"), candidate.serializedCompressor());
        Files.write(directory.resolve("dictionary.zd"), candidate.dictionaryBundle());
        Files.write(directory.resolve("dictionary.input"), samples[0]);
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureTrained(candidate);
            Files.write(directory.resolve("dictionary.zl"), compressor.compress(samples[0]));
        }
        System.out.println("Saved trained format-24 byte/Protobuf frames and a dictionary candidate");
    }
}
