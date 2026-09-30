package io.personalassistant.testsupport;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

public class StubConfig implements Config {

    private final Map<String, String> values;

    public StubConfig(Map<String, String> values) {
        this.values = values;
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        return this.<T>getOptionalValue(propertyName, propertyType)
                .orElseThrow(() -> new NoSuchElementException(propertyName));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        if (propertyType != String.class) {
            throw new UnsupportedOperationException("StubConfig only converts to String");
        }
        return Optional.ofNullable(values.get(propertyName)).map(value -> (T) value);
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return values.keySet();
    }

    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return List.of();
    }

    @Override
    public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
        return Optional.empty();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        throw new UnsupportedOperationException();
    }
}
