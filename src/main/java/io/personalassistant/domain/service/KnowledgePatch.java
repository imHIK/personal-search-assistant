package io.personalassistant.domain.service;

import io.personalassistant.domain.model.enums.SourceType;
import java.util.Map;
import java.util.Optional;

/**
 * A partial edit to an existing {@link io.personalassistant.domain.model.Knowledge}. Every field is
 * an {@link Optional}: {@code empty} means "not part of this edit — leave untouched", {@code present}
 * means "set to this value". This present-vs-absent distinction is what lets
 * {@code KnowledgeService.update} diff precisely and route by <em>what actually changed</em> (see
 * {@code knowledge-edit-design.md}): config-class fields ({@code name}, schedule, webhook, backfill
 * off) are applied in place, while provisioning-class fields ({@code auth}, {@code inputs}, backfill
 * on) trigger re-verify → re-discover → reconcile.
 *
 * <p>{@code type} is included only so an attempt to change it can be rejected — the connector type is
 * immutable.
 */
public record KnowledgePatch(
        Optional<String> name,
        Optional<SourceType> type,
        Optional<Map<String, Object>> auth,
        Optional<Map<String, Object>> inputs,
        Optional<String> cron,
        Optional<String> interval,
        Optional<Boolean> scheduleEnabled,
        Optional<Boolean> backfillEnabled,
        Optional<Boolean> webhookEnabled,
        Optional<String> webhookSecret) {

    /** Normalize any {@code null} field to {@link Optional#empty()} so callers can pass either. */
    public KnowledgePatch {
        name = orEmpty(name);
        type = orEmpty(type);
        auth = orEmpty(auth);
        inputs = orEmpty(inputs);
        cron = orEmpty(cron);
        interval = orEmpty(interval);
        scheduleEnabled = orEmpty(scheduleEnabled);
        backfillEnabled = orEmpty(backfillEnabled);
        webhookEnabled = orEmpty(webhookEnabled);
        webhookSecret = orEmpty(webhookSecret);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder taking plain (nullable) values — a {@code null} argument leaves that field
     * absent from the patch. Keeps call sites (DTO mapping, tests) readable without wrapping every
     * argument in {@link Optional}.
     */
    public static final class Builder {
        private Optional<String> name = Optional.empty();
        private Optional<SourceType> type = Optional.empty();
        private Optional<Map<String, Object>> auth = Optional.empty();
        private Optional<Map<String, Object>> inputs = Optional.empty();
        private Optional<String> cron = Optional.empty();
        private Optional<String> interval = Optional.empty();
        private Optional<Boolean> scheduleEnabled = Optional.empty();
        private Optional<Boolean> backfillEnabled = Optional.empty();
        private Optional<Boolean> webhookEnabled = Optional.empty();
        private Optional<String> webhookSecret = Optional.empty();

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

        public Builder backfillEnabled(Boolean v) {
            this.backfillEnabled = Optional.ofNullable(v);
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

        public KnowledgePatch build() {
            return new KnowledgePatch(name, type, auth, inputs, cron, interval,
                    scheduleEnabled, backfillEnabled, webhookEnabled, webhookSecret);
        }
    }
}
