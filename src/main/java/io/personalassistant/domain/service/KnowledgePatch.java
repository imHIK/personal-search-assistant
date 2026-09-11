package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;

/**
 * A partial edit to an existing {@link Knowledge}. Every value is a {@link Patched}: absent means "not
 * part of this edit — leave untouched", present means "set to this value", and present-with-null means
 * "clear it". That present-vs-absent distinction is what lets {@code KnowledgeService.update} diff
 * precisely and route by <em>what actually changed</em> (see {@code knowledge-edit-design.md}).

 * <p>The third state is not decoration. Moving a source off a custom cron and back onto a preset
 * interval means sending {@code cron: null}; read as "unchanged", the old cron stayed and went on
 * winning over the interval, so the console could put a source onto a custom schedule and never take
 * it off again.
 *
 * <p><b>Shape.</b> The patch mirrors {@link Knowledge}'s structure where that structure is real — the
 * cohesive, multi-field config groups {@link Knowledge.ScheduleSettings} and
 * {@link Knowledge.WebhookSettings} get their own {@link SchedulePatch} / {@link WebhookPatch}
 * sub-patches — but stays flat for single fields. Crucially the optionality lives on the <em>leaves</em>
 * inside each sub-patch (the sub-patch itself is always present), not on the group: that is what lets a
 * caller flip just {@code scheduleEnabled} without having to resend {@code cron}/{@code interval}. A
 * group-level {@code Patched<ScheduleSettings>} could not express that — it would force a
 * whole-group replace.
 *
 * <p>{@code type} is included only so an attempt to change it can be <em>rejected</em> — the connector
 * type is immutable — so it sits flat rather than grouped with the (freely editable) {@code auth}.
 *
 * <p>The {@link Builder} keeps flat setters ({@code cron}, {@code scheduleEnabled}, …) for ergonomic
 * call sites; it folds them into the sub-patches at {@link Builder#build()}.
 */
public record KnowledgePatch(
        // identity
        Patched<String> name,
        // connector — type is immutable (carried only to reject a change); auth is freely editable
        Patched<SourceType> type,
        Patched<Map<String, Object>> auth,
        // what to index
        Patched<Map<String, Object>> inputs,
        // operational config — mirrors Knowledge.Config (schedule, webhook, backfill, chunking)
        SchedulePatch schedule,
        WebhookPatch webhook,
        Patched<Boolean> backfillEnabled,
        ChunkingPatch chunking,
        Patched<String> retentionPeriod) {

    /** Normalize any {@code null} to its absent form so callers can pass either. */
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

    /** Leaf-optional patch over {@link Knowledge.ScheduleSettings}. */
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

    /** Leaf-optional patch over {@link Knowledge.WebhookSettings}. */
    public record WebhookPatch(Patched<Boolean> enabled, Patched<String> secret) {
        public WebhookPatch {
            enabled = Patched.orAbsent(enabled);
            secret = Patched.orAbsent(secret);
        }

        public static WebhookPatch empty() {
            return new WebhookPatch(Patched.absent(), Patched.absent());
        }
    }

    /**
     * Leaf-optional patch over {@link Knowledge.ChunkingSettings}. A chunking change is a pure
     * config-class edit — applied in place, taking effect on entities indexed afterwards, with no
     * re-chunk of existing chunks (see {@code knowledge-edit-design.md}).
     */
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

        /** True when this patch carries no chunking change (nothing to apply). */
        public boolean isEmpty() {
            return !strategy.present() && !maxSize.present() && !overlap.present()
                    && !separators.present();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder with flat setters taking plain (nullable) values — a {@code null} argument leaves
     * that field absent. Keeps call sites readable without reaching into the sub-patches.
     *
     * <p>It deliberately cannot express the "clear this field" state: a plain null already means
     * "absent" here, and giving one argument two meanings is how the wire format went wrong in the
     * first place. The one caller that needs clearing — {@code KnowledgePatchDto}, reading a request
     * body where the difference is real — builds the record directly.
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
