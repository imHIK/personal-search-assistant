package io.personalassistant.api.dto;

import io.personalassistant.ingestion.connector.ats.JobBoardsConnector;
import java.util.List;

/**
 * Wire shape for a company lookup — what a candidate name resolves to, before anything is created.
 *
 * @param platform the hosting platform, or null when none of the supported platforms has a board.
 *                 A null here is a normal answer ("this company is not reachable this way"), not a
 *                 failure
 * @param postings how many postings that board holds, before any location filter is applied
 */
public record CompanyLookupDto(String company, String platform, String handle, int postings,
                               boolean found) {

    public static CompanyLookupDto from(JobBoardsConnector.CompanyLookup lookup) {
        return new CompanyLookupDto(lookup.company(), lookup.platform(), lookup.handle(),
                lookup.postings(), lookup.platform() != null);
    }

    /** Inbound payload: the candidate names to look up. */
    public record Request(List<String> companies) {}
}
