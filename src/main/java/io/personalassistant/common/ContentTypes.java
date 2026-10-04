package io.personalassistant.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.apache.tika.Tika;

/**
 * {@code Files.probeContentType} returns null for many types on macOS ({@code .xlsx} among them), which would
 * drop a spreadsheet to the fallback parser. Tika reads the name and leading bytes; probeContentType is the
 * fallback, octet-stream the last resort.
 */
public final class ContentTypes {

    private static final Logger LOG = Logger.getLogger(ContentTypes.class.getName());

    public static final String UNKNOWN = "application/octet-stream";

    private static final Tika TIKA = new Tika();

    private ContentTypes() {
    }

    /** Never throws and never returns null. */
    public static String detect(Path path) {
        try {
            String detected = TIKA.detect(path);
            if (detected != null && !detected.isBlank() && !UNKNOWN.equals(detected)) {
                return detected;
            }
        } catch (IOException | RuntimeException e) {
            LOG.fine(() -> "Tika could not detect the content type of " + path + ": " + e);
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException e) {
            LOG.fine(() -> "probeContentType failed for " + path + ": " + e);
        }
        return UNKNOWN;
    }
}
