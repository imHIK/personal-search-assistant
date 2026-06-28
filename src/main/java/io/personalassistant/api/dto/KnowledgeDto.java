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
 * @param cron            custom forward-sync cron, or null to inherit (connector then global default)
 * @param interval        custom forward-sync interval (e.g. {@code "15m"}, {@code "1d"}), or null to
 *                        inherit; ignored when {@code cron} is also set (cron wins)
 * @param scheduleEnabled whether forward scheduling is on (default true)
 * @param backfillEnabled whether to walk history backward on activation (default true)
 */
public record KnowledgeDto(
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> inputs,
        String cron,
        String interval,
        Boolean scheduleEnabled,
        Boolean backfillEnabled) {

    public KnowledgeService.NewKnowledge toRequest() {
        Knowledge.Config defaults = Knowledge.Config.defaults();
        Knowledge.Config config = new Knowledge.Config(
                // cron/interval left null here means "inherit": the resolver falls through to the
                // connector default and then the global default at scheduling time.
                new Knowledge.ScheduleSettings(
                        cron,
                        interval,
                        scheduleEnabled != null ? scheduleEnabled : defaults.scheduleSettings().enabled()),
                defaults.webhookSettings(),
                new Knowledge.Backfill(backfillEnabled != null ? backfillEnabled : defaults.backfill().enabled()));
        return new KnowledgeService.NewKnowledge(name, SourceType.valueOf(type), auth, inputs, config);
    }
}
