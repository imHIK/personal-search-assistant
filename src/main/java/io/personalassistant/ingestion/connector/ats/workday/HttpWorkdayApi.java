package io.personalassistant.ingestion.connector.ats.workday;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** HTTP adapter for {@link WorkdayApi} against the public career-site API (no auth). */
@ApplicationScoped
public class HttpWorkdayApi implements WorkdayApi {

    @ConfigProperty(name = "app.ingestion.workday.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Override
    public JsonNode searchJobs(WorkdaySite site, int limit, int offset) {
        // The paging window travels in the body; there is no query-string form of this call.
        String body = "{\"appliedFacets\":{},\"limit\":" + limit
                + ",\"offset\":" + offset + ",\"searchText\":\"\"}";
        return new AtsHttp(timeoutSeconds).postJson(site.apiRoot() + "/jobs", body);
    }

    @Override
    public JsonNode posting(WorkdaySite site, String externalPath) {
        String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
        return new AtsHttp(timeoutSeconds).getJson(site.apiRoot() + path);
    }
}
