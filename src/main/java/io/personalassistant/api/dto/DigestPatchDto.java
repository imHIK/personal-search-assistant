package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.Durations;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.service.DigestPatch;
import io.personalassistant.domain.service.Patched;
import java.time.Duration;

/**
 * PATCH body: an absent key is left untouched and an explicit null clears the field (see {@link PatchBody}).
 */
public record DigestPatchDto(JsonNode body) {

    /**
     * @throws IllegalArgumentException on an unparseable window or interval, a wrong JSON type, or a null
     *                                  name
     */
    public DigestPatch toPatch() {
        PatchBody patch = new PatchBody(body);
        return new DigestPatch(
                patch.requiredText("name"),
                patch.text("query"),
                patch.strings("knowledgeIds"),
                patch.map("filters"),
                window(patch),
                schedule(patch),
                patch.text("taskId"),
                patch.bool("useLlm"),
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
     * Cron wins over interval, as on create. There is no clearing the cadence: pausing already means never
     * run.
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

    static String checkedWindow(String window) {
        if (window == null || window.isBlank() || Durations.parse(window) != null) {
            return window;
        }
        throw new IllegalArgumentException("window \"" + window + "\" is not a duration");
    }
}
