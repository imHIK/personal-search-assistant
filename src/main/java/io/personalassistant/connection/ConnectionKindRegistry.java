package io.personalassistant.connection;

import java.util.Set;

/** Resolves the {@link ConnectionKind} for a connection type. */
public interface ConnectionKindRegistry {

    /** @throws IllegalArgumentException if nothing uses connections of {@code type} */
    ConnectionKind get(String type);

    boolean supports(String type);

    /** Every connection type something is installed to use. */
    Set<String> types();
}
