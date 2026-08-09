package io.personalassistant.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Guards against {@code @ConfigProperty(defaultValue = ...)} drifting away from
 * {@code application.properties}. The properties file is the tiebreaker for any config default
 * (see {@code CLAUDE.md}), so a code default that disagrees is a latent bug: it only shows up on a
 * setup where the property is absent, which is exactly when nobody is watching.
 *
 * <p>Four such disagreements shipped at once — including
 * {@code app.embedding.dimension} defaulting to 384 against a 768-wide index mapping, which
 * invariant 5 says needs a whole new physical index to undo. Hence a test rather than a convention.
 *
 * <p>Deliberately source-text based rather than reflective: {@code @ConfigProperty} appears on
 * constructor parameters as well as fields, which reflection makes awkward, and this needs no CDI
 * container. It resolves {@code src/main} relative to the Gradle test working directory, which is
 * the project directory for this single-module build.
 */
class ConfigDefaultsTest {

    /**
     * Matches both the single-line form and the wrapped constructor-parameter form. Whitespace is
     * collapsed before matching so a line break between {@code name} and {@code defaultValue} is
     * irrelevant. Annotations with no {@code defaultValue} simply don't match — nothing to check.
     */
    private static final Pattern WITH_DEFAULT = Pattern.compile(
            "@ConfigProperty\\( name = \"([^\"]+)\", defaultValue = \"([^\"]*)\" \\)");

    @Test
    void codeDefaultsAgreeWithApplicationProperties() throws IOException {
        Map<String, String> properties = readProperties(Path.of("src/main/resources/application.properties"));
        List<String> mismatches = new ArrayList<>();

        for (Path source : javaSources()) {
            String collapsed = Files.readString(source).replaceAll("\\s+", " ");
            Matcher m = WITH_DEFAULT.matcher(collapsed);
            while (m.find()) {
                String key = m.group(1);
                String codeDefault = m.group(2);
                String fileValue = properties.get(key);
                // Only keys the properties file actually ships are checked. A code default for a key
                // that is deliberately unset (e.g. an optional override) has nothing to disagree with.
                if (fileValue != null && !fileValue.equals(codeDefault)) {
                    mismatches.add(key + ": code default \"" + codeDefault + "\" but application.properties says \""
                            + fileValue + "\" (" + source.getFileName() + ")");
                }
            }
        }

        Assertions.assertTrue(mismatches.isEmpty(),
                "@ConfigProperty defaults disagree with application.properties:\n  " + String.join("\n  ", mismatches));
    }

    /**
     * {@code app.embedding.dimension} must carry no code default anywhere. It is baked into the
     * OpenSearch {@code knn_vector} mapping at index creation (invariant 5), so a guessed width
     * silently builds an index that the configured provider's vectors do not fit — recoverable only
     * by creating a new physical index and re-indexing everything. A missing property must fail
     * startup loudly instead.
     */
    @Test
    void embeddingDimensionHasNoCodeDefault() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            String collapsed = Files.readString(source).replaceAll("\\s+", " ");
            Matcher m = WITH_DEFAULT.matcher(collapsed);
            while (m.find()) {
                if ("app.embedding.dimension".equals(m.group(1))) {
                    offenders.add(source.getFileName() + " defaults it to \"" + m.group(2) + "\"");
                }
            }
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "app.embedding.dimension must have no defaultValue:\n  " + String.join("\n  ", offenders));
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static Map<String, String> readProperties(Path path) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
