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
 * Guards against {@code @ConfigProperty(defaultValue = ...)} drifting away from
 * {@code application.properties}, in both directions. The properties file is the tiebreaker for any
 * config default (see {@code CLAUDE.md}), so a code default that disagrees is a latent bug: it only
 * shows up on a setup where the property is absent, which is exactly when nobody is watching.
 *
 * <p>Four such disagreements shipped at once — including
 * {@code app.embedding.dimension} defaulting to 384 against a 768-wide index mapping, which
 * invariant 5 says needs a whole new physical index to undo. Hence a test rather than a convention.
 *
 * <p><strong>This test previously enforced nothing.</strong> Its pattern required a literal space after
 * {@code (} and before {@code )} — {@code @ConfigProperty( name = … )} — a formatting the codebase has
 * never used, so it matched <em>zero</em> of 74 real annotations and passed vacuously for its whole
 * life. Two live drifts it was written to catch were sitting in the tree when it was fixed:
 * {@code app.embedding.provider} (code {@code onnx-bge} vs file {@code openai-embed}) and
 * {@code app.embedding.openai.dimensions} (code {@code 0} vs file {@code 768}, i.e. a missing property
 * would have requested 3072-wide vectors against a 768 mapping). {@link #reportsAPlantedDrift()} exists
 * so that can never happen quietly again — a guard that has never failed is not a guard.
 *
 * <p>Deliberately source-text based rather than reflective: {@code @ConfigProperty} appears on
 * constructor parameters as well as fields, which reflection makes awkward, and this needs no CDI
 * container. It resolves {@code src/main} relative to the Gradle test working directory, which is
 * the project directory for this single-module build.
 *
 * <p><strong>Known blind spot:</strong> a non-literal default such as
 * {@code defaultValue = RecursiveCharacterChunkingStrategy.NAME} cannot be compared by a text scan, so
 * those keys are invisible here. Closing that would need annotation processing or a running container.
 */
class ConfigDefaultsTest {

    /**
     * Matches the single-line form and the wrapped constructor-parameter form. Whitespace is collapsed
     * before matching so a line break between {@code name} and {@code defaultValue} is irrelevant, and
     * every separator is {@code \s*} so the pattern does not depend on one formatting style — the exact
     * mistake that made this whole test inert. Annotations with no {@code defaultValue} simply don't
     * match; there is nothing to check.
     */
    private static final Pattern WITH_DEFAULT = Pattern.compile(
            "@ConfigProperty\\(\\s*name\\s*=\\s*\"([^\"]+)\"\\s*,\\s*defaultValue\\s*=\\s*\"([^\"]*)\"\\s*\\)");

    /**
     * Keys reached by something other than a {@code @ConfigProperty} injection point, and therefore
     * legitimately absent from the code scan. Anything not listed here that has no injection point is a
     * dead key — usually the debris of a rename.
     */
    private static final Set<String> REACHED_ANOTHER_WAY = Set.of(
            // Resolved dynamically by name through MicroProfile Config; see LlmProfiles.
            "app.llm.profile.answer.model",
            "app.llm.profile.answer.temperature",
            "app.llm.profile.answer.max-tokens",
            "app.llm.profile.lite.model",
            "app.llm.profile.lite.temperature",
            "app.llm.profile.lite.max-tokens",
            // Interpolated into @Scheduled(every = "{…}") rather than injected.
            "app.ingestion.poll-interval",
            "app.indexing.poll-interval",
            "app.scheduler.forward-interval",
            "app.scheduler.discovery-interval",
            "app.retention.poll-interval",
            "app.digest.poll-interval",
            "app.connections.health-interval");

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

    /**
     * The pattern must actually match this codebase's formatting. Asserted separately from the
     * comparison above because a scan that silently matches nothing passes every other test in this
     * class — which is precisely how the original version stayed green while enforcing nothing.
     */
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

    /** Proves the comparison reports a disagreement rather than merely never finding one. */
    @Test
    void reportsAPlantedDrift() {
        String planted = "@ConfigProperty(name = \"app.embedding.dimension\", defaultValue = \"384\") int dim;";

        List<String> mismatches = mismatchesIn(planted, Map.of("app.embedding.dimension", "768"), "Planted.java");

        Assertions.assertEquals(1, mismatches.size(), "the planted drift must be reported: " + mismatches);
        Assertions.assertTrue(mismatches.get(0).contains("384") && mismatches.get(0).contains("768"),
                "the report must name both values: " + mismatches.get(0));
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

    /**
     * The reverse direction, which never existed: a key shipped in {@code application.properties} that
     * nothing reads. Renaming a property and missing one injection point leaves the old key sitting
     * there looking authoritative while the code runs on a default — silent, and invisible to the
     * forward check, which only looks at keys it finds in the code.
     */
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

    /** Every allowlisted key must still be shipped — otherwise the allowlist is itself stale. */
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

    // ---- internals ---------------------------------------------------------------------------

    /** Extracted so {@link #reportsAPlantedDrift()} can exercise the comparison on a literal source. */
    private static List<String> mismatchesIn(String javaSource, Map<String, String> properties, String name) {
        List<String> mismatches = new ArrayList<>();
        Matcher m = WITH_DEFAULT.matcher(collapse(javaSource));
        while (m.find()) {
            String key = m.group(1);
            String codeDefault = m.group(2);
            String fileValue = properties.get(key);
            // Only keys the properties file actually ships are checked. A code default for a key
            // that is deliberately unset (e.g. an optional override) has nothing to disagree with.
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
