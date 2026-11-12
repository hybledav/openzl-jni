import io.github.hybledav.OpenZLCompressor;
import io.github.hybledav.OpenZLGraph;
import io.github.hybledav.OpenZLProtobuf;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/** Run in separate JVMs so each release loads its own native library. */
class FrameCompatibility {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        boolean write = args[0].equals("write");
        boolean current = args[2].equals("current");
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        Random random = new Random(310024);
        int[] sizes = {0, 1, 2, 7, 8, 9, 31, 256, 1024, 65536};
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            if (current && write) {
                byte[] defaultFrame = compressor.compress(new byte[1024]);
                check(compressor.inspect(defaultFrame).formatVersion() == 27, "Expected default format 27");
                Files.write(directory.resolve("default27.zl"), defaultFrame);
                // This API also exists in the previous release.
                compressor.setParameter(4, 24);
            }
            for (int size : sizes) {
                for (int pattern = 0; pattern < 3; pattern++) {
                    byte[] input = new byte[size];
                    if (pattern == 0) random.nextBytes(input);
                    if (pattern == 1) {
                        for (int i = 0; i < size; i++) input[i] = (byte) (i % 17);
                    }
                    for (boolean direct : new boolean[] {false, true}) {
                        Path file = directory.resolve(size + "-" + pattern + "-" + direct + ".zl");
                        if (write) {
                            byte[] frame;
                            if (direct) {
                                ByteBuffer source = ByteBuffer.allocateDirect(size);
                                source.put(input).flip();
                                ByteBuffer destination = ByteBuffer.allocateDirect(
                                        Math.toIntExact(OpenZLCompressor.maxCompressedSize(size)));
                                compressor.compress(source, destination);
                                destination.flip();
                                frame = new byte[destination.remaining()];
                                destination.get(frame);
                            } else {
                                frame = compressor.compress(input);
                            }
                            check(compressor.inspect(frame).formatVersion() == 24, "Expected format 24");
                            Files.write(file, frame);
                        } else {
                            byte[] frame = Files.readAllBytes(file);
                            check(Arrays.equals(input, compressor.decompress(frame)), "Array mismatch: " + file);
                            ByteBuffer source = ByteBuffer.allocateDirect(frame.length);
                            source.put(frame).flip();
                            ByteBuffer destination = ByteBuffer.allocateDirect(size);
                            compressor.decompress(source, destination);
                            byte[] restored = new byte[destination.position()];
                            destination.flip().get(restored);
                            check(Arrays.equals(input, restored), "Direct mismatch: " + file);
                        }
                    }
                }
            }
            Path newerFrame = directory.resolve("default27.zl");
            if (!write && !current && Files.exists(newerFrame)) {
                boolean rejected = false;
                try {
                    // The old JNI reports unsupported frames with a null result.
                    rejected = compressor.decompress(Files.readAllBytes(newerFrame)) == null;
                } catch (RuntimeException expected) {
                    rejected = true;
                }
                check(rejected, "The previous native release should reject format 27");
            }
        }
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            if (current && write) {
                compressor.setParameter(4, 24);
                compressor.setParameter(2, 7);
            }
            for (int pattern = 0; pattern < 4; pattern++) {
                long[] values = new long[4096];
                for (int i = 0; i < values.length; i++) {
                    values[i] = switch (pattern) {
                        case 0 -> random.nextLong();
                        case 1 -> i % 31 == 0 ? Long.MIN_VALUE + i : 0;
                        case 2 -> Long.MAX_VALUE - i;
                        default -> i * 3L - 2000;
                    };
                }
                Path file = directory.resolve("longs-" + pattern + ".zl");
                if (write) {
                    byte[] frame = compressor.compressLongs(values);
                    check(compressor.inspect(frame).formatVersion() == 24, "Expected numeric format 24");
                    Files.write(file, frame);
                } else {
                    check(Arrays.equals(values, compressor.decompressLongs(Files.readAllBytes(file))),
                            "Numeric mismatch: " + file);
                }
            }
        }
        if (write && !current) {
            byte[] payload = "Persistent OpenZL trained frame\n".repeat(1024).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] graph = OpenZLCompressor.train("serial", new byte[][] {payload, payload, payload}, null)[0];
            Files.write(directory.resolve("old-trained.zc"), graph);
            Files.write(directory.resolve("old-trained.zl"), OpenZLCompressor.compressWithSerializedNative("serial", graph, payload));
        }
        if (!write && !current && Files.exists(directory.resolve("trained24.zl"))) {
            try (OpenZLCompressor compressor = new OpenZLCompressor()) {
                byte[] trainedFrame = Files.readAllBytes(directory.resolve("trained24.zl"));
                check(compressor.inspect(trainedFrame).formatVersion() == 24, "Expected trained format 24");
                check(Arrays.equals(Files.readAllBytes(directory.resolve("trained24.input")),
                        compressor.decompress(trainedFrame)), "Old reader cannot read the trained frame");
                byte[] protoFrame = Files.readAllBytes(directory.resolve("protobuf24.zl"));
                check(compressor.inspect(protoFrame).formatVersion() == 24, "Expected Protobuf format 24");
                OpenZLProtobuf.registerSchema(Files.readAllBytes(directory.resolve("protobuf.schema")));
                byte[] proto = OpenZLProtobuf.convert(protoFrame, OpenZLProtobuf.Protocol.ZL,
                        OpenZLProtobuf.Protocol.PROTO, "jni.compat.V3Frame");
                check(Arrays.equals(Files.readAllBytes(directory.resolve("protobuf24.input")), proto),
                        "Old reader cannot read the trained Protobuf frame");
            }
            System.out.println("Old reader decoded newly trained byte and Protobuf format-24 frames");
        }
        System.out.println(args[2] + " " + args[0] + ": 64 compatibility frames passed");
    }
}
