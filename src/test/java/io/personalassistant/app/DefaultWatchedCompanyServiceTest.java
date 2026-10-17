package io.personalassistant.app;

import io.personalassistant.domain.model.CompanyTier;
import io.personalassistant.domain.model.WatchedCompany;
import io.personalassistant.domain.service.WatchedCompanyService.CompanyFields;
import io.personalassistant.domain.service.WatchedCompanyService.ImportResult;
import io.personalassistant.testsupport.InMemoryWatchedCompanyRepository;
import io.personalassistant.testsupport.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultWatchedCompanyServiceTest {

    private final InMemoryWatchedCompanyRepository repository = new InMemoryWatchedCompanyRepository();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T10:00:00Z"));
    private final DefaultWatchedCompanyService service = service();

    private DefaultWatchedCompanyService service() {
        DefaultWatchedCompanyService s = new DefaultWatchedCompanyService(repository);
        s.clock = clock;
        return s;
    }

    private static CompanyFields named(String name) {
        return new CompanyFields(name, null, null, null, null, null, null, null);
    }

    @Test
    void createsAnUncheckedCompany() {
        WatchedCompany c = service.create(new CompanyFields("  Zepto ", "zepto.com",
                "https://zepto.darwinbox.in/ms/candidatev2/main/careers/allJobs", "Darwinbox", null,
                "Cloudflare", " ", null));

        Assertions.assertTrue(c.id().startsWith("wco_"));
        Assertions.assertEquals("Zepto", c.name());
        Assertions.assertNull(c.note());
        Assertions.assertNull(c.lastCheckedAt());
        Assertions.assertEquals(CompanyTier.MEDIUM, c.tier());
    }

    @Test
    void refusesADuplicateNameIgnoringCaseAndSpacing() {
        service.create(named("Tata 1mg"));

        Assertions.assertThrows(IllegalStateException.class, () -> service.create(named("tata  1MG")));
    }

    @Test
    void refusesANonHttpCareersUrl() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(
                new CompanyFields("X", null, "javascript:alert(1)", null, null, null, null, null)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(named(" ")));
    }

    @Test
    void markCheckedStampsTheClockAndLeavesEditsAlone() {
        WatchedCompany c = service.create(named("Nutanix"));
        clock.advance(Duration.ofDays(3));

        WatchedCompany checked = service.markChecked(c.id());

        Assertions.assertEquals(clock.instant(), checked.lastCheckedAt());
        Assertions.assertEquals(c.updatedAt(), checked.updatedAt());
    }

    @Test
    void updateLeavesNullsAloneAndClearsBlanks() {
        WatchedCompany c = service.create(new CompanyFields("Juspay", "juspay.in", "https://juspay.io/careers",
                "own site", CompanyTier.HIGH, "JSON endpoint", "ping recruiter", null));
        service.markChecked(c.id());

        WatchedCompany updated = service.update(c.id(),
                new CompanyFields(null, null, null, null, null, null, "", null));

        Assertions.assertEquals("Juspay", updated.name());
        Assertions.assertEquals(CompanyTier.HIGH, updated.tier());
        Assertions.assertEquals("https://juspay.io/careers", updated.careersUrl());
        Assertions.assertNull(updated.note());
        Assertions.assertNotNull(updated.lastCheckedAt());
    }

    @Test
    void renamingOntoAnotherCompanyIsAConflict() {
        service.create(named("Bloomberg"));
        WatchedCompany other = service.create(named("Siemens"));

        Assertions.assertThrows(IllegalStateException.class,
                () -> service.update(other.id(), named("bloomberg")));
        Assertions.assertDoesNotThrow(() -> service.update(other.id(), named("SIEMENS")));
    }

    @Test
    void missingIdsAreNotFound() {
        Assertions.assertThrows(NoSuchElementException.class, () -> service.markChecked("wco_missing"));
        Assertions.assertThrows(NoSuchElementException.class, () -> service.delete("wco_missing"));
        Assertions.assertThrows(NoSuchElementException.class, () -> service.update("wco_missing", named("x")));
    }

    @Test
    void importAddsOnlyWhatIsNotWatchedAndKeepsExistingState() {
        WatchedCompany zepto = service.create(
                new CompanyFields("Zepto", null, null, null, CompanyTier.LOW, null, "mine", null));
        service.markChecked(zepto.id());

        ImportResult first = service.importBundled();
        ImportResult second = service.importBundled();

        Assertions.assertTrue(first.added().size() > 50);
        Assertions.assertEquals(List.of("Zepto"), first.skipped());
        Assertions.assertTrue(second.added().isEmpty());
        WatchedCompany kept = repository.findByName("zepto").orElseThrow();
        Assertions.assertEquals("mine", kept.note());
        Assertions.assertNotNull(kept.lastCheckedAt());
        Assertions.assertEquals(CompanyTier.LOW, kept.tier());
        Assertions.assertTrue(first.tiered().isEmpty());
    }

    @Test
    void importGivesAnUntieredCompanyItsBundledTierOnly() {
        WatchedCompany google = service.create(named("Google"));
        repository.store.put(google.id(), google.withTier(null));
        service.markChecked(google.id());

        ImportResult first = service.importBundled();
        ImportResult second = service.importBundled();

        Assertions.assertEquals(List.of("Google"), first.tiered());
        Assertions.assertTrue(second.tiered().isEmpty());
        WatchedCompany kept = repository.findByName("google").orElseThrow();
        Assertions.assertEquals(CompanyTier.HIGH, kept.tier());
        Assertions.assertNotNull(kept.lastCheckedAt());
    }

    @Test
    void bundledListHasUniqueNamesAndHttpsLinks() {
        List<CompanyFields> bundled = DefaultWatchedCompanyService.bundled();
        Set<String> keys = new HashSet<>();
        for (CompanyFields c : bundled) {
            Assertions.assertTrue(keys.add(WatchedCompany.nameKey(c.name())), "duplicate " + c.name());
            Assertions.assertNotNull(c.reason(), c.name() + " has no reason");
            Assertions.assertNotNull(c.tier(), c.name() + " has no tier");
            if (c.careersUrl() != null) {
                URI uri = URI.create(c.careersUrl());
                Assertions.assertEquals("https", uri.getScheme(), c.name());
                Assertions.assertNotNull(uri.getHost(), c.name());
            }
        }
    }
}
