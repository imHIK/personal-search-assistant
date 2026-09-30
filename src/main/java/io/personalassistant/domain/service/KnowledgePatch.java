package io.personalassistant.domain.service;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;

/**
 * Absent means untouched, present sets the field, present-with-null clears it. Optionality lives on the
 * leaves of each sub-patch, so a caller can flip scheduleEnabled without resending cron and interval.
 * {@code type} is carried only so a change can be rejected.
 */
public record KnowledgePatch(
        Patched<String> name,
        Patched<SourceType> type,
        Patched<Map<String, Object>> auth,
        Patched<Map<String, Object>> inputs,
        SchedulePatch schedule,
        WebhookPatch webhook,
        Patched<Boolean> backfillEnabled,
        ChunkingPatch chunking,
        Patched<String> retentionPeriod) {

    public KnowledgePatch {
        name = Patched.orAbsent(name);
        type = Patched.orAbsent(type);
        auth = Patched.orAbsent(auth);
        inputs = Patched.orAbsent(inputs);
        schedule = schedule == null ? SchedulePatch.empty() : schedule;
        webhook = webhook == null ? WebhookPatch.empty() : webhook;
        backfillEnabled = Patched.orAbsent(backfillEnabled);
        chunking = chunking == null ? ChunkingPatch.empty() : chunking;
        retentionPeriod = Patched.orAbsent(retentionPeriod);
    }

    public record SchedulePatch(Patched<String> cron, Patched<String> interval,
                               Patched<Boolean> enabled) {
        public SchedulePatch {
            cron = Patched.orAbsent(cron);
            interval = Patched.orAbsent(interval);
            enabled = Patched.orAbsent(enabled);
        }

        public static SchedulePatch empty() {
            return new SchedulePatch(Patched.absent(), Patched.absent(), Patched.absent());
        }
    }

    public record WebhookPatch(Patched<Boolean> enabled, Patched<String> secret) {
        public WebhookPatch {
            enabled = Patched.orAbsent(enabled);
            secret = Patched.orAbsent(secret);
        }

        public static WebhookPatch empty() {
            return new WebhookPatch(Patched.absent(), Patched.absent());
        }
    }

    /** Applies to entities indexed afterwards; existing chunks are not re-chunked. */
    public record ChunkingPatch(Patched<String> strategy, Patched<Integer> maxSize,
                                Patched<Integer> overlap, Patched<List<String>> separators) {
        public ChunkingPatch {
            strategy = Patched.orAbsent(strategy);
            maxSize = Patched.orAbsent(maxSize);
            overlap = Patched.orAbsent(overlap);
            separators = Patched.orAbsent(separators);
        }

        public static ChunkingPatch empty() {
            return new ChunkingPatch(Patched.absent(), Patched.absent(), Patched.absent(),
                    Patched.absent());
        }

        public boolean isEmpty() {
            return !strategy.present() && !maxSize.present() && !overlap.present()
                    && !separators.present();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A null argument leaves the field absent: the builder cannot express "clear". KnowledgePatchDto builds
     * the record directly for that.
     */
    public static final class Builder {
        private Patched<String> name = Patched.absent();
        private Patched<SourceType> type = Patched.absent();
        private Patched<Map<String, Object>> auth = Patched.absent();
        private Patched<Map<String, Object>> inputs = Patched.absent();
        private Patched<String> cron = Patched.absent();
        private Patched<String> interval = Patched.absent();
        private Patched<Boolean> scheduleEnabled = Patched.absent();
        private Patched<Boolean> webhookEnabled = Patched.absent();
        private Patched<String> webhookSecret = Patched.absent();
        private Patched<Boolean> backfillEnabled = Patched.absent();
        private Patched<String> chunkingStrategy = Patched.absent();
        private Patched<Integer> chunkingMaxSize = Patched.absent();
        private Patched<Integer> chunkingOverlap = Patched.absent();
        private Patched<List<String>> chunkingSeparators = Patched.absent();
        private Patched<String> retentionPeriod = Patched.absent();

        public Builder name(String v) {
            this.name = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder type(SourceType v) {
            this.type = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder auth(Map<String, Object> v) {
            this.auth = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder inputs(Map<String, Object> v) {
            this.inputs = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder cron(String v) {
            this.cron = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder interval(String v) {
            this.interval = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder scheduleEnabled(Boolean v) {
            this.scheduleEnabled = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder webhookEnabled(Boolean v) {
            this.webhookEnabled = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder webhookSecret(String v) {
            this.webhookSecret = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder backfillEnabled(Boolean v) {
            this.backfillEnabled = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder chunkingStrategy(String v) {
            this.chunkingStrategy = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder chunkingMaxSize(Integer v) {
            this.chunkingMaxSize = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder chunkingOverlap(Integer v) {
            this.chunkingOverlap = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder chunkingSeparators(List<String> v) {
            this.chunkingSeparators = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public Builder retentionPeriod(String v) {
            this.retentionPeriod = v == null ? Patched.absent() : Patched.of(v);
            return this;
        }

        public KnowledgePatch build() {
            return new KnowledgePatch(name, type, auth, inputs,
                    new SchedulePatch(cron, interval, scheduleEnabled),
                    new WebhookPatch(webhookEnabled, webhookSecret),
                    backfillEnabled,
                    new ChunkingPatch(chunkingStrategy, chunkingMaxSize, chunkingOverlap, chunkingSeparators),
                    retentionPeriod);
        }
    }
}
