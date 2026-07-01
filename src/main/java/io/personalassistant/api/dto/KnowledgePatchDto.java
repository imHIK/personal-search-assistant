package io.personalassistant.api.dto;

import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgePatch;
import java.util.Map;

/**
 * Inbound payload for a partial edit ({@code PATCH /api/knowledge/{id}}). Patch semantics: a field
 * that is absent (or JSON {@code null}) is left untouched; only fields present with a value are
 * changed. This mirrors the boxed-nullable convention the create {@link KnowledgeDto} already uses
 * for {@code scheduleEnabled}/{@code backfillEnabled}.
 *
 * <p><b>Limitation:</b> because an omitted JSON field and an explicit JSON {@code null} both arrive
 * as a {@code null} Java field, this DTO cannot express "clear back to inherit" for {@code cron}/
 * {@code interval} — a null there means "unchanged", not "unset". The service-layer
 * {@link KnowledgePatch} models present-vs-absent precisely; a future typed-null wire format could
 * expose the clear semantics if needed.
 *
 * @param type present only so an attempt to change the immutable connector type can be rejected (400)
 */
public record KnowledgePatchDto(
        String name,
        String type,
        Map<String, Object> auth,
        Map<String, Object> inputs,
        String cron,
        String interval,
        Boolean scheduleEnabled,
        Boolean backfillEnabled,
        Boolean webhookEnabled,
        String webhookSecret) {

    public KnowledgePatch toPatch() {
        return KnowledgePatch.builder()
                .name(name)
                .type(type == null ? null : SourceType.valueOf(type)) // bad enum → IllegalArgumentException → 400
                .auth(auth)
                .inputs(inputs)
                .cron(cron)
                .interval(interval)
                .scheduleEnabled(scheduleEnabled)
                .backfillEnabled(backfillEnabled)
                .webhookEnabled(webhookEnabled)
                .webhookSecret(webhookSecret)
                .build();
    }
}
