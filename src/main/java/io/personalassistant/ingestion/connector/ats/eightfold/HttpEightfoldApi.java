package io.personalassistant.ingestion.connector.ats.eightfold;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.common.ratelimit.RateLimit;
import io.personalassistant.common.ratelimit.RateLimitMode;
import io.personalassistant.common.ratelimit.RateLimitPolicies;
import io.personalassistant.common.ratelimit.RateLimitedException;
import io.personalassistant.ingestion.connector.ats.AtsHttp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpEightfoldApi implements EightfoldApi {

    /** Public boards have no account, and Eightfold throttles each tenant on its own: the host is the bucket. */
    private static final Logger LOG = Logger.getLogger(HttpEightfoldApi.class.getName());

    private static final String PLATFORM = "eightfold";

    @ConfigProperty(name = "app.ingestion.eightfold.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    private final AtsHttp http;
    private final RateLimitPolicies policies;

    /**
     * Cookie header per host, "" when the careers page set none. Microsoft 429s a share of calls without a
     * real {@code _vs} session (a made-up cookie fares no better); the other tenants ignore it.
     */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    @Inject
    public HttpEightfoldApi(AtsHttp http, RateLimitPolicies policies) {
        this.http = http;
        this.policies = policies;
    }

    @Override
    public JsonNode listPositions(EightfoldSite site, int start) {
        return get(site, "/api/apply/v2/jobs?domain=" + encode(site.domain()) + "&start=" + start + "&num=10");
    }

    @Override
    public JsonNode position(EightfoldSite site, String id) {
        return get(site, "/api/apply/v2/jobs/" + encode(id) + "?domain=" + encode(site.domain()));
    }

    @Override
    public JsonNode search(EightfoldSite site, String location, int start) {
        return get(site, "/api/pcsx/search?domain=" + encode(site.domain()) + "&query=&location="
                + encode(location == null ? "" : location) + "&start=" + start + "&sort_by=timestamp");
    }

    @Override
    public JsonNode positionDetails(EightfoldSite site, String id) {
        return get(site, "/api/pcsx/position_details?position_id=" + encode(id) + "&domain="
                + encode(site.domain()) + "&hl=en");
    }

    private JsonNode get(EightfoldSite site, String pathAndQuery) {
        String cookie = sessions.get(site.host());
        if (cookie == null) {
            cookie = session(site);
            sessions.put(site.host(), cookie);
        }
        Map<String, String> headers = cookie.isEmpty() ? Map.of() : Map.of("Cookie", cookie);
        try {
            return http.getJson("https://" + site.host() + pathAndQuery, headers, timeoutSeconds, rateLimit(site));
        } catch (RateLimitedException e) {
            sessions.remove(site.host());
            throw e;
        }
    }

    /** Best-effort: a careers page that fails costs the session, not the call. A 429 still defers. */
    private String session(EightfoldSite site) {
        String url = "https://" + site.host() + "/careers?domain=" + encode(site.domain());
        try {
            return cookieHeader(http.setCookies(url, timeoutSeconds, rateLimit(site)));
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No Eightfold session for " + site.host() + "; calling without one", e);
            return "";
        }
    }

    static String cookieHeader(List<String> setCookies) {
        return setCookies.stream()
                .map(header -> header.split(";", 2)[0].trim())
                .filter(pair -> pair.indexOf('=') > 0)
                .collect(Collectors.joining("; "));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private RateLimit rateLimit(EightfoldSite site) {
        return policies.forBoard(PLATFORM + ":" + site.host(), RateLimitMode.WAIT);
    }
}
