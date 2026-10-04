package io.personalassistant.testsupport;

import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.oauth.AuthorizeRequest;
import io.personalassistant.ingestion.connector.oauth.OAuthClient;
import io.personalassistant.ingestion.connector.oauth.OAuthProvider;
import io.personalassistant.ingestion.connector.oauth.OAuthTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class StubOAuthProvider implements OAuthProvider {

    public final List<AuthorizeRequest> authorizeCalls = new ArrayList<>();
    public final List<String> exchangedCodes = new ArrayList<>();
    public final List<String> refreshedWith = new ArrayList<>();

    public Supplier<OAuthTokens> onExchange = () -> new OAuthTokens("access-1", "refresh-1", far());
    public Supplier<OAuthTokens> onRefresh = () -> new OAuthTokens("access-2", null, far());

    private final String id;
    private final Set<String> supported;

    public StubOAuthProvider(String id, SourceType... supported) {
        this.id = id;
        this.supported = java.util.Arrays.stream(supported).map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> supports() {
        return supported;
    }

    @Override
    public Set<String> scopesFor(String type) {
        if (!supported.contains(type)) {
            throw new IllegalArgumentException(id + " does not authenticate " + type);
        }
        return Set.of("scope:" + type.toLowerCase());
    }

    @Override
    public String authorizeUrl(AuthorizeRequest request) {
        authorizeCalls.add(request);
        return "https://stub.test/consent?state=" + request.state()
                + "&redirect_uri=" + request.redirectUri();
    }

    @Override
    public OAuthTokens exchangeCode(String code, String redirectUri, OAuthClient client) {
        exchangedCodes.add(code);
        return onExchange.get();
    }

    @Override
    public OAuthTokens refresh(String refreshToken, OAuthClient client, RateLimit limit) {
        refreshedWith.add(refreshToken);
        return onRefresh.get();
    }

    /** Beyond the token service's 60-second refresh skew. */
    public static long far() {
        return java.time.Instant.now().getEpochSecond() + 3600;
    }
}
