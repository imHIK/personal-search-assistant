package io.personalassistant.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code @ConfigProperty} defaults must agree with {@code application.properties}, in both directions. It
 * scans source text, so a non-literal default (a constant) is invisible to it.
 */
class ConfigDefaultsTest {

    /** Whitespace is collapsed and every separator is {@code \s*}, so no one formatting style is assumed. */
    private static final Pattern WITH_DEFAULT = Pattern.compile(
            "@ConfigProperty\\(\\s*name\\s*=\\s*\"([^\"]+)\"\\s*,\\s*defaultValue\\s*=\\s*\"([^\"]*)\"\\s*\\)");

    /**
     * Keys read without a {@code @ConfigProperty} injection point. Any other shipped key that nothing injects
     * is dead.
     */
    private static final Set<String> REACHED_ANOTHER_WAY = Set.of(
            // Resolved by name in LlmProfiles.
            "app.llm.profile.answer.model",
            "app.llm.profile.answer.temperature",
            "app.llm.profile.answer.max-tokens",
            "app.llm.profile.lite.base-url",
            "app.llm.profile.lite.model",
            "app.llm.profile.lite.api-key",
            "app.llm.profile.lite.temperature",
            "app.llm.profile.lite.max-tokens",
            // Interpolated into @Scheduled(every = "{…}") rather than injected.
            "app.ingestion.poll-interval",
            "app.indexing.poll-interval",
            "app.scheduler.forward-interval",
            "app.scheduler.discovery-interval",
            "app.retention.poll-interval",
            "app.digest.poll-interval",
            "app.publishing.poll-interval",
            "app.connections.health-interval",
            // Composed per provider id by OAuthClients.
            "app.oauth.google.client-id",
            "app.oauth.google.client-secret");

    @Test
    void codeDefaultsAgreeWithApplicationProperties() throws IOException {
        Map<String, String> properties = readProperties(Path.of("src/main/resources/application.properties"));
        List<String> mismatches = new ArrayList<>();

        for (Path source : javaSources()) {
            mismatches.addAll(mismatchesIn(Files.readString(source), properties,
                    source.getFileName().toString()));
        }

        Assertions.assertTrue(mismatches.isEmpty(),
                "@ConfigProperty defaults disagree with application.properties:\n  " + String.join("\n  ", mismatches));
    }

    /** A scan that matches nothing passes every other test here, so prove it matches. */
    @Test
    void theScanActuallyMatchesTheCodebase() throws IOException {
        int matches = 0;
        for (Path source : javaSources()) {
            Matcher m = WITH_DEFAULT.matcher(collapse(Files.readString(source)));
            while (m.find()) {
                matches++;
            }
        }
        Assertions.assertTrue(matches > 50,
                "the @ConfigProperty scan found only " + matches + " annotations — the pattern has "
                        + "drifted from the code's formatting and this test is no longer checking anything");
    }

    @Test
    void reportsAPlantedDrift() {
        String planted = "@ConfigProperty(name = \"app.embedding.dimension\", defaultValue = \"384\") int dim;";

        List<String> mismatches = mismatchesIn(planted, Map.of("app.embedding.dimension", "768"), "Planted.java");

        Assertions.assertEquals(1, mismatches.size(), "the planted drift must be reported: " + mismatches);
        Assertions.assertTrue(mismatches.get(0).contains("384") && mismatches.get(0).contains("768"),
                "the report must name both values: " + mismatches.get(0));
    }

    @Test
    void embeddingDimensionHasNoCodeDefault() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            Matcher m = WITH_DEFAULT.matcher(collapse(Files.readString(source)));
            while (m.find()) {
                if ("app.embedding.dimension".equals(m.group(1))) {
                    offenders.add(source.getFileName() + " defaults it to \"" + m.group(2) + "\"");
                }
            }
        }
        Assertions.assertTrue(offenders.isEmpty(),
                "app.embedding.dimension must have no defaultValue:\n  " + String.join("\n  ", offenders));
    }

    @Test
    void everyShippedPropertyIsActuallyRead() throws IOException {
        Map<String, String> properties = readProperties(Path.of("src/main/resources/application.properties"));
        StringBuilder code = new StringBuilder();
        for (Path source : javaSources()) {
            code.append(collapse(Files.readString(source))).append(' ');
        }
        String allCode = code.toString();

        List<String> orphans = new ArrayList<>();
        for (String key : properties.keySet()) {
            if (!key.startsWith("app.") || REACHED_ANOTHER_WAY.contains(key)) {
                continue;
            }
            if (!allCode.contains("\"" + key + "\"")) {
                orphans.add(key);
            }
        }

        Assertions.assertTrue(orphans.isEmpty(),
                "application.properties ships keys nothing reads (rename debris, or add them to "
                        + "REACHED_ANOTHER_WAY if they are resolved dynamically):\n  "
                        + String.join("\n  ", orphans));
    }

    @Test
    void theAllowlistHasNoStaleEntries() throws IOException {
        Map<String, String> properties = readProperties(Path.of("src/main/resources/application.properties"));

        List<String> stale = REACHED_ANOTHER_WAY.stream()
                .filter(key -> !properties.containsKey(key))
                .sorted()
                .toList();

        Assertions.assertTrue(stale.isEmpty(),
                "REACHED_ANOTHER_WAY lists keys application.properties no longer ships:\n  "
                        + String.join("\n  ", stale));
    }

    private static List<String> mismatchesIn(String javaSource, Map<String, String> properties, String name) {
        List<String> mismatches = new ArrayList<>();
        Matcher m = WITH_DEFAULT.matcher(collapse(javaSource));
        while (m.find()) {
            String key = m.group(1);
            String codeDefault = m.group(2);
            String fileValue = properties.get(key);
            // A code default for a key the file deliberately leaves unset has nothing to disagree with.
            if (fileValue != null && !fileValue.equals(codeDefault)) {
                mismatches.add(key + ": code default \"" + codeDefault + "\" but application.properties says \""
                        + fileValue + "\" (" + name + ")");
            }
        }
        return mismatches;
    }

    private static String collapse(String source) {
        return source.replaceAll("\\s+", " ");
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
