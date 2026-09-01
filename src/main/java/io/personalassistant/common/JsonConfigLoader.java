package io.personalassistant.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Loads a JSON config document that ships with the application and may be replaced by an external file.
 *
 * <p><strong>Why JSON here and not {@code application.properties}.</strong> Some configuration is
 * <em>content</em> rather than a knob: prompts are multi-line prose, field sets are lists scoped by
 * connector. Flattening those into properties keys produces things like
 * {@code app.prompts.answer.system.line1}, which is unreadable and unextendable. Content of that shape
 * belongs in a structured document.
 *
 * <p>The inverse also holds, and is why this loader is deliberately narrow: numeric and boolean knobs
 * stay in Quarkus config, where they keep env-var indirection ({@code ${GEMINI_API_KEY:}}),
 * {@code %dev}/{@code %prod} profiles, and startup type conversion. Moving those here would mean
 * reimplementing all three by hand.
 *
 * <p><strong>Bundled default, optional external override.</strong> The classpath copy is the shipped
 * default and always exists. An operator (later, a user) can point a config key at a file on disk to
 * replace it wholesale. Replace rather than merge: a half-overridden prompt catalogue — some entries
 * from the file, some from the jar — is far harder to reason about than "your file, or ours".
 *
 * <p>Failures are loud. A malformed or missing document is a startup failure, not a warning: every
 * caller here supplies something the application cannot sensibly run without, and degrading silently
 * would mean answering with no prompt or embedding with no context fields.
 */
public final class JsonConfigLoader {

    private static final Logger LOG = Logger.getLogger(JsonConfigLoader.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConfigLoader() {
    }

    /**
     * Read {@code classpathResource}, or the file at {@code overridePath} when one is configured.
     *
     * @param classpathResource resource path of the bundled default, e.g. {@code config/prompts.json}
     * @param overridePath      optional filesystem path replacing it entirely; blank counts as absent
     * @return the parsed document root
     * @throws IllegalStateException if the document is absent, unreadable, or not valid JSON
     */
    public static JsonNode load(String classpathResource, Optional<String> overridePath) {
        String external = ConfigText.orNull(overridePath);
        if (external != null) {
            return loadFile(Path.of(external), classpathResource);
        }
        return loadClasspath(classpathResource);
    }

    private static JsonNode loadFile(Path path, String classpathResource) {
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Configured override for " + classpathResource
                    + " is not readable: " + path.toAbsolutePath()
                    + ". Point the property at an existing file, or unset it to use the bundled default.");
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(path));
            LOG.info("Loaded " + classpathResource + " from override file " + path.toAbsolutePath());
            return requireObject(root, path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse override file " + path.toAbsolutePath()
                    + " for " + classpathResource, e);
        }
    }

    private static JsonNode loadClasspath(String resource) {
        // The thread context loader is what Quarkus sets up for application resources; the class's own
        // loader would miss them in some packaging modes.
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = JsonConfigLoader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled config " + resource + " is missing from the "
                        + "classpath. It ships in src/main/resources and the application cannot start "
                        + "without it.");
            }
            return requireObject(MAPPER.readTree(in), resource);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse bundled config " + resource, e);
        }
    }

    private static JsonNode requireObject(JsonNode root, String source) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Config " + source + " must be a JSON object at the top level");
        }
        return root;
    }

    /**
     * Read a required text field, failing with the document and field named. Callers are parsing a
     * config file a human edited, so "which file, which field" is the whole value of the message.
     */
    public static String requiredText(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing or blank \"" + field + "\" in " + where);
        }
        return value.asText();
    }
}
