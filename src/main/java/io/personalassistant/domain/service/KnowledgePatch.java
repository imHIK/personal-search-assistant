package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A partial edit to an existing {@link Knowledge}. Every value is an {@link Optional}: {@code empty}
 * means "not part of this edit — leave untouched", {@code present} means "set to this value". That
 * present-vs-absent distinction is what lets {@code KnowledgeService.update} diff precisely and route
 * by <em>what actually changed</em> (see {@code knowledge-edit-design.md}).
 *
 * <p><b>Shape.</b> The patch mirrors {@link Knowledge}'s structure where that structure is real — the
 * cohesive, multi-field config groups {@link Knowledge.ScheduleSettings} and
 * {@link Knowledge.WebhookSettings} get their own {@link SchedulePatch} / {@link WebhookPatch}
 * sub-patches — but stays flat for single fields. Crucially the optionality lives on the <em>leaves</em>
 * inside each sub-patch (the sub-patch itself is always present), not on the group: that is what lets a
 * caller flip just {@code scheduleEnabled} without having to resend {@code cron}/{@code interval}. A
 * group-level {@code Optional<ScheduleSettings>} could not express that — it would force a
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
        Optional<String> name,
        // connector — type is immutable (carried only to reject a change); auth is freely editable
        Optional<SourceType> type,
        Optional<Map<String, Object>> auth,
        // what to index
        Optional<Map<String, Object>> inputs,
        // operational config — mirrors Knowledge.Config (schedule, webhook, backfill, chunking)
        SchedulePatch schedule,
        WebhookPatch webhook,
        Optional<Boolean> backfillEnabled,
        ChunkingPatch chunking) {

    /** Normalize any {@code null} to its empty form so callers can pass either. */
    public KnowledgePatch {
        name = orEmpty(name);
        type = orEmpty(type);
        auth = orEmpty(auth);
        inputs = orEmpty(inputs);
        schedule = schedule == null ? SchedulePatch.empty() : schedule;
        webhook = webhook == null ? WebhookPatch.empty() : webhook;
        backfillEnabled = orEmpty(backfillEnabled);
        chunking = chunking == null ? ChunkingPatch.empty() : chunking;
    }

    /** Leaf-optional patch over {@link Knowledge.ScheduleSettings}. */
    public record SchedulePatch(Optional<String> cron, Optional<String> interval, Optional<Boolean> enabled) {
        public SchedulePatch {
            cron = orEmpty(cron);
            interval = orEmpty(interval);
            enabled = orEmpty(enabled);
        }

        public static SchedulePatch empty() {
            return new SchedulePatch(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** Leaf-optional patch over {@link Knowledge.WebhookSettings}. */
    public record WebhookPatch(Optional<Boolean> enabled, Optional<String> secret) {
        public WebhookPatch {
            enabled = orEmpty(enabled);
            secret = orEmpty(secret);
        }

        public static WebhookPatch empty() {
            return new WebhookPatch(Optional.empty(), Optional.empty());
        }
    }

    /**
     * Leaf-optional patch over {@link Knowledge.ChunkingSettings}. A chunking change is a pure
     * config-class edit — applied in place, taking effect on entities indexed afterwards, with no
     * re-chunk of existing chunks (see {@code knowledge-edit-design.md}).
     */
    public record ChunkingPatch(Optional<String> strategy, Optional<Integer> maxSize,
                                Optional<Integer> overlap, Optional<List<String>> separators) {
        public ChunkingPatch {
            strategy = orEmpty(strategy);
            maxSize = orEmpty(maxSize);
            overlap = orEmpty(overlap);
            separators = orEmpty(separators);
        }

        public static ChunkingPatch empty() {
            return new ChunkingPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** True when this patch carries no chunking change (nothing to apply). */
        public boolean isEmpty() {
            return strategy.isEmpty() && maxSize.isEmpty() && overlap.isEmpty() && separators.isEmpty();
        }
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder with flat setters taking plain (nullable) values — a {@code null} argument leaves
     * that field absent. Keeps call sites (DTO mapping, tests) readable without wrapping every argument
     * in {@link Optional} or reaching into the sub-patches.
     */
    public static final class Builder {
        private Optional<String> name = Optional.empty();
        private Optional<SourceType> type = Optional.empty();
        private Optional<Map<String, Object>> auth = Optional.empty();
        private Optional<Map<String, Object>> inputs = Optional.empty();
        private Optional<String> cron = Optional.empty();
        private Optional<String> interval = Optional.empty();
        private Optional<Boolean> scheduleEnabled = Optional.empty();
        private Optional<Boolean> webhookEnabled = Optional.empty();
        private Optional<String> webhookSecret = Optional.empty();
        private Optional<Boolean> backfillEnabled = Optional.empty();
        private Optional<String> chunkingStrategy = Optional.empty();
        private Optional<Integer> chunkingMaxSize = Optional.empty();
        private Optional<Integer> chunkingOverlap = Optional.empty();
        private Optional<List<String>> chunkingSeparators = Optional.empty();

        public Builder name(String v) {
            this.name = Optional.ofNullable(v);
            return this;
        }

        public Builder type(SourceType v) {
            this.type = Optional.ofNullable(v);
            return this;
        }

        public Builder auth(Map<String, Object> v) {
            this.auth = Optional.ofNullable(v);
            return this;
        }

        public Builder inputs(Map<String, Object> v) {
            this.inputs = Optional.ofNullable(v);
            return this;
        }

        public Builder cron(String v) {
            this.cron = Optional.ofNullable(v);
            return this;
        }

        public Builder interval(String v) {
            this.interval = Optional.ofNullable(v);
            return this;
        }

        public Builder scheduleEnabled(Boolean v) {
            this.scheduleEnabled = Optional.ofNullable(v);
            return this;
        }

        public Builder webhookEnabled(Boolean v) {
            this.webhookEnabled = Optional.ofNullable(v);
            return this;
        }

        public Builder webhookSecret(String v) {
            this.webhookSecret = Optional.ofNullable(v);
            return this;
        }

        public Builder backfillEnabled(Boolean v) {
            this.backfillEnabled = Optional.ofNullable(v);
            return this;
        }

        public Builder chunkingStrategy(String v) {
            this.chunkingStrategy = Optional.ofNullable(v);
            return this;
        }

        public Builder chunkingMaxSize(Integer v) {
            this.chunkingMaxSize = Optional.ofNullable(v);
            return this;
        }

        public Builder chunkingOverlap(Integer v) {
            this.chunkingOverlap = Optional.ofNullable(v);
            return this;
        }

        public Builder chunkingSeparators(List<String> v) {
            this.chunkingSeparators = Optional.ofNullable(v);
            return this;
        }

        public KnowledgePatch build() {
            return new KnowledgePatch(name, type, auth, inputs,
                    new SchedulePatch(cron, interval, scheduleEnabled),
                    new WebhookPatch(webhookEnabled, webhookSecret),
                    backfillEnabled,
                    new ChunkingPatch(chunkingStrategy, chunkingMaxSize, chunkingOverlap, chunkingSeparators));
        }
    }
}
