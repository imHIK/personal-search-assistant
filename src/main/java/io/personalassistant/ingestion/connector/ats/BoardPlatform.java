package io.personalassistant.ingestion.connector.ats;

import io.personalassistant.domain.model.RawItem;
import java.util.List;
import java.util.OptionalInt;

/**
 * One applicant-tracking platform that hosts company job boards.
 *
 * <p>The extension point behind {@code JobBoardsConnector}. A platform is <em>not</em> a
 * {@code SourceConnector}: which ATS a company happens to use is an implementation detail of fetching,
 * not something a user should have to know before they can watch that company. One connector routes
 * across all of them, and adding a platform is adding a bean here — discovered by CDI, registered
 * nowhere, exactly like connectors, parsers and chunking strategies.
 *
 * <p>Each implementation owns the mapping from its own JSON into the shared metadata schema, so that
 * every posting looks the same downstream regardless of where it came from. The shared derivations —
 * {@code dedupeKey}, seniority, remoteness, compensation — live in {@link AtsNormalization} and must be
 * computed identically by every platform, or the same role listed on two of them will not collapse.
 */
public interface BoardPlatform {

    /**
     * Stable identifier, recorded on each iterable and on every posting as {@code metadata.platform}.
     * Also the prefix a user may type to pin a company to one platform ({@code "lever:paytm"}).
     */
    String id();

    /**
     * How many postings this platform holds for {@code handle}, or empty when it hosts no such board.
     *
     * <p>Used once per company at discovery to work out which platform owns it. It returns a count
     * rather than a boolean because every platform's cheapest existence check already carries one —
     * a listing size, a {@code totalFound}, a {@code total} — so the number is free, and it is exactly
     * what someone deciding whether a company is worth watching wants to see.
     *
     * <p>Must return {@link OptionalInt#empty()} rather than throw for "no such board": resolution
     * probes every platform in turn, and a miss is the normal outcome for all but one. A genuine outage
     * is indistinguishable from a miss here, which is acceptable — the company fails to resolve and is
     * reported, rather than a transient error being cached as a permanent answer.
     */
    OptionalInt countPostings(String handle);

    /** Whether this platform hosts a board for {@code handle}. */
    default boolean hasBoard(String handle) {
        return countPostings(handle).isPresent();
    }

    /**
     * Every posting currently on one board, normalised.
     *
     * <p>A posting that cannot be mapped (missing id or title) is skipped rather than failing the page.
     *
     * @param locationHints the knowledge's location terms, already lowercased, or empty. A
     *     <strong>hint</strong>, not a contract: the connector applies the authoritative filter to
     *     whatever comes back, so a platform is free to ignore this entirely — and the three that
     *     return a whole board in one request do.
     *     <p>It exists for platforms that pay <em>per posting</em>. SmartRecruiters omits the
     *     description from its listing, so every posting needs its own second call; filtering on the
     *     listing metadata first turns Freshworks from 157 detail requests per poll into 34. Use
     *     {@link AtsNormalization#matchesLocation} so the early filter and the connector's agree.
     */
    List<RawItem> fetch(String handle, List<String> locationHints);
}
