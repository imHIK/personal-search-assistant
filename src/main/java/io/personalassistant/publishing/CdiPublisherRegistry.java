package io.personalassistant.publishing;

import io.personalassistant.domain.model.enums.ChannelType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CdiPublisherRegistry implements PublisherRegistry {

    private final Map<ChannelType, Publisher> byType = new EnumMap<>(ChannelType.class);

    @Inject
    public CdiPublisherRegistry(Instance<Publisher> publishers) {
        for (Publisher p : publishers) {
            byType.put(p.type(), p);
        }
    }

    @Override
    public Publisher get(ChannelType type) {
        Publisher p = type == null ? null : byType.get(type);
        if (p == null) {
            throw new IllegalArgumentException("No publisher registered for channel type " + type);
        }
        return p;
    }

    @Override
    public boolean supports(ChannelType type) {
        return type != null && byType.containsKey(type);
    }

    @Override
    public Set<ChannelType> supported() {
        return Set.copyOf(byType.keySet());
    }
}
