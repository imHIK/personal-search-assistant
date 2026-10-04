package io.personalassistant.ingestion.connector;

import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;

/** The connection named on the knowledge, else the default connection for its type. */
public interface ConnectionResolver {

    /**
     * @throws java.util.NoSuchElementException if a named connection does not exist, or the type has no
     *                                          default
     */
    Connection resolve(Knowledge knowledge);
}
