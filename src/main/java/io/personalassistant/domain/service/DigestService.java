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

    void delete(String id);

    /**
     * Execute a digest now and record the run. Never throws for a search or task failure — the failure
     * is recorded on the run instead, so a digest that has been erroring is visible as such rather than
     * merely quiet.
     *
     * @throws java.util.NoSuchElementException if no such digest exists
     */
    DigestRun run(String id);

    List<DigestRun> runs(String digestId, int limit);

    Optional<DigestRun> latestRun(String digestId);
}
