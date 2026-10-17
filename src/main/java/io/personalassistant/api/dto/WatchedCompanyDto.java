package io.personalassistant.api.dto;

import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import io.personalassistant.domain.service.WatchedCompanyService;
import java.time.Instant;
import java.util.List;

/**
 * Inbound for create and for PATCH, where a null field is left unchanged and {@code ""} clears it.
 *
 * @param lastCheckedAt outbound only; set through {@code POST /{id}/checked}
 */
public record WatchedCompanyDto(
        String id,
        String name,
        String domain,
        String careersUrl,
        String platform,
        CompanyTier tier,
        String reason,
        String note,
        String lookupHandle,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static WatchedCompanyDto from(WatchedCompany c) {
        return new WatchedCompanyDto(c.id(), c.name(), c.domain(), c.careersUrl(), c.platform(), c.tier(),
                c.reason(), c.note(), c.lookupHandle(), c.lastCheckedAt(), c.createdAt(), c.updatedAt());
    }

    public WatchedCompanyService.CompanyFields toFields() {
        return new WatchedCompanyService.CompanyFields(name, domain, careersUrl, platform, tier, reason, note,
                lookupHandle);
    }

    public record ImportResult(int added, int skipped, int tiered, List<String> addedNames) {

        public static ImportResult from(WatchedCompanyService.ImportResult result) {
            return new ImportResult(result.added().size(), result.skipped().size(), result.tiered().size(),
                    result.added());
        }
    }
}
