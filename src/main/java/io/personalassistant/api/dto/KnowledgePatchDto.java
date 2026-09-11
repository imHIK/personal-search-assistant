package io.personalassistant.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.domain.service.KnowledgePatch;
import io.personalassistant.domain.service.Patched;

/**
 * Inbound payload for a partial edit ({@code PATCH /api/knowledge/{id}}). A field that is <b>absent</b>
 * is left untouched; a field present as JSON {@code null} is <b>cleared</b> back to inherit-the-default;
 * anything else is set.
 *
 * <p>Reads the body as a tree rather than binding it — see {@link PatchBody}. That is what makes the
 * clears real: {@code cron: null} is how the console moves a source off a custom schedule and back onto
 * a preset interval, and a blank chunk size is how it hands tuning back to the server default. Bound to
 * plain fields, both were indistinguishable from "unchanged", so the old cron kept winning over the new
 * interval and a chunking override could be set but never removed.
 *
 * <p>{@code name}, {@code auth}, {@code inputs} and {@code type} have no empty state and reject an
 * explicit null with a 400 rather than writing one: a source with no name is a blank row, and one with
 * no inputs has nothing to walk. Clearing a map means sending {@code {}}, which is expressible and
 * means something different.
 */
public record KnowledgePatchDto(JsonNode body) {

    /**
     * @throws IllegalArgumentException on an unknown connector type, a field carrying the wrong JSON
     *                                  type, or a null where the field has no empty state. The resource
     *                                  maps all of these to a 400
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
