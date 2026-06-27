package io.personalassistant.common.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A granted unit of concurrency. A permit occupies one slot in <em>each</em> of its
 * {@link #scopeKeys()} simultaneously (composite, all-or-nothing acquisition) and carries a
 * TTL ({@link #expiresAt()}) so a crashed worker's permit is auto-reclaimed when it lapses.
 *
 * <p>The TTL is chosen by the caller at acquisition and carried on the permit ({@link #ttl()}) so
 * {@link PermitService#renew} extends by the same amount. Each stage sizes its own TTL to the work
 * it guards — the permit must outlive a single guarded unit of work, since it is only renewed
 * between units (ingestion: per page; indexing: per tick).
 *
 * @param id        unique permit id
 * @param owner     logical holder (worker id), for diagnostics
 * @param scopeKeys every scope this permit counts against (e.g. {@code global},
 *                  {@code connector:SLACK}, {@code knowledge:kn_123})
 * @param ttl       the lease duration; {@link PermitService#renew} extends the expiry by this
 * @param expiresAt lease expiry; reclaimed automatically once passed
 */
public record Permit(String id, String owner, List<String> scopeKeys, Duration ttl, Instant expiresAt) {
}
