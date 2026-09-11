package io.personalassistant.domain.service;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import java.util.List;
import java.util.Optional;

/** Use-case port for scheduled saved searches. */
public interface DigestService {

    Digest create(Digest digest);

    List<Digest> list();

    Optional<Digest> get(String id);

    /** Enable or disable scheduling. A disabled digest can still be run by hand. */
    Digest setEnabled(String id, boolean enabled);

    /**
     * Apply a partial edit. The run history — and so what the digest has already reported — survives
     * untouched; see {@link #resetHistory} for clearing that deliberately.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     * @throws IllegalArgumentException        if the edit would leave it with neither a query nor a
     *                                         source document
     */
    Digest update(String id, DigestPatch patch);

    /**
     * Forget what has already been reported, keeping every recorded run. The next run may repeat
     * things the user has already seen — which is the point, after widening a query.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     */
    Digest resetHistory(String id);

    void delete(String id);

    /**
     * Execute a digest now and record the run. Never throws for a search or task failure — the failure
     * is recorded on the run instead, so a digest that has been erroring is visible as such rather than
     * merely quiet.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     */
    DigestRun run(String id);

    /** A page of the history, newest first. */
    List<DigestRun> runs(String digestId, int limit, int offset);

    Optional<DigestRun> latestRun(String digestId);

    /** One run of this digest by id, so a link to a run resolves without paging the history. */
    Optional<DigestRun> run(String digestId, String runId);
}
