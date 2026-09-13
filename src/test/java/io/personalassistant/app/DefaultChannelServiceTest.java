package io.personalassistant.app;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.model.enums.ConnectionStatus;
import io.personalassistant.domain.service.ChannelService;
import io.personalassistant.publishing.ChannelConnectionResolver;
import io.personalassistant.publishing.PublishException;
import io.personalassistant.testsupport.InMemoryChannelRepository;
import io.personalassistant.testsupport.InMemoryConnectionRepository;
import io.personalassistant.testsupport.InMemoryDeliveryRepository;
import io.personalassistant.testsupport.InMemoryDigestRepository;
import io.personalassistant.testsupport.SinglePublisherRegistry;
import io.personalassistant.testsupport.StubPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Channel lifecycle: publisher-validated creation and edits, the account a channel sends through, the
 * synchronous test send, cascade delete.
 */
class DefaultChannelServiceTest {

    private static final String SEND_TYPE = "GMAIL_SEND";

    private final InMemoryChannelRepository channels = new InMemoryChannelRepository();
    private final InMemoryDeliveryRepository deliveries = new InMemoryDeliveryRepository();
    private final InMemoryConnectionRepository connections = new InMemoryConnectionRepository();
    private final StubPublisher publisher = new StubPublisher(ChannelType.EMAIL);
    private final InMemoryDigestRepository digests = new InMemoryDigestRepository();
    private final DefaultChannelService service = new DefaultChannelService(channels, deliveries, connections,
            digests, new SinglePublisherRegistry(publisher), new ChannelConnectionResolver(connections));

    private Channel create() {
        return create(null);
    }

    private Channel create(String connectionId) {
        return service.create(new ChannelService.NewChannel("Inbox", ChannelType.EMAIL, connectionId,
                Map.of("to", List.of("me@x.com")), null));
    }

    private Connection account(String id, String type, boolean isDefault) {
        Instant now = Instant.now();
        return connections.save(new Connection(id, "Sender", type, Map.of(), Map.of(), null, isDefault,
                ConnectionStatus.ACTIVE, null, now, now));
    }

    @Test
    void createsAnEnabledActiveChannel() {
        Channel c = create();

        Assertions.assertTrue(c.id().startsWith("chn_"));
        Assertions.assertTrue(c.enabled());
        Assertions.assertEquals(ChannelStatus.ACTIVE, c.status());
        Assertions.assertNull(c.connectionId());
        Assertions.assertEquals(c, channels.store.get(c.id()));
    }

