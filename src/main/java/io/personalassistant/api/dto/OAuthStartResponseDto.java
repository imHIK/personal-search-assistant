package io.personalassistant.api.dto;

/** JSON rather than a redirect, so the console owns the navigation. */
public record OAuthStartResponseDto(String authorizeUrl) {
}
