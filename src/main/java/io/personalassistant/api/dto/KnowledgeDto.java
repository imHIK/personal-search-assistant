package io.personalassistant.api.dto;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgeService;
import java.util.List;
import java.util.Map;

/**
 * Inbound payload to register a knowledge. Kept separate from the domain {@link Knowledge} so the
 * wire contract can evolve independently; defaults (schedule/backfill) are applied here.
 *
 * @param name            human-friendly label
 * @param type            connector type name (e.g. {@code LOCAL_FS})
 * @param connectionId    id of the {@code Connection} to authenticate through, or null to use the
 *                        type's default connection (ignored by no-auth connectors like LOCAL_FS)
 * @param auth            inline connector credentials fallback (normally omitted when using a connection)
 * @param inputs          what to index (e.g. {@code {"rootPath": "/home/me/Documents"}})
 * @param cron            custom forward-sync cron, or null to inherit (connector then global default)
 * @param interval        custom forward-sync interval (e.g. {@code "15m"}, {@code "1d"}), or null to
 *                        inherit; ignored when {@code cron} is also set (cron wins)
 * @param scheduleEnabled whether forward scheduling is on (default true)
 * @param backfillEnabled whether to walk history backward on activation (default true)
 * @param chunkingStrategy chunking strategy for this knowledge (e.g. {@code "recursive"}, {@code "character"},
 *                        {@code "fixed-size"}, {@code "token"}); null to inherit the global default
 * @param chunkingMaxSize  target chunk size (characters, or tokens for {@code token}); null to inherit
 * @param chunkingOverlap  overlap between adjacent chunks in the same unit; null to inherit
 * @param chunkingSeparators ordered separators for {@code recursive}/{@code character}; null/empty to inherit
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
        List<String> chunkingSeparators) {

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
                new Knowledge.Backfill(backfillEnabled != null ? backfillEnabled : defaults.backfill().enabled()),
                // Any chunking field left null/empty means "inherit the global default at index time".
                new Knowledge.ChunkingSettings(chunkingStrategy, chunkingMaxSize, chunkingOverlap, chunkingSeparators));
        return new KnowledgeService.NewKnowledge(name, SourceType.valueOf(type), connectionId, auth, inputs, config);
    }
}
