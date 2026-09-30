package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.RawItem;
import java.util.List;
import java.util.OptionalInt;

/**
 * One ATS hosting company boards. Not a SourceConnector: which ATS a company uses is a fetching detail. Each
 * maps its JSON into the shared metadata schema, with derived values from AtsNormalization so every platform
 * computes them identically.
 */
public interface BoardPlatform {

    /**
     * Recorded as metadata.platform, and the prefix a user may type to pin a company ({@code "lever:paytm"}).
     */
    String id();

    /**
     * Empty, never a throw, for no such board: resolution probes every platform and a miss is the normal
     * outcome. An outage reads as a miss too, and the company fails to resolve.
     */
    OptionalInt countPostings(String handle);

    default boolean hasBoard(String handle) {
        return countPostings(handle).isPresent();
    }

    /**
     * A posting that cannot be mapped is skipped.
     *
     * @param filter a hint only: the connector applies it authoritatively, so a platform may ignore it. It
     *               pays off where each posting needs a detail call
     * @param company the user's name for it, or null; filed as metadata.company and built into the checksum
     */
    List<RawItem> fetch(String handle, String company, BoardFilter filter);

    default List<RawItem> fetch(String handle, BoardFilter filter) {
        return fetch(handle, null, filter);
    }
}
