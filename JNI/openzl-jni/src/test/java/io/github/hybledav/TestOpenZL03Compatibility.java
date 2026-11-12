package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestOpenZL03Compatibility {
    private static final int OPENZL_02_FORMAT = 24;
    private static final int OPENZL_03_FORMAT = 27;
    private static final byte[] PAYLOAD =
            "OpenZL JNI frame compatibility\n".repeat(4096).getBytes(StandardCharsets.UTF_8);

    // Produced by the published JNI 0.2.1 Linux artifact, which embeds OpenZL 0.2.0.
    private static final byte[] OLD_FRAME = Base64.getDecoder().decode(
            "2KWx1wMBgeAHUgIBABYAAAAAMQGgAPABAEUBAPhPcGVuWkwgSk5JIGZyYW1lIGNvbXBhdGliaWxpdHkKAQB7v4tcOQGYSA+mx0YN3wA=");

    @Test
    void readsFramesFromOpenZL02() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertEquals(OPENZL_02_FORMAT, compressor.inspect(OLD_FRAME).formatVersion());
            assertArrayEquals(PAYLOAD, compressor.decompress(OLD_FRAME));
        }
    }

    @Test
    void formatVersionIsHonoredByArrayAndDirectCompression() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertEquals(OPENZL_03_FORMAT, compressor.inspect(compressor.compress(PAYLOAD)).formatVersion());
            compressor.setFormatVersion(OPENZL_02_FORMAT);
            compressor.configureProfile(OpenZLProfile.SERIAL);
            compressor.reset();

            assertEquals(OPENZL_02_FORMAT, compressor.getFormatVersion());
            byte[] frame = compressor.compress(PAYLOAD);
            assertEquals(OPENZL_02_FORMAT, compressor.inspect(frame).formatVersion());
            assertArrayEquals(PAYLOAD, compressor.decompress(frame));

            ByteBuffer input = ByteBuffer.allocateDirect(PAYLOAD.length);
            input.put(PAYLOAD).flip();
            ByteBuffer output = ByteBuffer.allocateDirect(Math.toIntExact(OpenZLCompressor.maxCompressedSize(PAYLOAD.length)));
            compressor.compress(input, output);
            output.flip();
            assertEquals(OPENZL_02_FORMAT, compressor.inspect(output).formatVersion());
            ByteBuffer restored = ByteBuffer.allocateDirect(PAYLOAD.length);
            compressor.decompress(output, restored);
            byte[] bytes = new byte[restored.position()];
            restored.flip().get(bytes);
            assertArrayEquals(PAYLOAD, bytes);
        }
    }

    @Test
    void invalidFormatVersionPreservesTheSettingAndZeroRestoresDefault() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setFormatVersion(OPENZL_02_FORMAT);
            assertThrows(IllegalArgumentException.class, () -> compressor.setFormatVersion(Integer.MAX_VALUE));
            assertEquals(OPENZL_02_FORMAT, compressor.getFormatVersion());
            compressor.setFormatVersion(0);
            assertEquals(OPENZL_03_FORMAT, compressor.getFormatVersion());
        }
    }

    @Test
    void pooledCompressorStartsWithDefaultFormatAndLevel() {
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.setFormatVersion(OPENZL_02_FORMAT);
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_12);
        }
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            assertEquals(OPENZL_03_FORMAT, compressor.getFormatVersion());
            assertEquals(OpenZLCompressionLevel.LEVEL_6, compressor.getCompressionLevel());
        }
    }

    @Test
    void numericTransformerRoundTripsAndFormatCanBePinned() {
        int[] values = new int[8192];
        for (int i = 0; i < values.length; i += 41) {
            values[i] = i * 3 - 1000;
        }
        try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            byte[] levelSix = compressor.compressInts(values);
            compressor.setCompressionLevel(OpenZLCompressionLevel.LEVEL_7);
            byte[] levelSeven = compressor.compressInts(values);
            assertFalse(Arrays.equals(levelSix, levelSeven), "Level 7 should change the graph on this stream");
            assertArrayEquals(values, compressor.decompressInts(levelSeven));

            compressor.setFormatVersion(OPENZL_02_FORMAT);
            byte[] compatible = compressor.compressInts(values);
            assertEquals(OPENZL_02_FORMAT, compressor.inspect(compatible).formatVersion());
            assertArrayEquals(values, compressor.decompressInts(compatible));
        }
    }

    @Test
    void bigEndianProfilesPreserveTheOriginalBytes() {
        ByteBuffer integers = ByteBuffer.allocate(4096);
        for (int i = 0; integers.hasRemaining(); i++) {
            integers.putInt(i % 2 == 0 ? -i : i);
        }
        for (OpenZLProfile profile : new OpenZLProfile[] {
                OpenZLProfile.BIG_ENDIAN_SIGNED_16, OpenZLProfile.BIG_ENDIAN_UNSIGNED_16,
                OpenZLProfile.BIG_ENDIAN_SIGNED_32, OpenZLProfile.BIG_ENDIAN_UNSIGNED_32,
                OpenZLProfile.BIG_ENDIAN_SIGNED_64, OpenZLProfile.BIG_ENDIAN_UNSIGNED_64
        }) {
            assertSame(profile, OpenZLProfile.fromName(profile.profileName()));
            try (OpenZLCompressor compressor = new OpenZLCompressor()) {
                compressor.configureProfile(profile);
                assertArrayEquals(integers.array(), compressor.decompress(compressor.compress(integers.array())));
            }
        }
    }

    @Test
    void sddl2ProfileSupportsBetweenAndInstantParse(@TempDir Path directory) throws Exception {
        Path description = directory.resolve("packet.sddl");
        Files.writeString(description,
                "record Packet() { value: Int32LE } @instant_parse\n" +
                "packet: Packet()\n" +
                "expect between(-3, packet.value, 3)\n");

        byte[] data = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(-2).array();
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.SDDL2, Map.of("TBD", description.toString()));
            assertArrayEquals(data, compressor.decompress(compressor.compress(data)));

            Files.writeString(description, "record Packet() { size: UInt16LE, payload: Bytes(size) } @instant_parse\n"
                    + "packet: Packet()\n");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> compressor.configureProfile(OpenZLProfile.SDDL2, Map.of("TBD", description.toString())));
            assertTrue(error.getMessage().contains("not instant-parse"), error::getMessage);
        }
    }

    @Test
    void lzTrainingProducesAReusableCompressor() {
        byte[][] samples = {
                PAYLOAD,
                "OpenZL LZ trainer sample 2\n".repeat(1024).getBytes(StandardCharsets.UTF_8),
                "OpenZL LZ trainer sample 3\n".repeat(1024).getBytes(StandardCharsets.UTF_8)
        };
        String profile = OpenZLProfile.LZ.profileName();
        TrainOptions options = new TrainOptions();
        options.paretoFrontier = true;
        byte[][] candidates = OpenZLCompressor.train(profile, samples, options);
        assertTrue(candidates.length > 0);

        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureProfile(OpenZLProfile.LZ);
            assertArrayEquals(PAYLOAD, compressor.decompress(compressor.compress(PAYLOAD)));
            for (byte[] candidate : candidates) {
                byte[] frame = OpenZLCompressor.compressWithSerializedNative(profile, candidate, PAYLOAD);
                assertArrayEquals(PAYLOAD, compressor.decompress(frame));
            }
        }
    }

    @Test
    void saoProfileHonorsTheRequestedLevelAndRoundTripsItsTransformerBranch() {
        ByteBuffer data = ByteBuffer.allocate(28 + 28 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        data.position(28);
        for (int i = 0; i < 1024; i++) {
            data.putDouble(i * 0.001).putDouble(-i * 0.002).putShort((short) 0x4142)
                    .putShort((short) (i - 500)).putFloat(i * 0.0001f).putFloat(-i * 0.0002f);
        }
        for (OpenZLCompressionLevel level : new OpenZLCompressionLevel[] {
                OpenZLCompressionLevel.LEVEL_6, OpenZLCompressionLevel.LEVEL_7
        }) {
            try (OpenZLCompressor compressor = new OpenZLCompressor()) {
                compressor.setCompressionLevel(level);
                compressor.configureProfile(OpenZLProfile.SAO);
                assertEquals(level, compressor.getCompressionLevel());
                assertArrayEquals(data.array(), compressor.decompress(compressor.compress(data.array())));
            }
        }
    }
}
