package io.personalassistant.common;

import java.util.Optional;

/**
 * Optional text config read as a plain String. SmallRye turns an empty value into null, which fails startup
 * for a bare String injection point, so such properties are {@code Optional<String>} and blank reads as null.
 */
public final class ConfigText {

    private ConfigText() {
    }

    public static String orNull(Optional<String> value) {
        return value == null ? null : value.filter(text -> !text.isBlank()).orElse(null);
    }

    public static String orElse(Optional<String> value, String fallback) {
        String resolved = orNull(value);
        return resolved == null ? fallback : resolved;
    }

    public static boolean isSet(Optional<String> value) {
        return orNull(value) != null;
    }
}
