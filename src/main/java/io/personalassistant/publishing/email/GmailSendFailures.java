package io.personalassistant.publishing.email;

import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.ingestion.connector.google.GoogleApiException;
import io.personalassistant.ingestion.connector.oauth.CredentialsRejectedException;
import io.personalassistant.ingestion.connector.oauth.OAuthTransportException;
import io.personalassistant.publishing.PublishException;
import java.util.Locale;

/**
 * A rejected sign-in counts as transient: OAuthTokenService has already marked the connection ERROR, and the
 * delivery worker holds the channel's queue until a reconnect releases it. Parking the channel as well would
 * leave it in ERROR after the reconnect.
 */
final class GmailSendFailures {

    private GmailSendFailures() {
    }

    static PublishException classify(Throwable failure) {
        if (failure instanceof PublishException already) {
            return already;
        }
        if (failure instanceof CredentialsRejectedException) {
            return PublishException.transientFailure("The Google sign-in for sending was rejected — reconnect "
                    + "the account on the Accounts page", failure);
        }
        if (failure instanceof OAuthTransportException || failure instanceof RateLimitedException) {
            return PublishException.transientFailure(message(failure), failure);
        }
        if (failure instanceof GoogleApiException google) {
            return classify(google);
        }
        if (failure instanceof IllegalArgumentException) {
            // No usable tokens, or the send scope was never granted.
            return PublishException.permanent(message(failure), failure);
        }
        return PublishException.transientFailure(message(failure), failure);
    }

    private static PublishException classify(GoogleApiException e) {
        int status = e.statusCode();
        String message = message(e);
        String lower = message.toLowerCase(Locale.ROOT);
        if (status == 403 && (lower.contains("ratelimitexceeded") || lower.contains("quota"))) {
            // Gmail reports per-user rate limits as 403, not 429.
            return PublishException.transientFailure(message, e);
        }
        if (status == 403) {
            return PublishException.permanent("Gmail refused to send (" + message + ") — reconnect the sending "
                    + "account and approve sending", e);
        }
        if (status == 400 || status == 404) {
            // A malformed message or an address Gmail refuses: identical on every retry.
            return PublishException.permanent(message, e);
        }
        // 401 (a token revoked mid-flight; the next attempt refreshes), 429, 5xx, transport.
        return PublishException.transientFailure(message, e);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
