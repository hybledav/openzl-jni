package io.github.hybledav;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class TestTypedOptions {
    private static void range(Object builder,String method,int min,int max,int... invalid) throws Exception {
        var setter=builder.getClass().getMethod(method,int.class);
        assertSame(builder,setter.invoke(builder,0));
        assertSame(builder,setter.invoke(builder,min));
        assertSame(builder,setter.invoke(builder,max));
        for(int value:invalid) {
            InvocationTargetException error=assertThrows(InvocationTargetException.class,()->setter.invoke(builder,value));
            assertInstanceOf(IllegalArgumentException.class,error.getCause());
        }
    }
    @Test void compressionOptionsValidateBoundsAndSnapshotsAreIndependent() throws Exception {
        var b=CompressionOptions.builder();
        range(b,"compressionLevel",-10000,7,-10001,8);
        range(b,"decompressionLevel",0,7,-1,8);
        range(b,"formatVersion",8,27,-1,7,28);
        range(b,"minStreamSize",-1,Integer.MAX_VALUE,-2);
        for(ParameterSwitch value:ParameterSwitch.values()) {
            CompressionOptions o=b.permissive(value).compressedChecksum(value).contentChecksum(value).storeOnExpansion(value).build();
            assertEquals(value,o.permissive());assertEquals(value,o.compressedChecksum());
            assertEquals(value,o.contentChecksum());assertEquals(value,o.storeOnExpansion());
        }
        assertThrows(NullPointerException.class,()->b.permissive(null));
        assertThrows(NullPointerException.class,()->b.compressedChecksum(null));
        assertThrows(NullPointerException.class,()->b.contentChecksum(null));
        assertThrows(NullPointerException.class,()->b.storeOnExpansion(null));
        CompressionOptions snapshot=b.compressionLevel(3).decompressionLevel(2).formatVersion(24).minStreamSize(-1).build();
        b.compressionLevel(7).decompressionLevel(7).formatVersion(27).minStreamSize(100);
        assertEquals(3,snapshot.compressionLevel());assertEquals(2,snapshot.decompressionLevel());
        assertEquals(24,snapshot.formatVersion());assertEquals(-1,snapshot.minStreamSize());
    }
    @Test void decompressionSwitchesAndBuilderSnapshots() {
        var b=DecompressionOptions.builder();
        for(ParameterSwitch value:ParameterSwitch.values()) {
            DecompressionOptions o=b.checkCompressedChecksum(value).checkContentChecksum(value).codecFusion(value).build();
            assertEquals(value,o.checkCompressedChecksum());assertEquals(value,o.checkContentChecksum());assertEquals(value,o.codecFusion());
        }
        assertThrows(NullPointerException.class,()->b.checkCompressedChecksum(null));
        assertThrows(NullPointerException.class,()->b.checkContentChecksum(null));
        assertThrows(NullPointerException.class,()->b.codecFusion(null));
        DecompressionOptions snapshot=b.codecFusion(ParameterSwitch.ENABLED).build();
        b.codecFusion(ParameterSwitch.DISABLED);
        assertEquals(ParameterSwitch.ENABLED,snapshot.codecFusion());
    }
    @Test void lzOptionsCheckAllExposedNativeBounds() throws Exception {
        var b=LzOptions.builder();
        range(b,"compressionLevel",-10000,7,-10001,8);
        range(b,"acceleration",1,10000,-1,10001);
        range(b,"windowLog",10,28,9,29);
        range(b,"hashLog1",10,23,9,24);
        range(b,"hashLog2",10,23,9,24);
        range(b,"hashLength",4,7,3,8);
        range(b,"searchLog",1,4,-1,5);
        range(b,"entropyMinGainBytes",-1,Integer.MAX_VALUE,-2);
        range(b,"entropyMinGainPercent",-1,100,-2,101);
        for(LzOptions.Strategy strategy:LzOptions.Strategy.values()) assertEquals(strategy,b.strategy(strategy).build().strategy());
        assertThrows(NullPointerException.class,()->b.strategy(null));
        LzOptions snapshot=b.compressionLevel(1).acceleration(2).windowLog(16).hashLog1(11).hashLog2(12)
                .hashLength(4).searchLog(3).entropyMinGainBytes(50).entropyMinGainPercent(1).build();
        b.compressionLevel(7).windowLog(20);
        assertEquals(1,snapshot.compressionLevel());assertEquals(2,snapshot.acceleration());
        assertEquals(16,snapshot.windowLog());assertEquals(11,snapshot.hashLog1());assertEquals(12,snapshot.hashLog2());
        assertEquals(4,snapshot.hashLength());assertEquals(3,snapshot.searchLog());
        assertEquals(50,snapshot.entropyMinGainBytes());assertEquals(1,snapshot.entropyMinGainPercent());
        assertEquals(-1,LzOptions.builder().build().entropyMinGainBytes());
        assertEquals(-1,LzOptions.builder().build().entropyMinGainPercent());
    }
    @Test void formatAndChecksumsApplyToRepeatedRealFrames() {
        byte[] data=new byte[8192];
        for(int i=0;i<data.length;i++)data[i]=(byte)(i%97);
        try(OpenZLCompressor c=new OpenZLCompressor(OpenZLGraph.GENERIC)) {
            for(int format:new int[]{24,25,26,27}) {
                for(ParameterSwitch setting:ParameterSwitch.values()) {
                    c.setCompressionOptions(CompressionOptions.builder().compressionLevel(3).decompressionLevel(2)
                            .formatVersion(format).minStreamSize(-1).permissive(ParameterSwitch.DISABLED)
                            .compressedChecksum(setting).contentChecksum(setting).storeOnExpansion(setting).build());
                    for(int repeat=0;repeat<2;repeat++) {
                        byte[] frame=c.compressInputs(TypedInput.serial(data));
                        assertEquals(format,c.inspect(frame).formatVersion());
                        assertArrayEquals(data,c.decompressOutputs(frame)[0].bytes());
                    }
                }
            }
            c.reset();
            assertEquals(27,c.inspect(c.compressInputs(TypedInput.serial(data))).formatVersion());
        }
    }
}
