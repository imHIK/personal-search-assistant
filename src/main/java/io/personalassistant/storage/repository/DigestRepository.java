package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Persistence port for the {@code digests} and {@code digestRuns} collections. */
public interface DigestRepository {

    Digest save(Digest digest);

    Optional<Digest> findById(String id);

    List<Digest> findAll();

    /**
     * Enabled digests whose {@code nextRunAt} has passed (or is unset, meaning "due now"). Drives the
     * scheduler tick.
     */
    List<Digest> findDue(Instant now, int limit);

    void delete(String id);

    // ---- runs --------------------------------------------------------------------------------

    DigestRun saveRun(DigestRun run);

    /** A digest's runs, newest first. */
    List<DigestRun> findRuns(String digestId, int limit);

    Optional<DigestRun> findLatestRun(String digestId);

    /**
     * Every entity id this digest has already reported, across all its recorded runs — the "already
     * seen" set that makes a digest show only what is new.
     *
     * <p>Deliberately a repository query rather than a scan in the service: the set is small (topK per
     * run) but the run history is not bounded, so paging every run's full document back to count ids
     * would grow linearly with the digest's age.
     */
    Set<String> reportedEntityIds(String digestId);

    /** Remove a digest's runs. Cascades from {@link #delete}. */
    void deleteRuns(String digestId);
}
