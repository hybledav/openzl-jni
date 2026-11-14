package io.github.hybledav;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestManagedCompression {
    private static byte[] data(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte)(i % 31);
        return bytes;
    }
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()]; buffer.duplicate().get(result); return result;
    }
    @Test void foreignReadOnlyAndOtherManagersBuffersAreIgnored() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(32).alignment(8).build();
             OpenZLBufferManager other = OpenZLBufferManager.builder().minimumCapacity(32).alignment(8).build()) {
            ByteBuffer foreign = ByteBuffer.allocateDirect(32).asReadOnlyBuffer();
            foreign.position(7);
            buffers.release(foreign);
            assertEquals(7, foreign.position(), "Foreign release must not clear its cursor");
            ByteBuffer fromOther = other.acquire(32); buffers.release(fromOther);
            ByteBuffer owned = buffers.acquire(32);
            assertNotSame(foreign, owned); assertNotSame(fromOther, owned); assertFalse(owned.isReadOnly());
            buffers.release(owned.duplicate());
            buffers.release(owned.slice());
            buffers.release(owned.asReadOnlyBuffer());
            buffers.release(owned);
            assertSame(owned, buffers.acquire(32));
            ByteBuffer another = buffers.acquire(32);
            assertNotSame(owned, another); assertFalse(another.isReadOnly());
            other.release(fromOther); buffers.release(owned); buffers.release(another);
        }
    }
    @Test void duplicateReleaseCannotHandOutTheSameStorageTwice() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(32).build()) {
            ByteBuffer original = buffers.acquire(32);
            buffers.release(original); buffers.release(original);
            ByteBuffer first = buffers.acquire(32), second = buffers.acquire(32);
            assertSame(original, first); assertNotSame(first, second);
            first.put(0,(byte)19); second.put(0,(byte)41);
            assertEquals(19,first.get(0));
            buffers.release(first); buffers.release(second);
        }
    }
    @Test void failedManagedCompressionReturnsItsDestinationAndKeepsSourceCursor() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(32).build();
             OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.NUMERIC)) {
            ByteBuffer source = ByteBuffer.allocateDirect(1030); source.position(3); source.put(data(1024)); source.flip().position(3);
            ByteBuffer destination = buffers.acquireForCompression(source.remaining()); buffers.release(destination);
            c.setCompressionOptions(CompressionOptions.builder().minStreamSize(-1).build());
            assertThrows(IllegalStateException.class,()->c.compress(source,buffers));
            assertEquals(3,source.position()); assertEquals(1027,source.limit());
            ByteBuffer recovered = buffers.acquireForCompression(source.remaining());
            assertSame(destination,recovered,"Failed operation must return the borrowed destination");
            buffers.release(recovered);
            c.setCompressionOptions(CompressionOptions.builder().permissive(ParameterSwitch.ENABLED).build());
            ByteBuffer frame = c.compress(source,buffers);
            ByteBuffer output = c.decompress(frame,buffers);
            assertArrayEquals(data(1024),bytes(output));
            buffers.release(frame); buffers.release(output);
        }
    }
    @Test void failedManagedDecompressionReturnsItsDestinationAndRecovers() {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(32).build();
             OpenZLCompressor c = new OpenZLCompressor(OpenZLGraph.STORE)) {
            byte[] frame = c.compress(data(1024)), corrupted = frame.clone(); corrupted[corrupted.length-1]^=1;
            ByteBuffer source=ByteBuffer.allocateDirect(corrupted.length+3); source.position(3).put(corrupted).flip().position(3);
            assertEquals(1024,c.getDecompressedSize(source));
            ByteBuffer destination=buffers.acquireForDecompression(1024);buffers.release(destination);
            assertThrows(IllegalStateException.class,()->c.decompress(source,buffers));
            assertEquals(3,source.position());
            ByteBuffer recovered=buffers.acquireForDecompression(1024);
            assertSame(destination,recovered,"Failed operation must return the borrowed destination");
            buffers.release(recovered);
            ByteBuffer valid=ByteBuffer.allocateDirect(frame.length).put(frame);valid.flip();
            ByteBuffer out=c.decompress(valid,buffers);assertArrayEquals(data(1024),bytes(out));buffers.release(out);
        }
    }
    @Test void resetCacheDiscardsOldOwnershipAndClosedCompressorReturnsDestination() {
        try (OpenZLBufferManager buffers=OpenZLBufferManager.builder().minimumCapacity(32).build()) {
            ByteBuffer old=buffers.acquire(32);
            ByteBuffer cached=buffers.acquire(32); buffers.release(cached); buffers.close();
            buffers.release(old); buffers.release(cached);
            ByteBuffer fresh=buffers.acquire(32); assertNotSame(old,fresh);buffers.release(fresh);
            ByteBuffer source=ByteBuffer.allocateDirect(32).put(data(32));source.flip();
            ByteBuffer destination=buffers.acquireForCompression(32);buffers.release(destination);
            OpenZLCompressor c=new OpenZLCompressor();c.close();
            assertThrows(IllegalStateException.class,()->c.compress(source,buffers));
            assertEquals(0,source.position());
            ByteBuffer recovered=buffers.acquireForCompression(32);assertSame(destination,recovered);buffers.release(recovered);
        }
    }
    @Test void returningOnAnotherThreadDoesNotTransferOwnership() throws Exception {
        try (OpenZLBufferManager buffers = OpenZLBufferManager.builder().minimumCapacity(32).build()) {
            ByteBuffer original = buffers.acquire(32); original.position(7);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    buffers.release(original);
                    ByteBuffer own = buffers.acquire(32);
                    assertNotSame(original, own); buffers.release(own); buffers.close();
                }).get(10, TimeUnit.SECONDS);
                assertEquals(7, original.position());
                buffers.release(original);
                ByteBuffer returned = buffers.acquire(32); assertSame(original, returned); buffers.release(returned);
            } finally { worker.shutdownNow(); }
        }
    }

    private static void roundTrips(OpenZLBufferManager buffers, OpenZLCompressor c, int count, int seed) {
        Random random=new Random(seed);
        int[] boundaries={0,1,7,8,9,31,32,63,64,65,255,256,257,65535,65536,65537,1048576};
        for(int i=0;i<count;i++) {
            int size=i<boundaries.length?boundaries[i]:random.nextInt(8193);
            byte[] expected=new byte[size];if((i&1)==0)random.nextBytes(expected);else expected=data(size);
            ByteBuffer source=buffers.acquire(size+7);
            source.position(3).put(expected).flip().position(3);
            ByteBuffer readOnly=source.asReadOnlyBuffer();
            ByteBuffer compressed=c.compress(readOnly,buffers);
            assertEquals(readOnly.limit(),readOnly.position()); assertEquals(3,source.position());
            assertEquals(0,compressed.position());assertTrue(compressed.remaining()>0);
            buffers.release(source);
            ByteBuffer compressedView=compressed.asReadOnlyBuffer();
            ByteBuffer restored=c.decompress(compressedView,buffers);
            assertEquals(compressedView.limit(),compressedView.position());assertEquals(0,compressed.position());
            assertEquals(0,restored.position());assertEquals(size,restored.remaining());
            assertArrayEquals(expected,bytes(restored));
            buffers.release(compressed);buffers.release(restored);
            if(i%100==0) {
                c.reset();
                c.setDecompressionOptions(DecompressionOptions.builder()
                        .codecFusion(i%200==0?ParameterSwitch.DISABLED:ParameterSwitch.ENABLED).build());
            }
        }
    }
    @Test void tenThousandManagedRoundTripsAcrossFourIndependentContexts() throws Exception {
        try(OpenZLBufferManager buffers=OpenZLBufferManager.builder().minimumCapacity(64).alignment(16).build()) {
            ExecutorService workers=Executors.newFixedThreadPool(4);
            Set<ByteBuffer> inUse=Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            CyclicBarrier barrier=new CyclicBarrier(4);
            try {
                Future<?>[] jobs=new Future<?>[4];
                for(int w=0;w<4;w++) {
                    final int worker=w;
                    jobs[w]=workers.submit(()->{
                        try(OpenZLCompressor c=new OpenZLCompressor(worker%2==0?OpenZLGraph.ZSTD:OpenZLGraph.GENERIC)) {
                            ByteBuffer owned=buffers.acquire(256);assertTrue(inUse.add(owned));barrier.await(10,TimeUnit.SECONDS);
                            buffers.release(owned);
                            if(worker==3)c.configureLz(LzOptions.builder().windowLog(16).build());
                            roundTrips(buffers,c,2500,5123+worker);
                        } catch(Exception e){throw new RuntimeException(e);}
                        finally{buffers.close();}
                    });
                }
                for(Future<?> job:jobs)job.get(120,TimeUnit.SECONDS);
            }finally{workers.shutdownNow();}
        }
    }
    @Test void shortLivedTasksCanUseManagedBuffersOnVirtualThreads() throws Exception {
        ExecutorService workers;
        if(Runtime.version().feature()>=21) {
            workers=(ExecutorService)Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        }else workers=Executors.newFixedThreadPool(4);
        try(OpenZLBufferManager buffers=OpenZLBufferManager.builder().minimumCapacity(64).build()) {
            try {
                Future<?>[] jobs=new Future<?>[200];
                for(int i=0;i<jobs.length;i++) {
                    jobs[i]=workers.submit(()->{
                        try(OpenZLCompressor c=new OpenZLCompressor()) {
                            // Small payloads keep the short-lived task test bounded in direct memory.
                            ByteBuffer source=buffers.acquire(1024);source.put(data(1024)).flip();
                            ByteBuffer frame=c.compress(source,buffers);buffers.release(source);
                            ByteBuffer output=c.decompress(frame,buffers);assertArrayEquals(data(1024),bytes(output));
                            buffers.release(frame);buffers.release(output);
                        }finally{buffers.close();}
                    });
                }
                for(Future<?> job:jobs)job.get(60,TimeUnit.SECONDS);
            }finally{workers.shutdownNow();}
        }
    }
}
