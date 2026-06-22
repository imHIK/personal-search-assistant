package io.personalassistant.api.dto;

import java.util.Map;

/** Inbound payload to register or update a source. */
public record SourceDto(
        String id,
        String type,                 // SourceType name
        String name,
        Map<String, Object> config) {}
