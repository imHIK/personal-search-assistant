package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgeService;
import java.util.Map;

/**
 * Inbound payload to register a knowledge. Kept separate from the domain {@link Knowledge} so the
 * wire contract can evolve independently; defaults (schedule/backfill) are applied here.
 *
 * @param name            human-friendly label
 * @param type            connector type name (e.g. {@code LOCAL_FS})
 * @param auth            opaque connector credentials
 * @param inputs          what to index (e.g. {@code {"rootPath": "/home/me/Documents"}})
 * @param cron            forward-sync cron, or null for the default
 * @param scheduleEnabled whether forward scheduling is on (default true)
 * @param backfillEnabled whether to walk history backward on activation (default true)
 */
public record KnowledgeDto(
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> inputs,
        String cron,
        Boolean scheduleEnabled,
        Boolean backfillEnabled) {

    public KnowledgeService.NewKnowledge toRequest() {
        Knowledge.Config defaults = Knowledge.Config.defaults();
        Knowledge.Config config = new Knowledge.Config(
                new Knowledge.ScheduleSettings(
                        cron != null ? cron : defaults.scheduleSettings().cron(),
                        scheduleEnabled != null ? scheduleEnabled : defaults.scheduleSettings().enabled()),
                defaults.webhookSettings(),
                new Knowledge.Backfill(backfillEnabled != null ? backfillEnabled : defaults.backfill().enabled()));
        return new KnowledgeService.NewKnowledge(name, SourceType.valueOf(type), auth, inputs, config);
    }
}
