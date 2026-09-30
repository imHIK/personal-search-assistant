package io.personalassistant.connection;

import java.util.Set;

public interface ConnectionKindRegistry {

    /** @throws IllegalArgumentException if nothing uses connections of {@code type} */
    ConnectionKind get(String type);

    boolean supports(String type);

    Set<String> types();
}
