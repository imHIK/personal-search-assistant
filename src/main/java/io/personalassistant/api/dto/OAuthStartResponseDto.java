package io.personalassistant.api.dto;

/**
 * Where to send the user's browser to gather consent. Returned as JSON rather than as a redirect so
 * the console owns the navigation — it may open a popup, or warn before leaving a half-filled form —
 * and so a failure to start is an ordinary error response rather than a bounce to nowhere.
 *
 * @param authorizeUrl the provider's consent URL, carrying a single-use state token
 */
public record OAuthStartResponseDto(String authorizeUrl) {
}
