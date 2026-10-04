package io.personalassistant.ingestion.connector.google;

import io.personalassistant.common.http.OutboundHttp;
import io.personalassistant.ingestion.connector.oauth.AbstractOAuth2Provider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A refresh token issued while the consent screen is still in Testing is revoked by Google after seven days,
 * whatever this app does: the consent screen must be published. See docs/oauth.md.
 */
@ApplicationScoped
public class GoogleOAuthProvider extends AbstractOAuth2Provider {

    /** Part of the URL contract once shipped. */
    public static final String ID = "google";

    public static final String GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";

    private static final Map<String, Set<String>> SCOPES = new LinkedHashMap<>();

    static {
        SCOPES.put(GoogleConnectionTypes.GMAIL, Set.of("https://www.googleapis.com/auth/gmail.readonly"));
        SCOPES.put(GoogleConnectionTypes.GOOGLE_DRIVE, Set.of("https://www.googleapis.com/auth/drive.readonly"));
        SCOPES.put(GoogleConnectionTypes.GMAIL_SEND, Set.of(GMAIL_SEND_SCOPE));
    }

    @ConfigProperty(name = "app.oauth.google.authorize-url",
            defaultValue = "https://accounts.google.com/o/oauth2/v2/auth")
    String authorizeUrl;

    @ConfigProperty(name = "app.oauth.google.token-url",
            defaultValue = "https://oauth2.googleapis.com/token")
    String tokenUrl;

    @Inject
    public GoogleOAuthProvider(OutboundHttp http) {
        super(http);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> supports() {
        return Set.copyOf(SCOPES.keySet());
    }

    @Override
    public Set<String> scopesFor(String type) {
        Set<String> scopes = SCOPES.get(type);
        if (scopes == null) {
            throw new IllegalArgumentException("Google does not authenticate " + type);
        }
        return scopes;
    }

    @Override
    protected String authorizeEndpoint() {
        return authorizeUrl;
    }

    @Override
    protected String tokenEndpoint() {
        return tokenUrl;
    }

    /**
     * {@code access_type=offline} asks for a refresh token; {@code prompt=consent} makes Google return one
     * again on a re-consent; {@code include_granted_scopes} keeps a second connector's consent from dropping
     * the first one's grant.
     */
    @Override
    protected Map<String, String> extraAuthorizeParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "true");
        return params;
    }
}
