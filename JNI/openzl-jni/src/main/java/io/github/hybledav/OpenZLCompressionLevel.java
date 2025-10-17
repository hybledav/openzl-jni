package io.github.hybledav;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum OpenZLCompressionLevel {
    LEVEL_1(1),
    LEVEL_2(2),
    LEVEL_3(3),
    LEVEL_4(4),
    LEVEL_5(5),
    LEVEL_6(6),
    LEVEL_7(7),
    LEVEL_8(8),
    LEVEL_9(9),
    LEVEL_10(10),
    LEVEL_11(11),
    LEVEL_12(12),
    LEVEL_13(13),
    LEVEL_14(14),
    LEVEL_15(15),
    LEVEL_16(16),
    LEVEL_17(17),
    LEVEL_18(18),
    LEVEL_19(19),
    LEVEL_20(20),
    LEVEL_21(21),
    LEVEL_22(22);

    private final int level;

    OpenZLCompressionLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    private static final Map<Integer, OpenZLCompressionLevel> BY_LEVEL = Map.copyOf(
            Stream.of(values()).collect(Collectors.toMap(OpenZLCompressionLevel::level, level -> level)));

    public static OpenZLCompressionLevel fromLevel(int level) {
        OpenZLCompressionLevel mapped = BY_LEVEL.get(level);
        if (mapped == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Unknown compression level %d. Supported levels: %s",
                    level,
                    BY_LEVEL.keySet()));
        }
        return mapped;
    }
}