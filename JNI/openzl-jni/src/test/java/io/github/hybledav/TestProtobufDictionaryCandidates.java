package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestProtobufDictionaryCandidates {
    static Descriptors.Descriptor descriptor;
    static byte[][] samples;
    static TrainedCompressor candidate;

    @BeforeAll
    static void train() throws Exception {
        var message=DescriptorProtos.DescriptorProto.newBuilder().setName("DictionaryMessage")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder().setName("description").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder().setName("id").setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var file=DescriptorProtos.FileDescriptorProto.newBuilder().setName("dictionary_extension.proto")
                .setPackage("jni.ext032").setSyntax("proto3").addMessageType(message).build();
        descriptor=Descriptors.FileDescriptor.buildFrom(file,new Descriptors.FileDescriptor[0])
                .findMessageTypeByName("DictionaryMessage");
        OpenZLProtobuf.registerSchema(descriptor);
        samples=new byte[128][];
        for(int i=0;i<samples.length;i++) {
            String text="Purchase order account-"+i+" region=eu-central currency=EUR product=item-"+(i%32)
                    +" status=accepted delivery=standard quantity=3 price=19.99 ";
            samples[i]=DynamicMessage.newBuilder(descriptor)
                    .setField(descriptor.findFieldByNumber(1),text.repeat(12))
                    .setField(descriptor.findFieldByNumber(2),i).build().toByteArray();
        }
        TrainOptions options=new TrainOptions();options.dictionaryTraining=true;options.maxNumCandidates=1;
        var candidates=OpenZLProtobuf.trainCandidates(samples,OpenZLProtobuf.Protocol.PROTO,options,descriptor);
        assertEquals(1,candidates.length);candidate=candidates[0];
        assertEquals("protobuf",candidate.profileName());
        assertTrue(candidate.hasDictionaryBundle(),"Training must return its dictionary bundle");
    }

    @AfterEach
    void clearCache() { OpenZLProtobuf.clearDictionaryCache(); }

    static byte[] encode(byte[] sample,TrainedCompressor trained) {
        return OpenZLProtobuf.convertWithCandidate(sample,OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,trained,descriptor);
    }

    @Test
    void persistedCandidateAndBundleOnlyReaderRoundTrip(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("graph"),candidate.serializedCompressor());
        Files.write(directory.resolve("dict"),candidate.dictionaryBundle());
        var restored=new TrainedCompressor("protobuf",Files.readAllBytes(directory.resolve("graph")),
                Files.readAllBytes(directory.resolve("dict")));
        byte[] frame=encode(samples[17],restored);
        assertEquals(32,OpenZLCompressor.getDictionaryBundleId(frame).length);
        OpenZLProtobuf.clearDictionaryCache();
        assertArrayEquals(samples[17],OpenZLProtobuf.convertWithDictionary(frame,
                OpenZLProtobuf.Protocol.PROTO,restored.dictionaryBundle(),descriptor));
        OpenZLProtobuf.clearDictionaryCache();
        assertArrayEquals(samples[17],OpenZLProtobuf.convertWithDictionaryProvider(frame,
                OpenZLProtobuf.Protocol.PROTO,id -> restored.dictionaryBundle(),descriptor));
        assertTrue(new String(OpenZLProtobuf.convertWithDictionary(frame,OpenZLProtobuf.Protocol.JSON,
                restored.dictionaryBundle(),descriptor),java.nio.charset.StandardCharsets.UTF_8).contains("account-17"));
        assertThrows(IllegalStateException.class,()->OpenZLProtobuf.convert(frame,
                OpenZLProtobuf.Protocol.ZL,OpenZLProtobuf.Protocol.PROTO,descriptor));
    }

    @Test
    void directInputKeepsPositionsAndSupportsReadOnlySlices() {
        ByteBuffer source=ByteBuffer.allocateDirect(samples[0].length+15);
        source.position(7).put(samples[0]);source.limit(source.position()).position(7);
        ByteBuffer view=source.asReadOnlyBuffer();
        byte[] frame=OpenZLProtobuf.convertWithCandidate(view,samples[0].length,OpenZLProtobuf.Protocol.PROTO,
                OpenZLProtobuf.Protocol.ZL,candidate,descriptor);
        assertEquals(7,view.position());assertEquals(7+samples[0].length,view.limit());
        assertArrayEquals(samples[0],OpenZLProtobuf.convertWithCandidate(frame,OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,candidate,descriptor));
        assertThrows(IndexOutOfBoundsException.class,()->OpenZLProtobuf.convertWithCandidate(view,Integer.MAX_VALUE,
                OpenZLProtobuf.Protocol.PROTO,OpenZLProtobuf.Protocol.ZL,candidate,descriptor));
    }

    @Test
    void corruptMissingAndWrongBundlesCannotPoisonGoodConversions() {
        byte[] frame=encode(samples[3],candidate);
        assertThrows(IllegalStateException.class,()->OpenZLProtobuf.convertWithDictionary(frame,
                OpenZLProtobuf.Protocol.PROTO,new byte[0],descriptor));
        assertThrows(IllegalStateException.class,()->OpenZLProtobuf.convertWithDictionary(frame,
                OpenZLProtobuf.Protocol.PROTO,new byte[]{1,2,3},descriptor));
        assertThrows(IllegalStateException.class,()->OpenZLProtobuf.convertWithDictionary(frame,
                OpenZLProtobuf.Protocol.PROTO,TestDictionaryBundleProvider.train("UNRELATED-").dictionaryBundle(),descriptor));
        assertThrows(IllegalStateException.class,()->OpenZLProtobuf.convertWithDictionaryProvider(frame,
                OpenZLProtobuf.Protocol.PROTO,id -> null,descriptor));
        assertThrows(IllegalStateException.class,()->encode(samples[3],new TrainedCompressor("protobuf",
                new byte[]{1,2,3},candidate.dictionaryBundle())));
        assertThrows(IllegalArgumentException.class,()->encode(samples[3],new TrainedCompressor("serial",
                candidate.serializedCompressor(),candidate.dictionaryBundle())));
        assertArrayEquals(samples[3],OpenZLProtobuf.convertWithDictionary(frame,
                OpenZLProtobuf.Protocol.PROTO,candidate.dictionaryBundle(),descriptor));
    }

    @Test
    void legacyTrainingAndFormat24CandidatesRemainSelfContained() {
        TrainOptions options=new TrainOptions();options.dictionaryTraining=true;
        assertThrows(IllegalArgumentException.class,()->OpenZLProtobuf.train(samples,
                OpenZLProtobuf.Protocol.PROTO,options,descriptor));
        options.formatVersion=24;
        assertThrows(IllegalArgumentException.class,()->OpenZLProtobuf.trainCandidates(samples,
                OpenZLProtobuf.Protocol.PROTO,options,descriptor));
        options.dictionaryTraining=false;options.maxNumCandidates=1;
        var trained=OpenZLProtobuf.trainCandidates(Arrays.copyOf(samples,4),OpenZLProtobuf.Protocol.PROTO,options,descriptor);
        assertEquals(1,trained.length);assertFalse(trained[0].hasDictionaryBundle());
        byte[] frame=encode(samples[0],trained[0]);
        try(var inspector=new OpenZLCompressor()) {assertEquals(24,inspector.inspect(frame).formatVersion());}
        assertArrayEquals(samples[0],OpenZLProtobuf.convert(frame,OpenZLProtobuf.Protocol.ZL,
                OpenZLProtobuf.Protocol.PROTO,(byte[])null,descriptor));
    }

    @Test
    void dictionaryContextsAreIsolatedAcrossThreads() throws Exception {
        var executor=Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<4;n++) {
                final int index=n;
                tasks.add(executor.submit(()->{
                    try {
                        for(int i=0;i<10;i++) {
                            byte[] frame=encode(samples[index],candidate);
                            assertArrayEquals(samples[index],OpenZLProtobuf.convertWithDictionary(frame,
                                    OpenZLProtobuf.Protocol.PROTO,candidate.dictionaryBundle(),descriptor));
                        }
                    } finally {OpenZLProtobuf.clearDictionaryCache();}
                }));
            }
            for(var task:tasks)task.get();
        } finally {executor.shutdownNow();}
    }

    @Test
    void invalidCandidateTrainingArgumentsFailBeforeNativeTraining() {
        TrainOptions options=new TrainOptions();options.maxNumCandidates=-1;
        assertThrows(IllegalArgumentException.class,()->OpenZLProtobuf.trainCandidates(samples,
                OpenZLProtobuf.Protocol.PROTO,options,descriptor));
        assertThrows(IllegalArgumentException.class,()->OpenZLProtobuf.trainCandidates(new byte[0][],
                OpenZLProtobuf.Protocol.PROTO,null,descriptor));
        assertThrows(NullPointerException.class,()->OpenZLProtobuf.trainCandidates(new byte[][]{null},
                OpenZLProtobuf.Protocol.PROTO,null,descriptor));
    }
}
