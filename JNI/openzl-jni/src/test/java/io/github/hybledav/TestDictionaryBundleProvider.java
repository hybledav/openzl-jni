package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestDictionaryBundleProvider {
    static TrainedCompressor first, second;
    static byte[] input, frame, otherFrame;

    static TrainedCompressor train(String tag) {
        byte[][] samples = new byte[128][];
        for (int i=0;i<samples.length;i++) samples[i] = (tag + i
                + " customer=account-" + i + " region=eu-central status=accepted currency=EUR "
                + "product=openzl-sample quantity=3 price=19.99 "
                + (tag + " Reusable dictionary training sample for purchase orders. ").repeat(3))
                .getBytes(StandardCharsets.UTF_8);
        TrainOptions options = new TrainOptions();
        options.dictionaryTraining = true;
        TrainedCompressor result = OpenZLCompressor.trainCandidates("zstd", samples, options)[0];
        assertTrue(result.hasDictionaryBundle());
        return result;
    }

    @BeforeAll
    static void prepare() {
        first = train("ORDER-"); second = train("INVOICE-");
        input = ("ORDER-17 customer=account-17 region=eu-central status=accepted currency=EUR "
                + "product=openzl-sample quantity=3 price=19.99 "
                + "ORDER- Reusable dictionary training sample for purchase orders. ".repeat(3))
                .getBytes(StandardCharsets.UTF_8);
        try (OpenZLCompressor writer = new OpenZLCompressor()) {
            writer.configureTrained(first); frame = writer.compress(input);
            writer.configureTrained(second); otherFrame = writer.compress(input);
        }
        assertEquals(32, OpenZLCompressor.getDictionaryBundleId(frame).length);
        assertFalse(Arrays.equals(OpenZLCompressor.getDictionaryBundleId(frame),
                OpenZLCompressor.getDictionaryBundleId(otherFrame)));
    }

    static String key(byte[] id) { return Base64.getEncoder().encodeToString(id); }

    @Test
    void resolvesDefensivelyAndCachesOnlyLastBundleAcrossAllBytePaths() {
        Map<String,byte[]> bundles = Map.of(key(OpenZLCompressor.getDictionaryBundleId(frame)), first.dictionaryBundle(),
                key(OpenZLCompressor.getDictionaryBundleId(otherFrame)), second.dictionaryBundle());
        AtomicInteger calls = new AtomicInteger();
        try (OpenZLCompressor reader = new OpenZLCompressor()) {
            reader.setDictionaryBundleProvider(id -> {
                calls.incrementAndGet(); byte[] bundle = bundles.get(key(id));
                Arrays.fill(id, (byte)0); return bundle;
            });
            assertArrayEquals(input, reader.decompress(frame));
            byte[] padded = new byte[frame.length+19]; System.arraycopy(frame,0,padded,7,frame.length);
            byte[] output = new byte[input.length+8];
            assertEquals(input.length,reader.decompress(padded,7,frame.length,output,3,input.length));
            assertArrayEquals(input,Arrays.copyOfRange(output,3,3+input.length));
            reader.reset();
            ByteBuffer source = ByteBuffer.allocateDirect(padded.length).put(padded);
            source.position(7).limit(7+frame.length);
            assertArrayEquals(OpenZLCompressor.getDictionaryBundleId(frame),OpenZLCompressor.getDictionaryBundleId(source));
            assertEquals(7,source.position());
            ByteBuffer destination = ByteBuffer.allocateDirect(input.length);
            reader.decompress(source.asReadOnlyBuffer(),destination);
            assertArrayEquals(input,bytes(destination));
            assertEquals(1,calls.get());
            assertArrayEquals(input,reader.decompress(otherFrame));
            assertArrayEquals(input,reader.decompress(frame));
            assertEquals(3,calls.get());
        }
    }

    @Test
    void typedReaderResolvesDictionaryBeforeArrayAndBufferDecoding() {
        AtomicInteger calls = new AtomicInteger();
        try (OpenZLCompressor reader = new OpenZLCompressor()) {
            reader.setDictionaryBundleProvider(id -> { calls.incrementAndGet(); return first.dictionaryBundle(); });
            TypedOutput[] outputs = reader.decompressOutputs(frame);
            assertEquals(1, outputs.length);
            assertEquals(OpenZLType.SERIAL, outputs[0].type());
            assertArrayEquals(input, outputs[0].bytes());
            ByteBuffer buffer = ByteBuffer.allocateDirect(frame.length + 8);
            buffer.position(5); buffer.put(frame); buffer.flip().position(5);
            assertArrayEquals(input, reader.decompressOutputs(buffer.asReadOnlyBuffer())[0].bytes());
            assertEquals(5, buffer.position());
            assertEquals(1, calls.get());
            reader.setDictionaryBundleProvider(id -> null);
            assertThrows(IllegalStateException.class, () -> reader.decompressOutputs(frame));
            reader.setDictionaryBundleProvider(id -> first.dictionaryBundle());
            assertArrayEquals(input, reader.decompressOutputs(frame)[0].bytes());
        }
    }

    @Test
    void managedCompressionAndLazyDecodingRecoverFromProviderFailure() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(64).build();
             OpenZLCompressor writer = new OpenZLCompressor();
             OpenZLCompressor reader = new OpenZLCompressor()) {
            writer.configureTrained(first);
            ByteBuffer source = buffers.acquire(input.length); source.put(input).flip();
            ByteBuffer compressed = writer.compress(source, buffers); buffers.release(source);
            byte[] id = OpenZLCompressor.getDictionaryBundleId(compressed);
            assertEquals(32, id.length);
            ByteBuffer destination = buffers.acquireForDecompression(input.length); buffers.release(destination);
            reader.setDictionaryBundleProvider(key -> null);
            assertThrows(IllegalStateException.class, () -> reader.decompress(compressed, buffers));
            assertEquals(0, compressed.position());
            ByteBuffer recovered = buffers.acquireForDecompression(input.length);
            assertSame(destination, recovered); buffers.release(recovered);
            AtomicInteger calls = new AtomicInteger();
            reader.setDictionaryBundleProvider(key -> { calls.incrementAndGet(); return first.dictionaryBundle(); });
            ByteBuffer output = reader.decompress(compressed.asReadOnlyBuffer(), buffers);
            byte[] decoded = new byte[output.remaining()]; output.duplicate().get(decoded);
            assertArrayEquals(input, decoded); assertEquals(1, calls.get());
            buffers.release(output); buffers.release(compressed);
        }
    }

    static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.position()];buffer.flip().get(result);return result;
    }

    @Test
    void missingWrongFailingAndRecursiveProvidersRecoverWithoutLosingNativeState() {
        try (OpenZLCompressor reader = new OpenZLCompressor()) {
            reader.setDictionaryBundleProvider(id -> null);
            assertThrows(IllegalStateException.class, () -> reader.decompress(frame));
            reader.setDictionaryBundleProvider(id -> second.dictionaryBundle());
            assertThrows(IllegalArgumentException.class, () -> reader.decompress(frame));
            RuntimeException expected = new IllegalStateException("storage unavailable");
            reader.setDictionaryBundleProvider(id -> { throw expected; });
            assertSame(expected,assertThrows(IllegalStateException.class, () -> reader.decompress(frame)));
            reader.setDictionaryBundleProvider(id -> { reader.decompress(frame); return first.dictionaryBundle(); });
            assertThrows(IllegalStateException.class, () -> reader.decompress(frame));
            reader.setDictionaryBundleProvider(id -> first.dictionaryBundle());
            assertArrayEquals(input,reader.decompress(frame));
            reader.setDictionaryBundleProvider(null);
            assertArrayEquals(input,reader.decompress(frame));
            assertThrows(IllegalArgumentException.class, () -> OpenZLCompressor.getDictionaryBundleId(new byte[] {1,2,3}));
        }
    }

    @Test
    void plainNumericFramesDoNotInvokeProvider() {
        try (OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            c.setDictionaryBundleProvider(id -> { throw new AssertionError("No dictionary required"); });
            int[] ints={1,2,3};long[] longs={4,5,6};float[] floats={1f,2f};double[] doubles={3d,4d};
            assertArrayEquals(ints,c.decompressInts(c.compressInts(ints)));
            assertArrayEquals(longs,c.decompressLongs(c.compressLongs(longs)));
            assertArrayEquals(floats,c.decompressFloats(c.compressFloats(floats)));
            assertArrayEquals(doubles,c.decompressDoubles(c.compressDoubles(doubles)));
        }
    }

    @Test
    void callbackCanCloseReaderWithoutUsingFreedNativeMemory() {
        OpenZLCompressor reader=new OpenZLCompressor();
        reader.setDictionaryBundleProvider(id -> { reader.close(); return first.dictionaryBundle(); });
        assertThrows(IllegalStateException.class, () -> reader.decompress(frame));
        try(OpenZLCompressor fresh=new OpenZLCompressor()) { assertNull(fresh.decompress(frame)); }
    }

    @Test
    void sharedProviderWorksAcrossIndependentWorkerContexts() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        Map<String,byte[]> bundles=Map.of(key(OpenZLCompressor.getDictionaryBundleId(frame)),first.dictionaryBundle(),
                key(OpenZLCompressor.getDictionaryBundleId(otherFrame)),second.dictionaryBundle());
        DictionaryBundleProvider provider=id -> bundles.get(key(id));
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<4;worker++) tasks.add(executor.submit(()->{
                try(OpenZLCompressor reader=new OpenZLCompressor()) {
                    reader.setDictionaryBundleProvider(provider);
                    for(int i=0;i<25;i++) {
                        assertArrayEquals(input,reader.decompress(i%2==0?frame:otherFrame));
                        reader.reset();
                    }
                }
            }));
            for(var task:tasks)task.get();
        } finally {executor.shutdownNow();}
    }
}
