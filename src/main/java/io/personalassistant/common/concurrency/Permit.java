package io.personalassistant.common.concurrency;

import java.time.Instant;
import java.util.List;

/**
 * A granted unit of concurrency. A permit occupies one slot in <em>each</em> of its
 * {@link #scopeKeys()} simultaneously (composite, all-or-nothing acquisition) and carries a
 * TTL ({@link #expiresAt()}) so a crashed worker's permit is auto-reclaimed when it lapses.
 *
 * @param id        unique permit id
 * @param owner     logical holder (worker id), for diagnostics
 * @param scopeKeys every scope this permit counts against (e.g. {@code global},
 *                  {@code connector:SLACK}, {@code knowledge:kn_123})
 * @param expiresAt lease expiry; reclaimed automatically once passed
 */
public record Permit(String id, String owner, List<String> scopeKeys, Instant expiresAt) {
}
