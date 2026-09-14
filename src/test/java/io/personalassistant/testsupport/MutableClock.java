package io.personalassistant.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves by hand. Rate-limit behaviour is entirely a function of elapsed time, so
 * driving it with a real clock would mean either sleeping (slow and flaky) or asserting nothing precise.
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
