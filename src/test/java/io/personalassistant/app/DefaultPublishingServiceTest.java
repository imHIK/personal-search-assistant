package io.personalassistant.app;

import io.personalassistant.domain.model.Channel;
import io.personalassistant.domain.model.Delivery;
import io.personalassistant.domain.model.PublishMessage;
import io.personalassistant.domain.model.enums.ChannelStatus;
import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.domain.model.enums.DeliveryStatus;
import io.personalassistant.testsupport.InMemoryChannelRepository;
import io.personalassistant.testsupport.InMemoryDeliveryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The outbox's front door: what it accepts, idempotent enqueueing, and the way back from dead-letter. */
class DefaultPublishingServiceTest {

    private final InMemoryChannelRepository channels = new InMemoryChannelRepository();
    private final InMemoryDeliveryRepository deliveries = new InMemoryDeliveryRepository();
    private final DefaultPublishingService service = new DefaultPublishingService(channels, deliveries);

    private static final PublishMessage HELLO = new PublishMessage("Hello", null, List.of(), null);

    @BeforeEach
    void channel() {
        Instant now = Instant.now();
        channels.insert(new Channel("chn_1", "Inbox", ChannelType.EMAIL, null, Map.of(), true,
                ChannelStatus.ACTIVE, null, now, now));
    }

    @Test
    void enqueueWritesAPendingDelivery() {
        Delivery d = service.enqueue("chn_1", HELLO, null, null);

        Assertions.assertTrue(d.id().startsWith("dlv_"));
        Assertions.assertEquals(DeliveryStatus.PENDING, d.status());
        Assertions.assertEquals(Delivery.Origin.MANUAL, d.origin().kind());
        Assertions.assertEquals(d, deliveries.store.get(d.id()));
    }

    @Test
    void enqueueToAnUnknownChannelIs404() {
        Assertions.assertThrows(NoSuchElementException.class,
                () -> service.enqueue("chn_missing", HELLO, null, null));
    }

    @Test
    void anEmptyMessageIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.enqueue("chn_1", new PublishMessage(" ", null, List.of(), null), null, null));
    }

    @Test
    void aDisabledChannelStillAcceptsMessages() {
        channels.updateEdits("chn_1", "Inbox", null, Map.of(), false, Instant.now());

        Assertions.assertEquals(DeliveryStatus.PENDING, service.enqueue("chn_1", HELLO, null, null).status());
    }

    @Test
    void theSameDedupeKeyReturnsTheFirstDelivery() {
        Delivery first = service.enqueue("chn_1", HELLO, null, "digestRun:run_1:chn_1");
        Delivery second = service.enqueue("chn_1", HELLO, null, "digestRun:run_1:chn_1");

        Assertions.assertEquals(first.id(), second.id());
        Assertions.assertEquals(1, deliveries.store.size());
    }

    @Test
    void sendsWithoutADedupeKeyNeverCollapse() {
        service.enqueue("chn_1", HELLO, null, null);
        service.enqueue("chn_1", HELLO, null, null);

        Assertions.assertEquals(2, deliveries.store.size());
    }

    @Test
    void retryRevivesOnlyAFailedDelivery() {
        Delivery d = service.enqueue("chn_1", HELLO, null, null);
        Assertions.assertThrows(IllegalStateException.class, () -> service.retry(d.id()));

        deliveries.store.put(d.id(), new Delivery(d.id(), d.channelId(), d.origin(), null, d.message(),
                DeliveryStatus.FAILED, 5, null, null, "timeout", null, d.createdAt(), null));
        Delivery revived = service.retry(d.id());

        Assertions.assertEquals(DeliveryStatus.PENDING, revived.status());
        Assertions.assertEquals(0, revived.attempts(), "a retry starts a fresh streak (invariant 9)");
    }

    @Test
    void retryOfAnUnknownDeliveryIs404() {
        Assertions.assertThrows(NoSuchElementException.class, () -> service.retry("dlv_missing"));
    }
}
