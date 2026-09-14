package io.personalassistant.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.apache.tika.Tika;

/**
 * Content-type detection for files on disk.
 *
 * <p>Exists because {@link Files#probeContentType} is not good enough on its own. It delegates to the
 * platform, and on macOS it returns {@code null} for many types — {@code .xlsx} among them. The caller
 * then stores {@code application/octet-stream}, which no dedicated {@code ContentParser} claims, so the
 * file silently falls through to the generic fallback parser with none of the format-specific
 * configuration: a spreadsheet stops being recognised as a spreadsheet, and nothing anywhere reports a
 * problem.
 *
 * <p>Tika's detector reads both the file name and the leading bytes, so it identifies a ZIP-container
 * format like {@code .xlsx} correctly. {@code probeContentType} is kept as a fallback for the case where
 * Tika declines, and the generic {@code application/octet-stream} remains the last resort so detection
 * never fails an ingest.
 */
public final class ContentTypes {

    private static final Logger LOG = Logger.getLogger(ContentTypes.class.getName());

    /** What we report when neither detector can say anything useful. */
    public static final String UNKNOWN = "application/octet-stream";

    /** Thread-safe and cheap to reuse; holds the parsed MIME registry. */
    private static final Tika TIKA = new Tika();

    private ContentTypes() {
    }

    /**
     * Best-effort MIME type for {@code path}. Never throws and never returns null: an undetectable file
     * is {@link #UNKNOWN}, which the fallback parser handles by sniffing the content itself.
     */
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
