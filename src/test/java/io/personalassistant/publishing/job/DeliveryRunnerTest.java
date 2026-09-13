package io.personalassistant.publishing.job;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.publishing.ChannelConnectionResolver;
import io.personalassistant.publishing.PublishException;
import io.personalassistant.storage.repository.ChannelRepository;
import io.personalassistant.testsupport.InMemoryChannelRepository;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.InMemoryDeliveryRepository;
import io.personalassistant.testsupport.SinglePublisherRegistry;
import io.personalassistant.testsupport.StubPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The outbox worker: sending, retrying, dead-lettering, parking a broken channel, holding the queue for a
 * broken account, and the lease fence.
 */
class DeliveryRunnerTest {

    private static final String SEND_TYPE = "GMAIL_SEND";

    private final InMemoryChannelRepository channels = new InMemoryChannelRepository();
    private final InMemoryDeliveryRepository deliveries = new InMemoryDeliveryRepository();
    private final InMemoryConnectionRepository connections = new InMemoryConnectionRepository();
    private final StubPublisher publisher = new StubPublisher();
    private final DeliveryRunner runner = runner(channels);

    private DeliveryRunner runner(ChannelRepository channelRepository) {
        DeliveryRunner r = new DeliveryRunner(channelRepository, deliveries, new SinglePublisherRegistry(publisher),
                new ChannelConnectionResolver(connections));
        r.batch = 20;
        r.leaseSeconds = 120;
        r.retryLimit = 3;
        r.backoffSeconds = 60;
        r.maxBackoffSeconds = 3600;
        return r;
    }

    private Channel channel(String id, boolean enabled, ChannelStatus status) {
        return channel(id, null, enabled, status);
    }

    private Channel channel(String id, String connectionId, boolean enabled, ChannelStatus status) {
        Instant now = Instant.now();
        return channels.insert(new Channel(id, "Inbox", ChannelType.EMAIL, connectionId,
                Map.of("to", List.of("me@x.com")), enabled, status, null, now, now));
    }

    private Connection account(String id, String type, ConnectionStatus status) {
        Instant now = Instant.now();
        return connections.save(new Connection(id, "Sender", type, Map.of(), Map.of(), null, true, status, null,
                now, now));
    }

    private Delivery queued(String id, String channelId, int attempts, Instant createdAt) {
        return deliveries.insertIfAbsent(new Delivery(id, channelId, Delivery.Origin.manual(), null,
                new PublishMessage("Hello " + id, null, List.of(), null), DeliveryStatus.PENDING, attempts,
                null, null, null, null, createdAt, null));
    }

    @Test
    void sendsAPendingDelivery() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        Assertions.assertEquals(1, runner.runOnce());

