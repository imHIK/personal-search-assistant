package io.personalassistant.storage.search.opensearch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.http.HttpHost;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.opensearch.client.RestClient;

/**
 * Produces the OpenSearch low-level {@link RestClient} as an application-scoped bean from
 * {@code opensearch.*} config. The low-level client is used deliberately: its API is extremely
 * stable and lets the adapter speak the documented query DSL directly as JSON, keeping the
 * mapping to OpenSearch transparent and easy to evolve.
 */
@ApplicationScoped
public class OpenSearchClientProducer {

    @ConfigProperty(name = "opensearch.host", defaultValue = "localhost")
    String host;

    @ConfigProperty(name = "opensearch.port", defaultValue = "9200")
    int port;

    @ConfigProperty(name = "opensearch.scheme", defaultValue = "http")
    String scheme;

    @Produces
    @ApplicationScoped
    public RestClient restClient() {
        return RestClient.builder(new HttpHost(host, port, scheme)).build();
    }

    public void close(@Disposes RestClient client) {
        try {
            client.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close OpenSearch client", e);
        }
    }
}
