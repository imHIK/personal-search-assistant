package io.personalassistant.publishing;

/**
 * What a transport said when it accepted a message.
 *
 * @param providerMessageId the platform's id for the sent message (an SMTP Message-ID, a Slack
 *                          {@code ts}), or null when it gives none
 */
public record PublishReceipt(String providerMessageId) {
}
