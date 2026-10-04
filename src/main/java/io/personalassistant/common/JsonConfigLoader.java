package io.personalassistant.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/** Loads a bundled JSON config document, or an external file that replaces it wholesale (never merged). */
public final class JsonConfigLoader {

    private static final Logger LOG = Logger.getLogger(JsonConfigLoader.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConfigLoader() {
    }

    /** @throws IllegalStateException if the document is missing, unreadable or not valid JSON */
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
        // Quarkus loads application resources through the context loader; the class's own loader misses them
        // in some packaging modes.
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

    public static String requiredText(JsonNode node, String field, String where) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing or blank \"" + field + "\" in " + where);
        }
        return value.asText();
    }
}
