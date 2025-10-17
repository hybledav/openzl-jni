package io.github.hybledav;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum OpenZLProfile {
    SERIAL("serial"),
    PYTORCH("pytorch"),
    CSV("csv"),
    LITTLE_ENDIAN_SIGNED_16("le-i16"),
    LITTLE_ENDIAN_UNSIGNED_16("le-u16"),
    LITTLE_ENDIAN_SIGNED_32("le-i32"),
    LITTLE_ENDIAN_UNSIGNED_32("le-u32"),
    LITTLE_ENDIAN_SIGNED_64("le-i64"),
    LITTLE_ENDIAN_UNSIGNED_64("le-u64"),
    PARQUET("parquet"),
    SDDL("sddl"),
    SAO("sao");

    private final String profileName;

    OpenZLProfile(String profileName) {
        this.profileName = profileName;
    }

    public String profileName() {
        return profileName;
    }

    private static final Map<String, OpenZLProfile> BY_NAME = Map.copyOf(
        Stream.of(values()).collect(Collectors.toMap(OpenZLProfile::profileName, p -> p)));

    public static OpenZLProfile fromName(String profileName) {
        Objects.requireNonNull(profileName, "profileName");
        OpenZLProfile profile = BY_NAME.get(profileName);
        if (profile == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Unknown compression profile '%s'. Available profiles: %s",
                    profileName,
                    BY_NAME.keySet()));
        }
        return profile;
    }
}
