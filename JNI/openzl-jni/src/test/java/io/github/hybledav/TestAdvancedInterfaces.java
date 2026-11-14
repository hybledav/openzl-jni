package io.github.hybledav;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestAdvancedInterfaces {
    private static byte[] payload() {
        byte[] bytes = new byte[32768];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)(i % 37);
        return bytes;
    }
    @Test void mixedStreamsPreserveOrderTypesBitsAndOwnership() {
        byte[] serial = {0, 1, -1, 0};
        byte[] fields = {1,2,3,4,5,6};
        byte[] strings = {0,-1,2,3,4};
        int[] lengths = {0,2,0,3,0};
        int[] ints = {Integer.MIN_VALUE, 0, 42, Integer.MAX_VALUE};
        long[] longs = {Long.MIN_VALUE, 0, Long.MAX_VALUE};
        float[] floats = {Float.intBitsToFloat(0x7fc00007), -0f, Float.NEGATIVE_INFINITY};
        double[] doubles = {Double.longBitsToDouble(0x7ff8000000000007L), -0d, Double.POSITIVE_INFINITY};
        TypedInput[] input = {TypedInput.serial(serial), TypedInput.structs(fields, 3),
                TypedInput.strings(strings, lengths), TypedInput.ints(ints), TypedInput.longs(longs),
                TypedInput.floats(floats), TypedInput.doubles(doubles),
                TypedInput.numeric(new byte[]{0,-1},1), TypedInput.numeric(new byte[]{1,2,3,4},2)};
        serial[0] = 99; fields[0] = 99; strings[0] = 99; lengths[1] = 99; ints[1] = 99;
        TypedOutput[] out;
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.GENERIC)) {
            out = c.decompressOutputs(c.compressInputs(input));
            assertEquals(9, out.length);
            assertEquals(0, c.getCompressionWarnings().length);
            assertEquals(0, c.getDecompressionWarnings().length);
        }
        assertEquals(OpenZLType.SERIAL, out[0].type());
        assertArrayEquals(new byte[]{0,1,-1,0}, out[0].bytes());
        assertEquals(1, out[0].elementWidth()); assertEquals(4, out[0].elementCount());
        assertEquals(OpenZLType.STRUCT, out[1].type());
        assertEquals(3, out[1].elementWidth()); assertEquals(2, out[1].elementCount());
        assertArrayEquals(new byte[]{1,2,3,4,5,6}, out[1].bytes());
        assertEquals(OpenZLType.STRING, out[2].type());
        assertArrayEquals(new byte[]{0,-1,2,3,4}, out[2].bytes());
        assertEquals(5, out[2].elementCount());
        assertArrayEquals(new int[]{0,2,0,3,0}, out[2].stringLengths());
        out[2].stringLengths()[1] = 999; out[0].bytes()[0] = 99;
        assertEquals(2, out[2].stringLengths()[1]); assertEquals(0, out[0].bytes()[0]);
        assertEquals(OpenZLType.NUMERIC, out[3].type());
        assertArrayEquals(new int[]{Integer.MIN_VALUE,0,42,Integer.MAX_VALUE}, out[3].ints());
        assertArrayEquals(longs,out[4].longs());
        assertEquals(0x7fc00007, Float.floatToRawIntBits(out[5].floats()[0]));
        assertEquals(0x80000000, Float.floatToRawIntBits(out[5].floats()[1]));
        assertEquals(Float.NEGATIVE_INFINITY,out[5].floats()[2]);
        assertEquals(0x7ff8000000000007L,Double.doubleToRawLongBits(out[6].doubles()[0]));
        assertEquals(0x8000000000000000L,Double.doubleToRawLongBits(out[6].doubles()[1]));
        assertEquals(Double.POSITIVE_INFINITY,out[6].doubles()[2]);
        assertArrayEquals(new byte[]{0,-1},out[7].bytes());
        assertArrayEquals(new byte[]{1,2,3,4},out[8].bytes());
        assertThrows(IllegalStateException.class,out[0]::ints);
        assertThrows(IllegalStateException.class,out[3]::longs);
        assertThrows(IllegalStateException.class,out[0]::floats);
        assertThrows(IllegalStateException.class,out[0]::doubles);
        assertThrows(IllegalStateException.class,out[0]::stringLengths);
    }
    @Test void bufferSlicesAndEmptyStreams() {
        ByteBuffer direct = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        direct.position(1); direct.putInt(42).putInt(-7); direct.limit(9).position(1);
        ByteBuffer readOnly = direct.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        TypedInput numbers = TypedInput.numeric(readOnly,4);
        assertEquals(1,readOnly.position()); assertEquals(9,readOnly.limit());
        direct.putInt(1,100);
        ByteBuffer heap = ByteBuffer.wrap(new byte[]{99,1,2,3,4,99});
        heap.position(1).limit(5);
        try(OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.GENERIC)) {
            byte[] frame=c.compressInputs(numbers,TypedInput.serial(heap),TypedInput.structs(heap,2),
                    TypedInput.strings(heap,new int[]{1,3}),TypedInput.serial(new byte[0]),
                    TypedInput.numeric(new byte[0],8),TypedInput.structs(new byte[0],3),
                    TypedInput.strings(new byte[0],new int[]{0,0}),TypedInput.strings(new byte[0],new int[0]));
            ByteBuffer buffer=ByteBuffer.allocateDirect(frame.length+4);
            buffer.position(3);buffer.put(frame);buffer.flip().position(3);
            TypedOutput[] out=c.decompressOutputs(buffer.asReadOnlyBuffer());
            assertArrayEquals(new int[]{42,-7},out[0].ints());
            for(int i=1;i<4;i++) assertArrayEquals(new byte[]{1,2,3,4},out[i].bytes());
            assertEquals(0,out[4].elementCount()); assertEquals(0,out[5].elementCount());
            assertEquals(0,out[6].elementCount()); assertEquals(2,out[7].elementCount());
            assertEquals(0,out[8].elementCount());
            assertEquals(3,buffer.position());assertEquals(frame.length+3,buffer.limit());
            assertEquals(1,heap.position());assertEquals(5,heap.limit());
            // The new typed reader also reads frames from the original APIs.
            assertArrayEquals(payload(),c.decompressOutputs(c.compress(payload()))[0].bytes());
        }
    }
    @Test void invalidInputsFailBeforeNativeAndClosedObjectsRejectNewOperations() {
        assertThrows(NullPointerException.class,()->TypedInput.serial((byte[])null));
        assertThrows(NullPointerException.class,()->TypedInput.serial((ByteBuffer)null));
        assertThrows(NullPointerException.class,()->TypedInput.numeric((ByteBuffer)null,4));
        for(int w:new int[]{-1,0,3,5,16}) assertThrows(IllegalArgumentException.class,()->TypedInput.numeric(new byte[16],w));
        assertThrows(IllegalArgumentException.class,()->TypedInput.structs(new byte[4],0));
        assertThrows(IllegalArgumentException.class,()->TypedInput.structs(new byte[4],-1));
        assertThrows(IllegalArgumentException.class,()->TypedInput.structs(new byte[4],3));
        assertThrows(IllegalArgumentException.class,()->TypedInput.numeric(new byte[3],2));
        ByteOrder other = ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN;
        assertThrows(IllegalArgumentException.class,()->TypedInput.numeric(ByteBuffer.allocate(4).order(other),4));
        assertThrows(NullPointerException.class,()->TypedInput.strings((byte[])null,new int[0]));
        assertThrows(NullPointerException.class,()->TypedInput.strings(new byte[0],null));
        assertThrows(IllegalArgumentException.class,()->TypedInput.strings(new byte[0],new int[]{-1}));
        assertThrows(IllegalArgumentException.class,()->TypedInput.strings(new byte[0],new int[]{1}));
        assertThrows(IllegalArgumentException.class,()->TypedInput.strings(new byte[0],new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,2}));
        try(OpenZLCompressor c=new OpenZLCompressor(OpenZLGraph.GENERIC)) {
            assertThrows(NullPointerException.class,()->c.compressInputs((TypedInput[])null));
            assertThrows(IllegalArgumentException.class,()->c.compressInputs());
            assertThrows(NullPointerException.class,()->c.compressInputs(TypedInput.serial(new byte[0]),null));
            assertThrows(NullPointerException.class,()->c.decompressOutputs((byte[])null));
            assertThrows(NullPointerException.class,()->c.decompressOutputs((ByteBuffer)null));
            assertThrows(NullPointerException.class,()->c.setCompressionOptions(null));
            assertThrows(NullPointerException.class,()->c.setDecompressionOptions(null));
            assertThrows(NullPointerException.class,()->c.configureLz(null));
            c.close();
            assertThrows(IllegalStateException.class,()->c.compressInputs(TypedInput.serial(new byte[0])));
            assertThrows(IllegalStateException.class,()->c.decompressOutputs(new byte[0]));
            assertThrows(IllegalStateException.class,()->c.setCompressionOptions(CompressionOptions.builder().build()));
            assertThrows(IllegalStateException.class,()->c.setDecompressionOptions(DecompressionOptions.builder().build()));
            assertThrows(IllegalStateException.class,()->c.configureLz(LzOptions.builder().build()));
            assertThrows(IllegalStateException.class,()->c.getCompressionWarnings());
            assertThrows(IllegalStateException.class,()->c.getDecompressionWarnings());
        }
    }
    @Test void nativeFailuresWarningsAndRecovery() {
        try(OpenZLCompressor c=new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            c.setCompressionOptions(CompressionOptions.builder().minStreamSize(-1).build());
            OpenZLException error=assertThrows(OpenZLException.class,()->c.compressInputs(TypedInput.serial(payload())));
            assertEquals("compressInputs",error.operation());assertTrue(error.errorCode()>0);
            assertFalse(error.nativeContext().isEmpty());assertTrue(error.getMessage().contains("compressInputs"));
            c.setCompressionOptions(CompressionOptions.builder().permissive(ParameterSwitch.ENABLED).minStreamSize(-1).build());
            byte[] frame=c.compressInputs(TypedInput.serial(payload()));
            OpenZLWarning[] warnings=c.getCompressionWarnings();
            assertTrue(warnings.length>0);
            assertTrue(warnings[0].errorCode()>0);assertFalse(warnings[0].message().isEmpty());assertFalse(warnings[0].nativeContext().isEmpty());
            assertArrayEquals(payload(),c.decompressOutputs(frame)[0].bytes());
            String warning=warnings[0].nativeContext();
            c.reset();
            assertEquals(warning,warnings[0].nativeContext());
            assertEquals(0,c.getCompressionWarnings().length);
            assertEquals(0,c.getDecompressionWarnings().length);
            assertThrows(OpenZLException.class,()->c.compressInputs(TypedInput.serial(payload())));
            for(byte[] bad:new byte[][]{new byte[0],new byte[]{1,2,3},Arrays.copyOf(frame,frame.length/2)}) {
                OpenZLException e=assertThrows(OpenZLException.class,()->c.decompressOutputs(bad));
                assertEquals("decompressOutputs",e.operation()); assertNotNull(e.nativeContext());
            }
            assertArrayEquals(payload(),c.decompressOutputs(frame)[0].bytes());
            c.setCompressionOptions(CompressionOptions.builder().permissive(ParameterSwitch.ENABLED).minStreamSize(-1).build());
            assertNotNull(c.compress(payload())); // legacy path also exposes warnings
            assertTrue(c.getCompressionWarnings().length > 0);
        }
        try (OpenZLCompressor fresh = new OpenZLCompressor()) {
            assertEquals(0, fresh.getCompressionWarnings().length);
            assertEquals(0, fresh.getDecompressionWarnings().length);
        }
    }
    @Test void checksumSettingsDetectCorruptionAndResetAndPoolRestoreChecks() {
        byte[] data=payload();
        byte[] frame;
        try(OpenZLCompressor c=new OpenZLCompressor(OpenZLGraph.STORE)) {
            c.setCompressionOptions(CompressionOptions.builder().compressedChecksum(ParameterSwitch.ENABLED)
                    .contentChecksum(ParameterSwitch.ENABLED).storeOnExpansion(ParameterSwitch.DISABLED).build());
            frame=c.compressInputs(TypedInput.serial(data));
            byte[] corrupted=frame.clone();
            // STORE retains this distinctive payload verbatim. Flip a content byte, not framing.
            int start=indexOf(corrupted,Arrays.copyOf(data,64));assertTrue(start>=0);
            corrupted[start+27]^=1;
            assertThrows(OpenZLException.class,()->c.decompressOutputs(corrupted));
            c.setDecompressionOptions(DecompressionOptions.builder().checkCompressedChecksum(ParameterSwitch.DISABLED)
                    .checkContentChecksum(ParameterSwitch.DISABLED).codecFusion(ParameterSwitch.DISABLED).build());
            assertFalse(Arrays.equals(data,c.decompressOutputs(corrupted)[0].bytes()));
            c.reset();
            assertThrows(OpenZLException.class,()->c.decompressOutputs(corrupted));
            c.setDecompressionOptions(DecompressionOptions.builder().checkCompressedChecksum(ParameterSwitch.DISABLED)
                    .checkContentChecksum(ParameterSwitch.DISABLED).build());
            // New pooled contexts must not retain the disabled checksum verification.
            c.close();
            try(OpenZLCompressor fresh=new OpenZLCompressor()) {
                assertThrows(OpenZLException.class,()->fresh.decompressOutputs(corrupted));
                assertArrayEquals(data,fresh.decompressOutputs(frame)[0].bytes());
            }
        }
    }
    private static int indexOf(byte[] data,byte[] pattern) {
        outer:for(int i=0;i<=data.length-pattern.length;i++) {
            for(int k=0;k<pattern.length;k++) if(data[i+k]!=pattern[k]) continue outer;
            return i;
        }
        return -1;
    }
    @Test void fusionOnOffAndLzStrategiesAreLosslessAndReusable() {
        try(OpenZLCompressor c=new OpenZLCompressor()) {
            for(LzOptions.Strategy strategy:LzOptions.Strategy.values()) {
                c.configureLz(LzOptions.builder().strategy(strategy).windowLog(16).hashLog1(12).hashLog2(12)
                        .hashLength(4).searchLog(2).acceleration(1).compressionLevel(3)
                        .entropyMinGainBytes(0).entropyMinGainPercent(0).build());
                byte[] frame=c.compressInputs(TypedInput.serial(payload()));
                for(ParameterSwitch fusion:ParameterSwitch.values()) {
                    c.setDecompressionOptions(DecompressionOptions.builder().codecFusion(fusion).build());
                    assertArrayEquals(payload(),c.decompressOutputs(frame)[0].bytes());
                }
                c.reset();
                assertArrayEquals(payload(),c.decompressOutputs(c.compressInputs(TypedInput.serial(payload())))[0].bytes());
            }
            c.configureLz(LzOptions.builder().build());
            assertArrayEquals(payload(),c.decompressOutputs(c.compressInputs(TypedInput.serial(payload())))[0].bytes());
            // Parameterized graphs survive serialization and reconstruction in a separate context.
            TrainedCompressor graph=new TrainedCompressor("serial",c.serializeCompressor(),new byte[0]);
            try(OpenZLCompressor reader=new OpenZLCompressor()) {
                reader.configureTrained(graph);
                assertArrayEquals(payload(),reader.decompressOutputs(reader.compressInputs(TypedInput.serial(payload())))[0].bytes());
            }
        }
    }
    @Test void lzWindowControlChangesMatchingRange() {
        byte[] block = new byte[4096];
        new Random(772).nextBytes(block);
        byte[] repeated = new byte[65536];
        for (int i = 0; i < repeated.length; i += block.length) System.arraycopy(block, 0, repeated, i, block.length);
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            c.configureLz(LzOptions.builder().windowLog(10).compressionLevel(3).build());
            byte[] small = c.compressInputs(TypedInput.serial(repeated));
            c.configureLz(LzOptions.builder().windowLog(16).compressionLevel(3).build());
            byte[] wide = c.compressInputs(TypedInput.serial(repeated));
            assertTrue(wide.length < small.length / 4, "Larger window must find the repeated distant blocks");
            assertArrayEquals(repeated, c.decompressOutputs(small)[0].bytes());
            assertArrayEquals(repeated, c.decompressOutputs(wide)[0].bytes());
        }
    }

    @Test void malformedMixedFramesNeverPoisonTheContext() {
        Random random = new Random(993);
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.GENERIC)) {
            byte[] frame = c.compressInputs(TypedInput.serial(payload()), TypedInput.ints(new int[]{1,2,3}),
                    TypedInput.strings(new byte[]{0,1,2}, new int[]{0,1,2}));
            for (int i = 0; i < frame.length; i++) {
                final byte[] truncated = Arrays.copyOf(frame, i);
                assertThrows(OpenZLException.class, () -> c.decompressOutputs(truncated));
            }
            for (int i = 0; i < 300; i++) {
                byte[] mutated = frame.clone();
                int at = random.nextInt(frame.length);
                mutated[at] ^= (byte)(1 << random.nextInt(8));
                try {
                    TypedOutput[] out = c.decompressOutputs(mutated);
                    assertEquals(3, out.length);
                    assertArrayEquals(payload(), out[0].bytes());
                    assertArrayEquals(new int[]{1,2,3}, out[1].ints());
                    assertArrayEquals(new byte[]{0,1,2}, out[2].bytes());
                    assertArrayEquals(new int[]{0,1,2}, out[2].stringLengths());
                } catch (OpenZLException expected) {
                    assertEquals("decompressOutputs", expected.operation());
                }
                assertArrayEquals(payload(), c.decompressOutputs(frame)[0].bytes());
            }
        }
        try (OpenZLCompressor c = new OpenZLCompressor()) {
            assertThrows(OpenZLException.class, () -> c.compressInputs(TypedInput.serial(payload()), TypedInput.serial(payload())));
            assertArrayEquals(payload(), c.decompressOutputs(c.compressInputs(TypedInput.serial(payload())))[0].bytes());
        }
    }

    @Test void deterministicRandomMixedStreamsAcrossResetsAndThreads() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(4);
        try {
            Future<?>[] tasks=new Future<?>[4];
            for(int worker=0;worker<4;worker++) {
                final int seed=worker;
                tasks[worker]=workers.submit(()->{
                    Random random=new Random(735+seed);
                    try(OpenZLCompressor c=new OpenZLCompressor(OpenZLGraph.GENERIC)) {
                        for(int round=0;round<100;round++) {
                            byte[] bytes=new byte[random.nextInt(4096)]; random.nextBytes(bytes);
                            int[] nums=new int[random.nextInt(1024)];for(int i=0;i<nums.length;i++) nums[i]=random.nextInt();
                            byte[] frame=c.compressInputs(TypedInput.serial(bytes),TypedInput.ints(nums),
                                    TypedInput.strings(bytes,new int[]{0,bytes.length,0}));
                            TypedOutput[] out=c.decompressOutputs(frame);
                            assertArrayEquals(bytes,out[0].bytes());assertArrayEquals(nums,out[1].ints());assertArrayEquals(bytes,out[2].bytes());
                            c.reset();
                        }
                    }
                });
            }
            for(Future<?> task:tasks)task.get(90,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
    }
}
