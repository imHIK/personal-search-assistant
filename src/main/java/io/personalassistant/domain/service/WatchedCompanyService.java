package io.personalassistant.domain.service;

import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import java.util.List;
import java.util.Optional;

public interface WatchedCompanyService {

    /**
     * @throws IllegalArgumentException if the name is blank or the careers URL is not http(s)
     * @throws IllegalStateException if a company with that name exists
     */
    WatchedCompany create(CompanyFields fields);

    Optional<WatchedCompany> get(String id);

    /** By name. */
    List<WatchedCompany> list();

    /**
     * @throws java.util.NoSuchElementException if no company with {@code id} exists
     * @throws IllegalArgumentException if the result is invalid
     * @throws IllegalStateException if the new name belongs to another company
     */
    WatchedCompany update(String id, CompanyFields edit);

    /**
     * Stamps now.
     *
     * @throws java.util.NoSuchElementException if no company with {@code id} exists
     */
    WatchedCompany markChecked(String id);

    /** @throws java.util.NoSuchElementException if no company with {@code id} exists */
    void delete(String id);

    /**
     * Adds every company in the bundled list whose name is not already watched, and gives an existing one
     * its bundled tier only if it has none. Nothing else on an existing one is touched, so it is safe to
     * repeat; a company you deleted comes back.
     */
    ImportResult importBundled();

    /**
     * On create a null field is unset (a null tier is {@code MEDIUM}); on update a null field is left
     * unchanged and {@code ""} clears it (except the name and tier, which cannot be cleared).
     */
    record CompanyFields(String name, String domain, String careersUrl, String platform, CompanyTier tier,
                         String reason, String note, String lookupHandle) {
    }

    /** @param tiered existing companies, among {@code skipped}, that got their bundled tier */
    record ImportResult(List<String> added, List<String> skipped, List<String> tiered) {
    }
}
