package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Edits and the check stamp are separate field-level writes, so marking checked never reverts an edit. */
public interface WatchedCompanyRepository {

    /** @throws IllegalStateException if a company with the same {@link WatchedCompany#nameKey} exists */
    WatchedCompany insert(WatchedCompany company);

    Optional<WatchedCompany> findById(String id);

    Optional<WatchedCompany> findByName(String name);

    /** By name. */
    List<WatchedCompany> findAll();

    /**
     * @return false if no such company
     * @throws IllegalStateException if the new name belongs to another company
     */
    boolean updateEdits(String id, String name, String domain, String careersUrl, String platform,
                        CompanyTier tier, String reason, String note, String lookupHandle, Instant at);

    /** Sets the tier only where none is stored, so a tier the user chose is never overwritten. */
    boolean fillTier(String id, CompanyTier tier);

    /** @return false if no such company */
    boolean updateLastCheckedAt(String id, Instant checkedAt);

    void delete(String id);
}
