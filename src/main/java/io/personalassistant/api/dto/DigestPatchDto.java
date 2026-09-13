package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.service.DigestPatch;
import io.personalassistant.domain.service.Patched;
import java.time.Duration;

/**
 * Inbound payload for a partial edit ({@code PATCH /api/digests/{id}}). A field that is <b>absent</b>
 * is left untouched; a field present as JSON {@code null} is <b>cleared</b>; anything else is set.
 *
 * <p>The previous shape of this endpoint accepted {@code enabled} alone, and everything else meant
 * deleting the digest and creating a new one — which dropped its runs, and so its memory of what it had
 * already reported. That is why every field is handled here now.
 *
 * <p>Reads the body as a tree rather than binding it: see {@link PatchBody} for why the distinction
 * between an absent key and an explicit null cannot survive binding, and what it broke.
 */
public record DigestPatchDto(JsonNode body) {

    /**
     * @throws IllegalArgumentException on a window or interval that is present but unparseable, on a
     *                                  field carrying the wrong JSON type, or on a null {@code name} —
     *                                  a digest has to keep one. Silently ignoring any of these would
     *                                  turn a typo into a digest with no time bound, or one that never
     *                                  runs, and answer 200 either way
     */
    public DigestPatch toPatch() {
        PatchBody patch = new PatchBody(body);
        return new DigestPatch(
                patch.requiredText("name"),
                patch.text("query"),
                patch.text("sourceEntityId"),
                patch.strings("knowledgeIds"),
                patch.map("filters"),
                window(patch),
                schedule(patch),
                patch.text("taskId"),
                patch.integer("topK"),
                patch.bool("collapseDuplicates"),
                patch.integer("maxChunksPerEntity"),
                patch.bool("onlyNew"),
                patch.bool("enabled"),
                patch.strings("channelIds"));
    }

    private static Patched<String> window(PatchBody patch) {
        Patched<String> window = patch.text("window");
        return window.present() ? Patched.of(checkedWindow(window.value())) : window;
    }

    /**
     * Cron wins over interval, as it does on create. Clearing the cadence is not offered: a digest with
     * none would simply never run, which is what pausing it already means — and means reversibly.
     */
    private static Patched<SyncSchedule> schedule(PatchBody patch) {
        String cron = patch.text("cron").value();
        if (cron != null && !cron.isBlank()) {
            return Patched.of(SyncSchedule.ofCron(cron));
        }
        String interval = patch.text("interval").value();
        if (interval == null || interval.isBlank()) {
            return Patched.absent();
        }
        Duration parsed = Durations.parse(interval);
        if (parsed == null) {
            throw new IllegalArgumentException("interval \"" + interval + "\" is not a duration");
        }
        return Patched.of(SyncSchedule.ofInterval(parsed));
    }

    /** Shared with the create path, so a typo is a 400 whichever endpoint it arrives at. */
    static String checkedWindow(String window) {
        if (window == null || window.isBlank() || Durations.parse(window) != null) {
            return window;
        }
        throw new IllegalArgumentException("window \"" + window + "\" is not a duration");
    }
}
