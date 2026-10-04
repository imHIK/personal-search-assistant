package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgeService;
import java.util.List;
import java.util.Map;

/**
 * Null schedule and chunking fields inherit: the connector default, then the global one. Cron wins over
 * interval.
 *
 * @param connectionId null uses the type's default connection
 */
public record KnowledgeDto(
        String name,
        String type,
        String connectionId,
        Map<String, Object> auth,
        Map<String, Object> inputs,
        String cron,
        String interval,
        Boolean scheduleEnabled,
        Boolean backfillEnabled,
        String chunkingStrategy,
        Integer chunkingMaxSize,
        Integer chunkingOverlap,
        List<String> chunkingSeparators,
        String retentionPeriod,
        String enrichTaskId) {

    public KnowledgeService.NewKnowledge toRequest() {
        Knowledge.Config defaults = Knowledge.Config.defaults();
        Knowledge.Config config = new Knowledge.Config(
                new Knowledge.ScheduleSettings(
                        cron,
                        interval,
                        scheduleEnabled != null ? scheduleEnabled : defaults.scheduleSettings().enabled()),
                defaults.webhookSettings(),
                new Knowledge.Backfill(backfillEnabled != null ? backfillEnabled : defaults.backfill().enabled()),
                new Knowledge.ChunkingSettings(chunkingStrategy, chunkingMaxSize, chunkingOverlap, chunkingSeparators),
                // Null inherits the connector default, then the global one, which is unset: never expire.
                new Knowledge.Retention(retentionPeriod),
                new Knowledge.EnrichmentSettings(enrichTaskId));
        return new KnowledgeService.NewKnowledge(name, SourceType.valueOf(type), connectionId, auth, inputs, config);
    }
}
