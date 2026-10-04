package io.personalassistant.ingestion.connector.ats.eightfold;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Eightfold serves two public APIs and a tenant answers only one: {@code apply/v2} (Netflix, Millennium) or
 * {@code pcsx} (PayPal, Qualcomm, Morgan Stanley). Neither listing carries the description.
 */
public interface EightfoldApi {

    /** Ten positions a page whatever is asked for; a PCSX tenant answers 200 without {@code count}. */
    JsonNode listPositions(EightfoldSite site, int start);

    JsonNode position(EightfoldSite site, String id);

    /**
     * Ten positions a page under {@code data}. {@code location} is matched server-side and case-insensitively;
     * blank lists everything. A v2 tenant answers 403 "PCSX is not enabled".
     */
    JsonNode search(EightfoldSite site, String location, int start);

    JsonNode positionDetails(EightfoldSite site, String id);
}
