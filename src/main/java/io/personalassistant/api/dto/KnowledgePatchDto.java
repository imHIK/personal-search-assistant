package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgePatch;
import io.personalassistant.domain.service.Patched;

/**
 * PATCH body: an absent key is left untouched and an explicit null clears the field back to inherit (see
 * {@link PatchBody}).
 */
public record KnowledgePatchDto(JsonNode body) {

    /**
     * @throws IllegalArgumentException on an unknown connector type, a wrong JSON type, or a null where the
     *                                  field has no empty state
     */
    public KnowledgePatch toPatch() {
        PatchBody patch = new PatchBody(body);
        String type = patch.requiredText("type").value();
        return new KnowledgePatch(
                patch.requiredText("name"),
                // Carried only so an attempt to change the immutable connector type can be rejected.
                type == null ? Patched.absent() : Patched.of(SourceType.valueOf(type)),
                patch.requiredMap("auth"),
                patch.requiredMap("inputs"),
                new KnowledgePatch.SchedulePatch(
                        patch.text("cron"),
                        patch.text("interval"),
                        patch.bool("scheduleEnabled")),
                new KnowledgePatch.WebhookPatch(
                        patch.bool("webhookEnabled"),
                        patch.text("webhookSecret")),
                patch.bool("backfillEnabled"),
                new KnowledgePatch.ChunkingPatch(
                        patch.text("chunkingStrategy"),
                        patch.integer("chunkingMaxSize"),
                        patch.integer("chunkingOverlap"),
                        patch.strings("chunkingSeparators")),
                patch.text("retentionPeriod"));
    }
}
