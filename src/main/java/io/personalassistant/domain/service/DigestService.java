package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import java.util.List;
import java.util.Optional;

public interface DigestService {

    Digest create(Digest digest);

    List<Digest> list();

    Optional<Digest> get(String id);

    Digest setEnabled(String id, boolean enabled);

    /**
     * The run history survives an edit.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     * @throws IllegalArgumentException if the edit would leave it without a query
     */
    Digest update(String id, DigestPatch patch);

    /**
     * Keeps every recorded run.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     */
    Digest resetHistory(String id);

    void delete(String id);

    /**
     * Never throws for a search or task failure: it is recorded on the run.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     */
    DigestRun run(String id);

    List<DigestRun> runs(String digestId, int limit, int offset);

    Optional<DigestRun> latestRun(String digestId);

    Optional<DigestRun> run(String digestId, String runId);
}
