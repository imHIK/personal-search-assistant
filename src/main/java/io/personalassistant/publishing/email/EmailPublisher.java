package io.personalassistant.publishing.email;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import io.personalassistant.ingestion.connector.google.GoogleConnectionTypes;
import io.personalassistant.publishing.PublishReceipt;
import io.personalassistant.publishing.Publisher;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.mailencoder.MailEncoder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Email, sent through the Gmail API from a {@code GMAIL_SEND} account.
 *
 * <p>The account is an OAuth connection holding only the {@code gmail.send} scope: it can send as its
 * owner and nothing else. That is why this is not SMTP — SMTP to Gmail needs an app password, which
 * skips two-step verification and opens the whole mailbox over IMAP to anyone holding it.
 *
 * <p>Target: {@code {to: ["a@x.com"] | "a@x.com", cc: [...], subjectPrefix: "[digest]"}}. There is no
 * {@code from}: Gmail sends as the authenticated account.
 */
@ApplicationScoped
public class EmailPublisher implements Publisher {

    /**
     * Deliberately loose. Real address validation is Gmail's and its refusal is reported; this only
     * catches the pasted-the-wrong-field mistakes before they become a parked channel.
     */
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s<>,]+@[^@\\s<>,]+\\.[^@\\s<>,]+$");

    /**
     * The hostname the MIME encoder writes into the Message-ID it generates. Cosmetic: Gmail replaces the
     * Message-ID with its own on send, so no id chosen here survives — which is also why a resent delivery
     * cannot be made to collapse into the first copy (L13).
     */
    static final String ENCODER_HOSTNAME = "personal-search-assistant";

    private final GmailSendApi api;
    private final GoogleAccessTokens tokens;
    private final EmailRenderer renderer;
    private final GmailSendConnectionKind account;

    @Inject
    public EmailPublisher(GmailSendApi api, GoogleAccessTokens tokens, EmailRenderer renderer,
                          GmailSendConnectionKind account) {
        this.api = api;
        this.tokens = tokens;
        this.renderer = renderer;
        this.account = account;
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public Optional<String> connectionType() {
        return Optional.of(GoogleConnectionTypes.GMAIL_SEND);
    }

    @Override
    public void validateTarget(Map<String, Object> target) {
        if (addresses(target, "to").isEmpty()) {
            throw new IllegalArgumentException("an email channel needs at least one \"to\" address");
        }
        addresses(target, "cc");
        Object prefix = target.get("subjectPrefix");
        if (prefix != null && !(prefix instanceof String)) {
            throw new IllegalArgumentException("\"subjectPrefix\" must be text");
        }
    }

    /** That the account still signs in and granted the send scope — the usual first-run failure. */
    @Override
    public void verify(Channel channel, Connection connection) {
        try {
            account.verify(connection);
        } catch (RuntimeException e) {
            throw GmailSendFailures.classify(e);
        }
    }

    @Override
    public PublishReceipt publish(Channel channel, Connection connection, PublishMessage message,
                                  String reference) {
        List<String> to = addresses(channel.target(), "to");
        List<String> cc = addresses(channel.target(), "cc");
        Object prefix = channel.target().get("subjectPrefix");
        RenderedEmail email = renderer.render(message, prefix instanceof String s ? s : null);
        String raw = raw(to, cc, email);
        try {
            JsonNode sent = api.send(tokens.authFor(connection), raw);
            return new PublishReceipt(sent.path("id").asText(null));
        } catch (RuntimeException e) {
            throw GmailSendFailures.classify(e);
        }
    }

    /**
     * The whole message as the Gmail API wants it: RFC 2822, multipart/alternative with a text and an
     * HTML part, base64url-encoded. Vert.x's encoder does the MIME work — header encoding of a non-ASCII
     * subject, line lengths, boundaries — which is exactly the part not worth hand-rolling.
     */
    // Package-private for tests.
    static String raw(List<String> to, List<String> cc, RenderedEmail email) {
        MailMessage mail = new MailMessage()
                .setTo(to)
                .setSubject(email.subject())
                .setText(email.text())
                .setHtml(email.html());
        if (!cc.isEmpty()) {
            mail.setCc(cc);
        }
        String rfc2822 = new MailEncoder(mail, ENCODER_HOSTNAME).encode();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rfc2822.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException if a value is present but is not an address or list of them
     */
    private static List<String> addresses(Map<String, Object> target, String key) {
        Object raw = target == null ? null : target.get(key);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        for (Object value : values) {
            String address = value == null ? "" : value.toString().trim();
            if (!ADDRESS.matcher(address).matches()) {
                throw new IllegalArgumentException("\"" + key + "\" contains an invalid address: \""
                        + address + "\"");
            }
            out.add(address);
        }
        return out;
    }
}
