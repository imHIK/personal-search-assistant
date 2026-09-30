package io.personalassistant.storage.repository;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DigestRepository {

    Digest save(Digest digest);

    Optional<Digest> findById(String id);

    List<Digest> findAll();

    List<Digest> findByChannelId(String channelId);

    /** Enabled, with nextRunAt passed or unset (due now). */
    List<Digest> findDue(Instant now, int limit);

    void delete(String id);

    DigestRun saveRun(DigestRun run);

    List<DigestRun> findRuns(String digestId, int limit);

    /** Newest first. */
    List<DigestRun> findRuns(String digestId, int limit, int offset);

    Optional<DigestRun> findLatestRun(String digestId);

    Optional<DigestRun> findRun(String runId);

    /** A repository query rather than a scan: the run history is unbounded. */
    Set<String> reportedEntityIds(String digestId);

    /** Counts only runs at or after {@code since}; null counts every run. */
    Set<String> reportedEntityIds(String digestId, Instant since);

    void deleteRuns(String digestId);
}
