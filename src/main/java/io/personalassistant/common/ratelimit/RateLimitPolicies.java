package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ConfigText;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Account rules win, the operator default in application.properties fills in, and absent both a call is
 * unlimited. A WAIT call resolves {@code app.ratelimit.<area>.background.rules} and a FAIL_FAST one
 * {@code .rules}, both charged to the same key: with matching windows they share one counter, so background
 * work stops short and the rest is left for searches.
 */
@ApplicationScoped
public class RateLimitPolicies {

    private static final String CONNECTOR_PREFIX = "app.ratelimit.connector.";
    private static final String JOB_BOARDS_KEY = "app.ratelimit.job-boards.rules";
    private static final String LLM_KEY = "app.ratelimit.llm.rules";
    private static final String LLM_BACKGROUND_KEY = "app.ratelimit.llm.background.rules";
    private static final String EMBEDDING_KEY = "app.ratelimit.embedding.rules";
    private static final String EMBEDDING_BACKGROUND_KEY = "app.ratelimit.embedding.background.rules";

    private final Config config;

    /** Config policies only; account rules come fresh on each Connection. */
    private final ConcurrentHashMap<String, RateLimitPolicy> cache = new ConcurrentHashMap<>();

    @ConfigProperty(name = JOB_BOARDS_KEY)
    Optional<String> jobBoardRules;

    @ConfigProperty(name = LLM_KEY)
    Optional<String> llmRules;

    @ConfigProperty(name = LLM_BACKGROUND_KEY)
    Optional<String> llmBackgroundRules;

    @ConfigProperty(name = EMBEDDING_KEY)
    Optional<String> embeddingRules;

    @ConfigProperty(name = EMBEDDING_BACKGROUND_KEY)
    Optional<String> embeddingBackgroundRules;

    @Inject
    public RateLimitPolicies(Config config) {
        this.config = config;
    }

    public static RateLimitPolicies unlimited() {
        return new RateLimitPolicies(null);
    }

    /**
     * @param connectionId the bucket: two accounts on one host throttle apart
     * @param connectorType used only to find the operator default
     */
    public RateLimit forConnection(String connectionId, String connectorType,
                                   RateLimitPolicy configured, RateLimitMode mode) {
        RateLimitPolicy policy = configured != null && !configured.isUnlimited()
                ? configured
                : fromConfig(CONNECTOR_PREFIX + connectorType + ".rules");
        return new RateLimit(RateLimitKey.connection(connectionId), policy, mode);
    }

    public RateLimit forBoard(String platform, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.board(platform), parsed(JOB_BOARDS_KEY, jobBoardRules), mode);
    }

    public RateLimit forLlm(String providerId, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.llm(providerId),
                tiered(mode, LLM_KEY, llmRules, LLM_BACKGROUND_KEY, llmBackgroundRules), mode);
    }

    public RateLimit forEmbedding(String providerId, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.embedding(providerId), tiered(mode, EMBEDDING_KEY, embeddingRules,
                EMBEDDING_BACKGROUND_KEY, embeddingBackgroundRules), mode);
    }

    private RateLimitPolicy tiered(RateLimitMode mode, String sharedKey, Optional<String> shared,
                                   String backgroundKey, Optional<String> background) {
        if (mode == RateLimitMode.WAIT) {
            RateLimitPolicy policy = parsed(backgroundKey, background);
            if (!policy.isUnlimited()) {
                return policy;
            }
        }
        return parsed(sharedKey, shared);
    }

    private RateLimitPolicy parsed(String configKey, Optional<String> spec) {
        if (spec == null) {
            return RateLimitPolicy.UNLIMITED;
        }
        return cache.computeIfAbsent(configKey, k -> RateLimitRules.parse(ConfigText.orNull(spec)));
    }

    private RateLimitPolicy fromConfig(String configKey) {
        if (config == null) {
            return RateLimitPolicy.UNLIMITED;
        }
        return cache.computeIfAbsent(configKey, k -> RateLimitRules.parse(
                ConfigText.orNull(config.getOptionalValue(k, String.class))));
    }
}