        Delivery d = deliveries.store.get("dlv_1");
        Assertions.assertEquals(DeliveryStatus.SENT, d.status());
        Assertions.assertNull(d.lease());
        Assertions.assertNotNull(d.sentAt());
        Assertions.assertEquals("msg-dlv_1", d.providerMessageId());
        Assertions.assertEquals(List.of("dlv_1"), publisher.references,
                "the delivery id is the reference, so a resend is recognisable as the same message");
    }

    @Test
    void sendsOldestFirst() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        Instant now = Instant.now();
        queued("dlv_new", "chn_1", 0, now);
        queued("dlv_old", "chn_1", 0, now.minusSeconds(60));

        runner.runOnce();

        Assertions.assertEquals(List.of("dlv_old", "dlv_new"), publisher.references);
    }

    @Test
    void successResetsTheFailureStreak() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 2, Instant.now());

        runner.runOnce();

        Assertions.assertEquals(0, deliveries.store.get("dlv_1").attempts());
    }

    @Test
    void transientFailureIsRetriedAfterABackoff() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());
        publisher.failure = PublishException.transientFailure("timeout", null);

        Assertions.assertEquals(0, runner.runOnce());

        Delivery d = deliveries.store.get("dlv_1");
        Assertions.assertEquals(DeliveryStatus.PENDING, d.status());
        Assertions.assertEquals(1, d.attempts());
        Assertions.assertEquals("timeout", d.lastError());
        Assertions.assertTrue(d.nextAttemptAt().isAfter(Instant.now().plusSeconds(50)));
        Assertions.assertEquals(ChannelStatus.ACTIVE, channels.store.get("chn_1").status(),
                "a transient failure says nothing about the channel");

        runner.runOnce();
        Assertions.assertEquals(1, publisher.references.size(), "still backing off, so not claimed again");
    }

    @Test
    void anUnclassifiedExceptionIsTreatedAsTransient() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());
        publisher.failure = new IllegalStateException("boom");

        runner.runOnce();

        Assertions.assertEquals(DeliveryStatus.PENDING, deliveries.store.get("dlv_1").status());
        Assertions.assertEquals(ChannelStatus.ACTIVE, channels.store.get("chn_1").status());
    }

    @Test
    void deadLettersAtTheRetryLimit() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 2, Instant.now());
        publisher.failure = PublishException.transientFailure("timeout", null);

        runner.runOnce();

        Delivery d = deliveries.store.get("dlv_1");
        Assertions.assertEquals(DeliveryStatus.FAILED, d.status());
        Assertions.assertEquals(3, d.attempts());
        Assertions.assertNull(d.nextAttemptAt());
    }

    @Test
    void permanentFailureParksTheChannelAndSparesItsOtherDeliveries() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        Instant now = Instant.now();
        queued("dlv_1", "chn_1", 0, now.minusSeconds(10));
        queued("dlv_2", "chn_1", 0, now);
        publisher.failure = PublishException.permanent("Gmail refused the recipient", null);

        runner.runOnce();

        Channel c = channels.store.get("chn_1");
        Assertions.assertEquals(ChannelStatus.ERROR, c.status());
        Assertions.assertEquals("Gmail refused the recipient", c.lastError());
        Assertions.assertEquals(1, deliveries.store.get("dlv_1").attempts());
        Assertions.assertEquals(DeliveryStatus.PENDING, deliveries.store.get("dlv_1").status(),
                "the fault is the channel's, so the message waits for it to be fixed");
        Assertions.assertEquals(0, deliveries.store.get("dlv_2").attempts(),
                "a parked channel's other deliveries must not spend attempts on the same refusal");
        Assertions.assertEquals(List.of("dlv_1"), publisher.references);
    }

    @Test
    void deliveriesForAnUnusableChannelWait() {
        channel("chn_off", false, ChannelStatus.ACTIVE);
        channel("chn_err", true, ChannelStatus.ERROR);
        queued("dlv_1", "chn_off", 0, Instant.now());
        queued("dlv_2", "chn_err", 0, Instant.now());

        Assertions.assertEquals(0, runner.runOnce());

        Assertions.assertTrue(publisher.references.isEmpty());
        Assertions.assertEquals(0, deliveries.store.get("dlv_1").attempts());
        Assertions.assertEquals(0, deliveries.store.get("dlv_2").attempts());
    }

    @Test
    void aChannelPausedAfterTheClaimReleasesTheDeliveryWithoutAnAttempt() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());
        // findUsable is read at the start of the tick; pausing afterwards is what the re-read catches.
        InMemoryChannelRepository racing = new InMemoryChannelRepository() {
            @Override
            public List<Channel> findUsable() {
                List<Channel> usable = channels.findUsable();
                channels.updateEdits("chn_1", "Inbox", null, Map.of(), false, Instant.now());
                return usable;
            }

            @Override
            public Optional<Channel> findById(String id) {
                return channels.findById(id);
            }
        };

        Assertions.assertEquals(0, runner(racing).runOnce());

        Delivery d = deliveries.store.get("dlv_1");
        Assertions.assertNull(d.lease(), "released for when the channel is resumed");
        Assertions.assertEquals(0, d.attempts());
        Assertions.assertTrue(publisher.references.isEmpty());
    }

    @Test
    void aWorkerThatLostItsLeaseDoesNotMarkTheDeliverySent() {
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());
        publisher.duringPublish = () -> deliveries.steal("dlv_1", "publisher-other");

        runner.runOnce();

        Delivery d = deliveries.store.get("dlv_1");
        Assertions.assertEquals(DeliveryStatus.PENDING, d.status(),
                "the new owner decides the outcome; a stale markSent must be a no-op");
        Assertions.assertEquals("publisher-other", d.lease().owner());
    }

    @Test
    void backoffDoublesAndIsCapped() {
        Assertions.assertEquals(Duration.ofSeconds(60), runner.backoff(1));
        Assertions.assertEquals(Duration.ofSeconds(120), runner.backoff(2));
        Assertions.assertEquals(Duration.ofSeconds(240), runner.backoff(3));
        Assertions.assertEquals(Duration.ofSeconds(3600), runner.backoff(30));
    }

    // ---- sending through an account ----------------------------------------------------------------

    @Test
    void sendsThroughTheDefaultAccountOfThePublishersType() {
        publisher.connectionType = SEND_TYPE;
        account("conn_send", SEND_TYPE, ConnectionStatus.ACTIVE);
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        Assertions.assertEquals(1, runner.runOnce());

        Assertions.assertEquals("conn_send", publisher.connections.get(0).id());
    }

    @Test
    void anExplicitAccountWinsOverTheDefault() {
        publisher.connectionType = SEND_TYPE;
        account("conn_default", SEND_TYPE, ConnectionStatus.ACTIVE);
        account("conn_work", SEND_TYPE, ConnectionStatus.ACTIVE);
        channel("chn_1", "conn_work", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        runner.runOnce();

        Assertions.assertEquals("conn_work", publisher.connections.get(0).id());
    }

    @Test
    void aChannelWithNoAccountIsParkedWithoutSpendingAnAttempt() {
        publisher.connectionType = SEND_TYPE;
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        Assertions.assertEquals(0, runner.runOnce());

        Channel c = channels.store.get("chn_1");
        Assertions.assertEquals(ChannelStatus.ERROR, c.status());
        Assertions.assertTrue(c.lastError().contains(SEND_TYPE), c.lastError());
        Assertions.assertEquals(0, deliveries.store.get("dlv_1").attempts());
        Assertions.assertNull(deliveries.store.get("dlv_1").lease());
        Assertions.assertTrue(publisher.references.isEmpty());
    }

    @Test
    void anAccountOfTheWrongTypeParksTheChannel() {
        publisher.connectionType = SEND_TYPE;
        account("conn_read", "GMAIL", ConnectionStatus.ACTIVE);
        channel("chn_1", "conn_read", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        runner.runOnce();

        Assertions.assertEquals(ChannelStatus.ERROR, channels.store.get("chn_1").status());
        Assertions.assertTrue(publisher.references.isEmpty(), "a read-only account must never be used to send");
    }

    @Test
    void aBrokenAccountHoldsTheQueueAndReleasesItOnceReconnected() {
        publisher.connectionType = SEND_TYPE;
        account("conn_send", SEND_TYPE, ConnectionStatus.ERROR);
        channel("chn_1", true, ChannelStatus.ACTIVE);
        queued("dlv_1", "chn_1", 0, Instant.now());

        Assertions.assertEquals(0, runner.runOnce());
        Assertions.assertEquals(ChannelStatus.ACTIVE, channels.store.get("chn_1").status(),
                "the account is the broken thing; parking the channel would outlive the reconnect");
        Assertions.assertEquals(0, deliveries.store.get("dlv_1").attempts());
        Assertions.assertTrue(publisher.references.isEmpty());

        account("conn_send", SEND_TYPE, ConnectionStatus.ACTIVE); // the user reconnects

        Assertions.assertEquals(1, runner.runOnce());
        Assertions.assertEquals(DeliveryStatus.SENT, deliveries.store.get("dlv_1").status());
    }
}
