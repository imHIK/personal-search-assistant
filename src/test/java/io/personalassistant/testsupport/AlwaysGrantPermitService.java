package io.personalassistant.testsupport;

import io.personalassistant.common.concurrency.Permit;
import io.personalassistant.common.concurrency.PermitService;
import io.personalassistant.common.concurrency.ScopeLimit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A {@link PermitService} that always grants — for tests that aren't exercising concurrency limits. */
public class AlwaysGrantPermitService implements PermitService {

    @Override
    public Optional<Permit> tryAcquire(String scopeKey, int max, String owner, Duration ttl) {
        return Optional.of(new Permit(UUID.randomUUID().toString(), owner, List.of(scopeKey),
                ttl, Instant.now().plus(ttl)));
    }

    @Override
    public Optional<Permit> tryAcquire(List<ScopeLimit> limits, String owner, Duration ttl) {
        return Optional.of(new Permit(UUID.randomUUID().toString(), owner,
                limits.stream().map(ScopeLimit::key).toList(), ttl, Instant.now().plus(ttl)));
    }

    @Override
    public void renew(Permit permit) {
    }

    @Override
    public void release(Permit permit) {
    }
}
