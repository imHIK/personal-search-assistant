package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleApiException;
import io.personalassistant.ingestion.connector.google.GoogleAuth;
import io.personalassistant.ingestion.connector.google.GoogleConnectionTypes;
import io.personalassistant.ingestion.connector.google.GoogleOAuthProvider;
import io.personalassistant.ingestion.connector.oauth.CredentialsRejectedException;
import io.personalassistant.publishing.PublishException;
import io.personalassistant.publishing.PublishReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The EMAIL publisher against a fake Gmail API: target rules, the RFC 2822 message it sends, the send-scope
 * check, and how Gmail's failures are classified.
 */
class EmailPublisherTest {

    /** Records sends and scripts tokeninfo and failures. */
    private static final class FakeGmail implements GmailSendApi {
        final List<String> raws = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
        String grantedScopes = GoogleOAuthProvider.GMAIL_SEND_SCOPE;
        RuntimeException failure;

        @Override
        public JsonNode send(GoogleAuth auth, String rawBase64Url) {
            if (failure != null) {
                throw failure;
            }
            bearers.add(auth.bearer());
            raws.add(rawBase64Url);
            return JsonNodeFactory.instance.objectNode().put("id", "gm_" + raws.size());
        }

        @Override
        public JsonNode tokenInfo(String accessToken) {
            return JsonNodeFactory.instance.objectNode().put("scope", grantedScopes);
        }
    }

    private final FakeGmail gmail = new FakeGmail();
    private final GoogleAccessTokens tokens = connection -> GoogleAuth.unlimited("token-" + connection.id());
    private final EmailPublisher publisher = new EmailPublisher(gmail, tokens, new EmailRenderer(),
            new GmailSendConnectionKind(tokens, gmail));

    private static final Connection ACCOUNT = new Connection("conn_send", "Sender", GoogleConnectionTypes.GMAIL_SEND,
            Map.of("refreshToken", "r"), Map.of(), null, true, ConnectionStatus.ACTIVE, null, Instant.now(),
            Instant.now());

    private static final PublishMessage HELLO = new PublishMessage("Hello", "Body", List.of(), null);

    private static Channel channel(Map<String, Object> target) {
        return new Channel("chn_1", "Inbox", ChannelType.EMAIL, null, target, true, ChannelStatus.ACTIVE, null,
                Instant.now(), Instant.now());
    }

    private static String decode(String raw) {
        return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    @Test
    void sendsThroughASendOnlyGmailAccount() {
        Assertions.assertEquals(GoogleConnectionTypes.GMAIL_SEND, publisher.connectionType().orElseThrow());
        Assertions.assertEquals(GoogleConnectionTypes.GMAIL_SEND, new GmailSendConnectionKind(tokens, gmail).id());
    }

    @Test
    void aTargetNeedsAValidToAddress() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> publisher.validateTarget(Map.of()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> publisher.validateTarget(Map.of("to", List.of("not-an-address"))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> publisher.validateTarget(Map.of("to", "me@x.com", "cc", List.of("nope"))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> publisher.validateTarget(Map.of("to", "me@x.com", "subjectPrefix", 3)));

        publisher.validateTarget(Map.of("to", "me@x.com"));
        publisher.validateTarget(Map.of("to", List.of("me@x.com", "you@y.org"), "cc", List.of("c@z.io")));
    }

    @Test
    void publishSendsAMultipartMessageAsTheChannelsAccount() {
        PublishReceipt receipt = publisher.publish(channel(Map.of("to", List.of("me@x.com", "you@y.org"),
                "cc", List.of("c@z.io"), "subjectPrefix", "[digest]")), ACCOUNT, HELLO, "dlv_42");

        Assertions.assertEquals(List.of("token-conn_send"), gmail.bearers, "sent as the channel's account");
        Assertions.assertEquals("gm_1", receipt.providerMessageId());
        Assertions.assertFalse(gmail.raws.get(0).contains("="), "base64url without padding");

        String message = decode(gmail.raws.get(0));
        Assertions.assertTrue(message.contains("me@x.com") && message.contains("you@y.org"), message);
        Assertions.assertTrue(message.contains("Cc: c@z.io"), message);
        Assertions.assertTrue(message.contains("Subject: [digest] Hello"), message);
        Assertions.assertTrue(message.contains("multipart/alternative"), message);
        Assertions.assertFalse(message.contains("From:"), "Gmail sends as the authenticated account");
    }

    @Test
    void verifyPassesWhenTheSendScopeWasGranted() {
        gmail.grantedScopes = "openid " + GoogleOAuthProvider.GMAIL_SEND_SCOPE;

        publisher.verify(channel(Map.of("to", "me@x.com")), ACCOUNT);
    }

    @Test
    void verifyFailsPermanentlyWhenTheSendScopeIsMissing() {
        gmail.grantedScopes = "https://www.googleapis.com/auth/gmail.readonly";

        PublishException e = Assertions.assertThrows(PublishException.class,
                () -> publisher.verify(channel(Map.of("to", "me@x.com")), ACCOUNT));

        Assertions.assertTrue(e.permanent());
        Assertions.assertTrue(e.getMessage().contains("permission to send"), e.getMessage());
    }

    @Test
    void aBadRequestIsPermanent() {
        gmail.failure = new GoogleApiException(400, "Google API 400: Invalid To header");

        Assertions.assertTrue(send().permanent());
    }

    @Test
    void aMissingPermissionIsPermanentButARateLimitIsNot() {
        gmail.failure = new GoogleApiException(403, "Google API 403: insufficientPermissions");
        Assertions.assertTrue(send().permanent());

        gmail.failure = new GoogleApiException(403, "Google API 403: userRateLimitExceeded");
        Assertions.assertFalse(send().permanent());
    }

    @Test
    void serverAndTransportFailuresAreTransient() {
        gmail.failure = new GoogleApiException(503, "Google API 503");
        Assertions.assertFalse(send().permanent());

        gmail.failure = new GoogleApiException("Google API request failed", new java.net.ConnectException());
        Assertions.assertFalse(send().permanent());
    }

    @Test
    void aRejectedSignInIsTransientSoTheAccountGateHandlesIt() {
        gmail.failure = new CredentialsRejectedException("invalid_grant");

        PublishException e = send();

        Assertions.assertFalse(e.permanent(),
                "the connection is already ERROR; parking the channel too would outlive the reconnect");
        Assertions.assertTrue(e.getMessage().contains("reconnect"), e.getMessage());
    }

    private PublishException send() {
        return Assertions.assertThrows(PublishException.class,
                () -> publisher.publish(channel(Map.of("to", "me@x.com")), ACCOUNT, HELLO, "dlv_1"));
    }
}
