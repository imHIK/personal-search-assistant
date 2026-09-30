package io.personalassistant.common.ratelimit;

import io.personalassistant.common.ConfigText;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves which ceilings apply to a call, and packages them with a key and a mode into the
 * {@link RateLimit} the transport wants.
 *
 * <p>Two tiers, in the shape {@code docs/configuration.md} prescribes: what the user configured on the
 * account wins, and an operator-set default in {@code application.properties} fills in behind it.
 * Absent both, the call is unlimited — which is what every existing deployment gets on upgrade, so
 * adding this changes no behaviour until somebody sets a limit.
 *
 * <p>The per-connector default is looked up dynamically as
 * {@code app.ratelimit.connector.<TYPE>.rules} rather than being a fixed field per source. That keeps
 * a new connector's default a config edit instead of a code change — the same reasoning
 * {@code LlmProfiles} applies to profiles — and avoids the core branching on {@code SourceType}.
 *
 * <p><strong>Background calls get a lower ceiling on the same bucket.</strong> The LLM and embedding
 * endpoints are one provider quota serving two kinds of caller: indexing and digests, which will happily
 * spend all of it, and a user's search, which then has nothing left. So a {@link RateLimitMode#WAIT} call
 * resolves {@code app.ratelimit.<area>.background.rules} while a {@link RateLimitMode#FAIL_FAST} call
 * keeps {@code app.ratelimit.<area>.rules}, and both are charged to the <em>same key</em>. A window is
 * counted per key and window length, so the two share one counter and differ only in where they stop:
 * with {@code 100/1d} and {@code 120/1d}, background work halts at 100 and the last 20 are reserved for
 * searches. The windows must match ({@code 1d} with {@code 1d}) to share a counter; an unmatched one is
 * simply an extra ceiling of its own.
 *
 * <p>Separate keys with split budgets were the alternative, and are worse on both sides: a reserve that
 * nobody searches into is wasted, and two independent counters can together exceed the provider's real
 * ceiling. The mode is the discriminator because it already <em>is</em> the background/interactive
 * split — see {@link RateLimitMode}. An unset background rule inherits the shared one, which is exactly
 * the behaviour before it existed.
 *
 * <p>Parsed policies are cached because the rule syntax is parsed per call otherwise; the values are
 * immutable and configuration does not change at runtime. Account-level rules are deliberately
 * <em>not</em> cached: they arrive on the {@code Connection} the caller already loaded, so an edit
 * takes effect on the next grab with nothing to invalidate.
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

    /** Config-derived policies only; see the class Javadoc on why account rules are not cached. */
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

    /**
     * A resolver with no configuration behind it, so every call is unlimited unless the caller passes an
     * account policy of its own. For hand-wired unit tests, which have no MicroProfile {@link Config}.
     */
    public static RateLimitPolicies unlimited() {
        return new RateLimitPolicies(null);
    }

    /**
     * The quota for one account's calls.
     *
     * @param connectionId  the account, which is the bucket — two accounts on one host throttle apart
     * @param connectorType {@code SourceType} name, used only to find the operator default
     * @param configured    what the user set on the account, or null/unlimited to fall back
     * @param mode          wait or fail, decided by the call path rather than the account
     */
    public RateLimit forConnection(String connectionId, String connectorType,
                                   RateLimitPolicy configured, RateLimitMode mode) {
        RateLimitPolicy policy = configured != null && !configured.isUnlimited()
                ? configured
                : fromConfig(CONNECTOR_PREFIX + connectorType + ".rules");
        return new RateLimit(RateLimitKey.connection(connectionId), policy, mode);
    }

    /** The quota for one public job board. No credential exists, so the platform is the bucket. */
    public RateLimit forBoard(String platform, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.board(platform), parsed(JOB_BOARDS_KEY, jobBoardRules), mode);
    }

    /** One LLM provider's quota; a background call stops short of it. See the class Javadoc. */
    public RateLimit forLlm(String providerId, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.llm(providerId),
                tiered(mode, LLM_KEY, llmRules, LLM_BACKGROUND_KEY, llmBackgroundRules), mode);
    }

    /** One embedding provider's quota; indexing stops short of it. See the class Javadoc. */
    public RateLimit forEmbedding(String providerId, RateLimitMode mode) {
        return new RateLimit(RateLimitKey.embedding(providerId), tiered(mode, EMBEDDING_KEY, embeddingRules,
                EMBEDDING_BACKGROUND_KEY, embeddingBackgroundRules), mode);
    }

    /** The background ceiling for a waiting call when one is set, else the shared one. */
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

    /** An injected property, parsed once and cached under its own config key. */
    private RateLimitPolicy parsed(String configKey, Optional<String> spec) {
        if (spec == null) {
            return RateLimitPolicy.UNLIMITED; // see unlimited()
        }
        return cache.computeIfAbsent(configKey, k -> RateLimitRules.parse(ConfigText.orNull(spec)));
    }

    /** A dynamically-named property, looked up and parsed once. Absent resolves to unlimited. */
    private RateLimitPolicy fromConfig(String configKey) {
        if (config == null) {
            return RateLimitPolicy.UNLIMITED; // see unlimited()
        }
        return cache.computeIfAbsent(configKey, k -> RateLimitRules.parse(
                ConfigText.orNull(config.getOptionalValue(k, String.class))));
    }
}
