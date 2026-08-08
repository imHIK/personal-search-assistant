package io.personalassistant.common;

import java.util.Optional;

/**
 * Reads an optional text config property as a plain {@code String}, treating absent and blank
 * alike.
 *
 * <p>Why this exists: a {@code @ConfigProperty} injection point typed as a bare {@code String}
 * <strong>cannot hold an empty value</strong>. SmallRye Config converts {@code ""} to {@code null},
 * and a non-{@code Optional} injection point then fails startup with
 * {@code "Failed to load config value of type class java.lang.String for: <key>"}. Writing
 * {@code defaultValue = ""} does not help — the default goes through the same converter — and
 * neither does removing the property from {@code application.properties}.
 *
 * <p>So every "may legitimately be unset" text property is injected as {@code Optional<String>} and
 * read through here. Blank collapses to {@code null} because that is what the call sites already
 * mean by "not configured": a blank {@code app.scheduler.default-cron} means no cron, a blank
 * {@code app.ingestion.google-drive.download-dir} means use the temp directory, a blank API key
 * means send no {@code Authorization} header.
 *
 * <p>Properties that always have a real value (e.g. {@code app.scheduler.default-interval=1d})
 * stay plain {@code String} with a non-empty {@code defaultValue} — they are unaffected.
 */
public final class ConfigText {

    private ConfigText() {
    }

    /**
     * @param value an injected optional text property
     * @return the trimmed-of-meaning value, or {@code null} when absent or blank
     */
    public static String orNull(Optional<String> value) {
        return value == null ? null : value.filter(text -> !text.isBlank()).orElse(null);
    }

    /**
     * @param value an injected optional text property
     * @return the value, or {@code fallback} when absent or blank
     */
    public static String orElse(Optional<String> value, String fallback) {
        String resolved = orNull(value);
        return resolved == null ? fallback : resolved;
    }

    /** True when the property carries an actual value (present and not blank). */
    public static boolean isSet(Optional<String> value) {
        return orNull(value) != null;
    }
}
