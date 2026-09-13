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
 * Google as an OAuth provider, covering both connectors that authenticate through a Google account
 * and the send-only account the email publisher uses.
 * Almost all of it is inherited — what is actually Google-specific is the two endpoints, the
 * scopes, and the three authorize parameters below.
 *
 * <p><strong>Operational note that no code can substitute for:</strong> a refresh token issued by a
 * client whose consent screen is still in <em>Testing</em> publishing status is revoked by Google after
 * seven days, no matter what this application does — refresh tokens are neither rotated nor extended by
 * use. The consent screen must be published ("In production") for a connection to survive. Unverified
 * is fine for a personal deployment; the user clicks through one "Google hasn't verified this app"
 * interstitial. See {@code docs/oauth.md}.
 */
@ApplicationScoped
public class GoogleOAuthProvider extends AbstractOAuth2Provider {

    /** URL segment under {@code /api/connections/oauth/…}; part of the contract once shipped. */
    public static final String ID = "google";

    /** Send mail as the account, and nothing else — no reading, no deleting. */
    public static final String GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";

    private static final Map<String, Set<String>> SCOPES = new LinkedHashMap<>();

    static {
        SCOPES.put(GoogleConnectionTypes.GMAIL, Set.of("https://www.googleapis.com/auth/gmail.readonly"));
        SCOPES.put(GoogleConnectionTypes.GOOGLE_DRIVE, Set.of("https://www.googleapis.com/auth/drive.readonly"));
        // Its own connection type rather than a second scope on GMAIL: the account that is read for
        // ingestion never gains the right to send, and the sending account never gains the right to read.
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
     * {@code access_type=offline} is what asks for a refresh token at all, and {@code prompt=consent}
     * is what makes Google return one <em>again</em> on a re-consent — without it a user reconnecting a
     * broken account gets an access token and no refresh token, and is back where they started an hour
     * later. {@code include_granted_scopes} keeps a second connector's consent from silently dropping
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
