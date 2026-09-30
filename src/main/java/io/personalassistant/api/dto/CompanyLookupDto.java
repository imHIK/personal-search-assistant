package io.personalassistant.api.dto;

import io.personalassistant.ingestion.connector.ats.JobBoardsConnector;
import java.util.List;

/**
 * @param platform null when no supported platform hosts a board for it: an answer, not a failure
 * @param postings counted before any location filter
 */
public record CompanyLookupDto(String company, String platform, String handle, int postings,
                               boolean found) {

    public static CompanyLookupDto from(JobBoardsConnector.CompanyLookup lookup) {
        return new CompanyLookupDto(lookup.company(), lookup.platform(), lookup.handle(),
                lookup.postings(), lookup.platform() != null);
    }

    public record Request(List<String> companies) {}
}
