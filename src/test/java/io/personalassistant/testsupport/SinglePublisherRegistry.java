package io.personalassistant.testsupport;

import io.personalassistant.domain.model.enums.ChannelType;
import io.personalassistant.publishing.Publisher;
import io.personalassistant.publishing.PublisherRegistry;
import java.util.Set;

/** A {@link PublisherRegistry} holding exactly one publisher. */
public class SinglePublisherRegistry implements PublisherRegistry {

    private final Publisher publisher;

    public SinglePublisherRegistry(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public Publisher get(ChannelType type) {
        if (!supports(type)) {
            throw new IllegalArgumentException("No publisher registered for channel type " + type);
        }
        return publisher;
    }

    @Override
    public boolean supports(ChannelType type) {
        return publisher.type() == type;
    }

    @Override
    public Set<ChannelType> supported() {
        return Set.of(publisher.type());
    }
}