    @Test
    void aTypeWithNoPublisherIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(
                new ChannelService.NewChannel("Alerts", ChannelType.SLACK, null, Map.of(), null)));
    }

    @Test
    void aTargetThePublisherRefusesIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(
                new ChannelService.NewChannel("Inbox", ChannelType.EMAIL, null, Map.of(StubPublisher.INVALID, 1),
                        null)));
        Assertions.assertTrue(channels.store.isEmpty());
    }

    @Test
    void aBlankNameIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.create(
                new ChannelService.NewChannel(" ", ChannelType.EMAIL, null, Map.of(), null)));
    }

    @Test
    void aNamedAccountMustExistAndBeOfThePublishersType() {
        publisher.connectionType = SEND_TYPE;
        account("conn_read", "GMAIL", false);
        account("conn_send", SEND_TYPE, false);

        Assertions.assertThrows(IllegalArgumentException.class, () -> create("conn_missing"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> create("conn_read"),
                "a read-only ingestion account must not be accepted as a sender");
        Assertions.assertEquals("conn_send", create("conn_send").connectionId());
    }

    @Test
    void aPublisherWithNoAccountRefusesAConnectionId() {
        account("conn_send", SEND_TYPE, false);

        Assertions.assertThrows(IllegalArgumentException.class, () -> create("conn_send"));
    }

    @Test
    void anEditedTargetIsRevalidatedAndAbsentFieldsAreKept() {
        Channel c = create();

        Assertions.assertThrows(IllegalArgumentException.class, () -> service.update(c.id(),
                new ChannelService.ChannelEdit(null, null, Map.of(StubPublisher.INVALID, 1), null)));

        Channel paused = service.update(c.id(), new ChannelService.ChannelEdit(null, null, null, false));
        Assertions.assertFalse(paused.enabled());
        Assertions.assertEquals("Inbox", paused.name());
        Assertions.assertEquals(c.target(), paused.target());
    }

    @Test
    void aBlankAccountOnEditSwitchesBackToTheDefault() {
        publisher.connectionType = SEND_TYPE;
        account("conn_send", SEND_TYPE, false);
        Channel pinned = create("conn_send");

        Channel kept = service.update(pinned.id(), new ChannelService.ChannelEdit("Renamed", null, null, null));
        Assertions.assertEquals("conn_send", kept.connectionId(), "null means unchanged");

        Channel unpinned = service.update(pinned.id(), new ChannelService.ChannelEdit(null, "", null, null));
        Assertions.assertNull(unpinned.connectionId());
    }

    @Test
    void anEditLeavesStatusAlone() {
        Channel c = create();
        channels.updateStatus(c.id(), ChannelStatus.ERROR, "refused", Instant.now());

        Channel renamed = service.update(c.id(), new ChannelService.ChannelEdit("Work inbox", null, null, null));

        Assertions.assertEquals(ChannelStatus.ERROR, renamed.status(),
                "only a successful test brings a parked channel back");
    }

    @Test
    void aSuccessfulTestSendsTheSampleThroughTheDefaultAccountAndRestoresTheChannel() {
        publisher.connectionType = SEND_TYPE;
        account("conn_send", SEND_TYPE, true);
        Channel c = create();
        channels.updateStatus(c.id(), ChannelStatus.ERROR, "refused", Instant.now());

        Channel tested = service.test(c.id());

        Assertions.assertEquals(ChannelStatus.ACTIVE, tested.status());
        Assertions.assertNull(tested.lastError());
        Assertions.assertEquals(List.of(DefaultChannelService.SAMPLE), publisher.sent);
        Assertions.assertEquals("conn_send", publisher.connections.get(0).id());
    }

    @Test
    void aTestWithNoAccountConnectedSaysSo() {
        publisher.connectionType = SEND_TYPE;
        Channel c = create();

        Channel tested = service.test(c.id());

        Assertions.assertEquals(ChannelStatus.ERROR, tested.status());
        Assertions.assertTrue(tested.lastError().contains(SEND_TYPE), tested.lastError());
        Assertions.assertTrue(publisher.references.isEmpty());
    }

    @Test
    void aFailedTestIsRecordedNotThrown() {
        Channel c = create();
        publisher.failure = PublishException.permanent("Gmail refused to send", null);

        Channel tested = service.test(c.id());

        Assertions.assertEquals(ChannelStatus.ERROR, tested.status());
        Assertions.assertEquals("Gmail refused to send", tested.lastError());
    }

    @Test
    void aFailedVerificationSkipsTheSend() {
        Channel c = create();
        publisher.verifyFailure = PublishException.permanent("has not granted permission to send", null);

        Channel tested = service.test(c.id());

        Assertions.assertEquals(ChannelStatus.ERROR, tested.status());
        Assertions.assertTrue(publisher.references.isEmpty());
    }

    @Test
    void deleteCascadesItsDeliveries() {
        Channel c = create();
        Channel other = create();
        PublishMessage m = new PublishMessage("Hi", null, List.of(), null);
        deliveries.insertIfAbsent(Delivery.pending("dlv_1", c.id(), null, null, m, Instant.now()));
        deliveries.insertIfAbsent(Delivery.pending("dlv_2", other.id(), null, null, m, Instant.now()));

        service.delete(c.id());

        Assertions.assertFalse(channels.store.containsKey(c.id()));
        Assertions.assertEquals(List.of("dlv_2"), List.copyOf(deliveries.store.keySet()));
        Assertions.assertThrows(NoSuchElementException.class, () -> service.delete(c.id()));
    }

    @Test
    void deleteIsRefusedWhileADigestSendsToIt() {
        Channel c = create();
        Instant now = Instant.now();
        digests.save(new Digest("dig_1", "New roles", "roles", null, List.of(), Map.of(), null, SyncSchedule.NONE,
                null, 10, false, 1, true, true, null, now, now, null, List.of(c.id())));

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () -> service.delete(c.id()));

        Assertions.assertTrue(e.getMessage().contains("New roles"), e.getMessage());
        Assertions.assertTrue(channels.store.containsKey(c.id()));
    }
}
