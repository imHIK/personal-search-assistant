package io.personalassistant.testsupport;

import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import io.personalassistant.storage.repository.WatchedCompanyRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryWatchedCompanyRepository implements WatchedCompanyRepository {

    public final Map<String, WatchedCompany> store = new LinkedHashMap<>();

    @Override
    public WatchedCompany insert(WatchedCompany company) {
        requireUnique(company.id(), company.name());
        store.put(company.id(), company);
        return company;
    }

    @Override
    public Optional<WatchedCompany> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<WatchedCompany> findByName(String name) {
        String key = WatchedCompany.nameKey(name);
        return store.values().stream().filter(c -> WatchedCompany.nameKey(c.name()).equals(key)).findFirst();
    }

    @Override
    public List<WatchedCompany> findAll() {
        return store.values().stream().sorted(Comparator.comparing(c -> WatchedCompany.nameKey(c.name()))).toList();
    }

    @Override
    public boolean updateEdits(String id, String name, String domain, String careersUrl, String platform,
                               CompanyTier tier, String reason, String note, String lookupHandle, Instant at) {
        requireUnique(id, name);
        return store.computeIfPresent(id, (k, c) ->
                c.withEdits(name, domain, careersUrl, platform, tier, reason, note, lookupHandle, at)) != null;
    }

    @Override
    public boolean fillTier(String id, CompanyTier tier) {
        WatchedCompany current = store.get(id);
        if (current == null || current.tier() != null) {
            return false;
        }
        store.put(id, current.withTier(tier));
        return true;
    }

    @Override
    public boolean updateLastCheckedAt(String id, Instant checkedAt) {
        return store.computeIfPresent(id, (k, c) -> c.withLastCheckedAt(checkedAt)) != null;
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    private void requireUnique(String id, String name) {
        findByName(name).filter(other -> !other.id().equals(id)).ifPresent(other -> {
            throw new IllegalStateException("A watched company named " + name + " already exists");
        });
    }
}
