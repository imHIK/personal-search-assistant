package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.testsupport.StubInstance;
import io.personalassistant.testsupport.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JobBoardsConnectorTest {

    private static final class StubPlatform implements BoardPlatform {
        private final String id;
        private final Map<String, List<RawItem>> boards;
        int probes;
        BoardFilter lastFilter;
        String lastCompany;

        StubPlatform(String id, Map<String, List<RawItem>> boards) {
            this.id = id;
            this.boards = boards;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public java.util.OptionalInt countPostings(String handle) {
            probes++;
            List<RawItem> board = boards.get(handle);
            return board == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(board.size());
        }

        @Override
        public List<RawItem> fetch(String handle, String company, BoardFilter filter) {
            lastFilter = filter;
            lastCompany = company;
            return boards.getOrDefault(handle, List.of());
        }
    }

    private static RawItem posting(String id, String location) {
        Map<String, Object> metadata = location == null
                ? Map.of("title", id)
                : Map.of("title", id, "location", location);
        return new RawItem(id, EntityType.JOB_POSTING, "text/html", id, "uri://" + id,
                "sum:" + id, Instant.now(), Map.of(), "body", null, metadata, null, false);
    }

    private static RawItem posting(String id, String location, boolean remote, Instant postedAt) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("title", id);
        metadata.put("location", location);
        metadata.put("remote", remote);
        metadata.put("postedAt", postedAt);
        return new RawItem(id, EntityType.JOB_POSTING, "text/html", id, "uri://" + id,
                "sum:" + id, postedAt, Map.of(), "body", null, metadata, null, false);
    }

    private static final List<RawItem> FILTERABLE = List.of(
            posting("engineer-blr", "Bengaluru, India", false, Instant.now()),
            posting("engineer-remote", "London, UK", true, Instant.now()),
            posting("manager-blr", "Bengaluru, India", false, Instant.now()),
            posting("engineer-stale", "Bengaluru, India", false,
                    Instant.now().minus(java.time.Duration.ofDays(60))));

    private static final List<RawItem> BOARD = List.of(
            posting("bengaluru", "Bengaluru, India"),
            posting("city-only", "Bengaluru"),
            posting("newyork", "New York, NY"),
            posting("remote-us", "Remote - US"),
            posting("unstated", null));

    private final StubPlatform greenhouse =
            new StubPlatform("greenhouse", Map.of("acme", BOARD, "filterable", FILTERABLE));
    private final StubPlatform lever = new StubPlatform("lever", Map.of("globex", BOARD));

    private JobBoardsConnector connector() {
        return new JobBoardsConnector(new StubInstance<>(List.of(greenhouse, lever)));
    }

    private static Knowledge knowledge(Object companies, Object locations) {
        Map<String, Object> inputs = locations == null
                ? Map.of(JobBoardsConnector.COMPANIES_INPUT, companies)
                : Map.of(JobBoardsConnector.COMPANIES_INPUT, companies,
                        JobBoardsConnector.LOCATIONS_INPUT, locations);
        return TestData.knowledge("kn_1", SourceType.JOB_BOARDS, Instant.now(), inputs);
    }

    private List<String> grab(String company, Object locations) {
        return grab(company, knowledge(List.of(company), locations), connector());
    }

    private List<String> grabWith(String company, Map<String, Object> extraInputs) {
        Map<String, Object> inputs = new java.util.LinkedHashMap<>(extraInputs);
        inputs.put(JobBoardsConnector.COMPANIES_INPUT, List.of(company));
        return grab(company, TestData.knowledge("kn_1", SourceType.JOB_BOARDS, Instant.now(), inputs),
                connector());
    }

    private List<String> grab(String company, Knowledge kn, JobBoardsConnector connector) {
        SourceIterable iterable = connector.discover(kn).stream()
                .filter(i -> i.iterableId().equals(company)).findFirst().orElseThrow();
        return connector.grab(new GrabContext(kn, company, iterable.attributes(),
                        CursorPosition.start(), TimeWindow.atOrAfter(Instant.EPOCH), 100))
                .items().stream().map(RawItem::externalId).toList();
    }

    @Test
    void resolvesEachCompanyToThePlatformHostingIt() {
        List<SourceIterable> iterables = connector().discover(knowledge(List.of("acme", "globex"), null));

        Assertions.assertEquals(List.of("acme", "globex"),
                iterables.stream().map(SourceIterable::iterableId).toList());
        Assertions.assertEquals("greenhouse", iterables.get(0).attributes().get("platform"));
        Assertions.assertEquals("lever", iterables.get(1).attributes().get("platform"));
    }

    @Test
    void grabUsesTheResolvedPlatformFromAttributesWithoutReProbing() {
        JobBoardsConnector connector = connector();
        Knowledge kn = knowledge(List.of("globex"), null);
        SourceIterable iterable = connector.discover(kn).get(0);
        int probesAfterDiscover = greenhouse.probes + lever.probes;

        connector.grab(new GrabContext(kn, "globex", iterable.attributes(),
                CursorPosition.start(), TimeWindow.atOrAfter(Instant.EPOCH), 100));

        Assertions.assertEquals(probesAfterDiscover, greenhouse.probes + lever.probes);
    }

    @Test
    void anExplicitPlatformPrefixPinsTheChoice() {
        List<SourceIterable> iterables = connector().discover(knowledge(List.of("lever:globex"), null));

        Assertions.assertEquals("lever", iterables.get(0).attributes().get("platform"));
        Assertions.assertEquals("globex", iterables.get(0).attributes().get("handle"));
    }

    @Test
    void anUnresolvableCompanyIsSkippedRatherThanFailingDiscovery() {
        List<SourceIterable> iterables =
                connector().discover(knowledge(List.of("acme", "nowhere"), null));

        Assertions.assertEquals(List.of("acme"),
                iterables.stream().map(SourceIterable::iterableId).toList());
    }

    @Test
    void verifyRejectsAKnowledgeWithNoCompanies() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> connector().verify(knowledge(List.of(), null)));
    }

    @Test
    void verifyRejectsAKnowledgeWhereNothingResolves() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> connector().verify(knowledge(List.of("nowhere", "alsonowhere"), null)));
    }

    @Test
    void verifyAcceptsAPartialMiss() {
        Assertions.assertDoesNotThrow(
                () -> connector().verify(knowledge(List.of("acme", "nowhere"), null)));
    }

    @Test
    void isForwardOnlyBecauseAJobBoardHasNoHistoryWorthWalking() {
        Assertions.assertEquals(EnumSet.of(CursorDirection.FORWARD),
                connector().supportedDirections());
    }

    @Test
    void optsIntoARetentionWindowLongerThanItsPollInterval() {
        JobBoardsConnector connector = connector();
        Duration retention = connector.defaultRetention().orElseThrow();

        Assertions.assertTrue(retention.compareTo(connector.defaultSchedule().interval()) > 0);
    }

    @Test
    void keepsOnlyPostingsMatchingATerm() {
        Assertions.assertEquals(List.of("bengaluru", "unstated"), grab("acme", List.of("India")));
    }

    @Test
    void theCountryAloneMissesCityOnlyLocations() {
        Assertions.assertFalse(grab("acme", List.of("India")).contains("city-only"));
        Assertions.assertTrue(grab("acme", List.of("India", "Bengaluru")).contains("city-only"));
    }

    @Test
    void remoteIsABluntTermThatAlsoMatchesOtherCountries() {
        Assertions.assertTrue(grab("acme", List.of("Remote")).contains("remote-us"));
    }

    @Test
    void aPostingWithNoLocationSurvivesTheFilter() {
        Assertions.assertTrue(grab("acme", List.of("India")).contains("unstated"));
        Assertions.assertTrue(grab("acme", List.of("nowhere-at-all")).contains("unstated"));
    }

    @Test
    void noLocationsInputKeepsEverything() {
        Assertions.assertEquals(List.of("bengaluru", "city-only", "newyork", "remote-us", "unstated"),
                grab("acme", null));
        Assertions.assertEquals(5, grab("acme", List.of()).size());
    }

    @Test
    void acceptsASingleStringAsWellAsAList() {
        Assertions.assertEquals(List.of("bengaluru", "unstated"), grab("acme", "India"));
    }

    @Test
    void aTitleIncludeListNarrowsTheBoard() {
        Assertions.assertEquals(List.of("engineer-blr", "engineer-remote", "engineer-stale"),
                grabWith("filterable", Map.of(JobBoardsConnector.TITLE_INCLUDE_INPUT, List.of("engineer"))));
    }

    @Test
    void aTitleExcludeListWinsOverTheIncludeList() {
        Assertions.assertEquals(List.of("engineer-blr", "engineer-stale"),
                grabWith("filterable", Map.of(
                        JobBoardsConnector.TITLE_INCLUDE_INPUT, List.of("engineer"),
                        JobBoardsConnector.TITLE_EXCLUDE_INPUT, List.of("remote"))));
    }

    @Test
    void includeRemoteAdmitsARemoteRoleFiledOutsideTheNamedPlaces() {
        Assertions.assertEquals(List.of("engineer-blr", "manager-blr", "engineer-stale"),
                grabWith("filterable", Map.of(JobBoardsConnector.LOCATIONS_INPUT, List.of("bengaluru"))),
                "the London remote role is out");
        Assertions.assertEquals(
                List.of("engineer-blr", "engineer-remote", "manager-blr", "engineer-stale"),
                grabWith("filterable", Map.of(
                        JobBoardsConnector.LOCATIONS_INPUT, List.of("bengaluru"),
                        JobBoardsConnector.INCLUDE_REMOTE_INPUT, true)),
                "and back in once remote counts as a place");
    }

    @Test
    void anAgeLimitDropsStalePostings() {
        Assertions.assertEquals(List.of("engineer-blr", "engineer-remote", "manager-blr"),
                grabWith("filterable", Map.of(JobBoardsConnector.MAX_AGE_DAYS_INPUT, 14)));
    }

    @Test
    void anUnparseableAgeLimitMeansNoLimitRatherThanAnEmptyBoard() {
        Assertions.assertEquals(4, grabWith("filterable",
                Map.of(JobBoardsConnector.MAX_AGE_DAYS_INPUT, "not-a-number")).size());
    }

    @Test
    void theFilterIsHandedToThePlatformAsAHint() {
        grabWith("filterable", Map.of(JobBoardsConnector.TITLE_INCLUDE_INPUT, List.of("Engineer")));

        Assertions.assertEquals(List.of("engineer"), greenhouse.lastFilter.titleInclude());
    }

    @Test
    void theCompanyLabelIsHandedToThePlatformUnderItsEntry() {
        grabWith("lever:globex", Map.of(JobBoardsConnector.COMPANY_LABELS_INPUT,
                Map.of("lever:globex", " Globex Corporation ", "globex", "wrong key")));

        Assertions.assertEquals("Globex Corporation", lever.lastCompany);
    }

    @Test
    void anEntryWithNoLabelHandsDownNull() {
        grabWith("acme", Map.of(JobBoardsConnector.COMPANY_LABELS_INPUT, Map.of("globex", "Globex")));

        Assertions.assertNull(greenhouse.lastCompany);
    }

    @Test
    void signatureChangesWhenLocationsChange() {
        JobBoardsConnector connector = connector();

        Assertions.assertNotEquals(
                connector.membershipSignature(Map.of(JobBoardsConnector.LOCATIONS_INPUT, List.of("India"))),
                connector.membershipSignature(
                        Map.of(JobBoardsConnector.LOCATIONS_INPUT, List.of("India", "Singapore"))));
    }

    @Test
    void signatureIgnoresTheCompanyList() {
        JobBoardsConnector connector = connector();

        Assertions.assertEquals(
                connector.membershipSignature(Map.of(
                        JobBoardsConnector.COMPANIES_INPUT, List.of("acme"),
                        JobBoardsConnector.LOCATIONS_INPUT, List.of("India"))),
                connector.membershipSignature(Map.of(
                        JobBoardsConnector.COMPANIES_INPUT, List.of("acme", "globex"),
                        JobBoardsConnector.LOCATIONS_INPUT, List.of("India"))));
    }

    @Test
    void signatureIsStableRegardlessOfCaseOrSurroundingSpace() {
        JobBoardsConnector connector = connector();

        Assertions.assertEquals(
                connector.membershipSignature(Map.of(JobBoardsConnector.LOCATIONS_INPUT, List.of("India"))),
                connector.membershipSignature(
                        Map.of(JobBoardsConnector.LOCATIONS_INPUT, List.of("  india  "))));
    }

    @Test
    void lookupReportsThePlatformAndPostingCount() {
        List<JobBoardsConnector.CompanyLookup> found = connector().lookup(List.of("acme", "globex"));

        Assertions.assertEquals("greenhouse", found.get(0).platform());
        Assertions.assertEquals(BOARD.size(), found.get(0).postings());
        Assertions.assertEquals("lever", found.get(1).platform());
    }

    @Test
    void lookupReportsAMissRatherThanOmittingIt() {
        List<JobBoardsConnector.CompanyLookup> found = connector().lookup(List.of("nowhere"));

        Assertions.assertEquals(1, found.size());
        Assertions.assertEquals("nowhere", found.get(0).company());
        Assertions.assertNull(found.get(0).platform());
        Assertions.assertEquals(0, found.get(0).postings());
    }

    @Test
    void lookupHonoursAnExplicitPlatformPrefix() {
        Assertions.assertEquals("lever", connector().lookup(List.of("lever:globex")).get(0).platform());
    }

    @Test
    void lookupSkipsBlankNames() {
        Assertions.assertEquals(1, connector().lookup(List.of("acme", "", "   ")).size());
    }
}
