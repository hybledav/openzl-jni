package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TestCompressionInfoGuards {

    @Test
    void dataFlavorMappingIncludesFallback() {
        assertEquals(OpenZLCompressionInfo.DataFlavor.SERIAL, OpenZLCompressionInfo.DataFlavor.fromNative(1));
        assertEquals(OpenZLCompressionInfo.DataFlavor.STRUCT, OpenZLCompressionInfo.DataFlavor.fromNative(2));
        assertEquals(OpenZLCompressionInfo.DataFlavor.NUMERIC, OpenZLCompressionInfo.DataFlavor.fromNative(4));
        assertEquals(OpenZLCompressionInfo.DataFlavor.STRING, OpenZLCompressionInfo.DataFlavor.fromNative(8));
        assertEquals(OpenZLCompressionInfo.DataFlavor.UNKNOWN, OpenZLCompressionInfo.DataFlavor.fromNative(999));
    }

    @Test
    void elementCountEmptyWhenNegative() {
        OpenZLCompressionInfo info = new OpenZLCompressionInfo(
                100,
                10,
                OpenZLGraph.ZSTD,
                OpenZLCompressionInfo.DataFlavor.SERIAL,
                -1,
                1);
        assertEquals(OptionalLong.empty(), info.elementCount());
    }

    @Test
    void elementCountPresentWhenNonNegative() {
        OpenZLCompressionInfo info = new OpenZLCompressionInfo(
                100,
                10,
                OpenZLGraph.ZSTD,
                OpenZLCompressionInfo.DataFlavor.SERIAL,
                42,
                1);
        assertEquals(OptionalLong.of(42), info.elementCount());
    }

    @Test
    void compressionRatioZeroWhenOriginalZero() {
        OpenZLCompressionInfo info = new OpenZLCompressionInfo(
                0,
                10,
                OpenZLGraph.ZSTD,
                OpenZLCompressionInfo.DataFlavor.SERIAL,
                0,
                1);
        assertEquals(0.0d, info.compressionRatio());
    }

    @Test
    void toStringIncludesElementCount() {
        OpenZLCompressionInfo info = new OpenZLCompressionInfo(
                100,
                10,
                OpenZLGraph.ZSTD,
                OpenZLCompressionInfo.DataFlavor.NUMERIC,
                12,
                3);
        String rendered = info.toString();
        assertTrue(rendered.contains("elements=12"));
        assertTrue(rendered.contains("version=3"));
    }

    @Test
    void toStringHandlesUnknownElementCount() {
        OpenZLCompressionInfo info = new OpenZLCompressionInfo(
                50,
                5,
                OpenZLGraph.ZSTD,
                OpenZLCompressionInfo.DataFlavor.STRING,
                -1,
                2);
        assertTrue(info.toString().contains("elements=n/a"));
        assertEquals(2, info.formatVersion());
    }
}
