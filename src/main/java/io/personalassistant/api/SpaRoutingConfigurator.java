package io.personalassistant.api;

import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.logging.Logger;

/**
 * Serves index.html for the console's client-side routes: only a browser navigation (GET/HEAD accepting
 * text/html) outside API, management and asset paths. Anything else keeps its real status, so a mistyped API
 * call is still a 404.
 */
@ApplicationScoped
public class SpaRoutingConfigurator {

    private static final Logger LOG = Logger.getLogger(SpaRoutingConfigurator.class.getName());

    private static final String INDEX = "/index.html";

    private static final String[] SERVER_PREFIXES = {"/api/", "/q/", "/assets/"};

    void configure(@Observes Router router) {
        // Last, so it only sees requests that no static file or resource method matched.
        router.route().order(Integer.MAX_VALUE).handler(spaFallback());
        LOG.fine("SPA fallback route registered for the web console");
    }

    private Handler<RoutingContext> spaFallback() {
        return context -> {
            if (!isNavigation(context) || isServerPath(context.normalizedPath())) {
                context.next();
                return;
            }
            // Reroute, not redirect: the address bar keeps the deep link.
            context.reroute(INDEX);
        };
    }

    private static boolean isNavigation(RoutingContext context) {
        String method = context.request().method().name();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String accept = context.request().getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private static boolean isServerPath(String path) {
        if (path == null || path.equals(INDEX)) {
            return true;
        }
        for (String prefix : SERVER_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // A path with a file extension is an asset request; rewriting it would mask a missing file.
        int lastSlash = path.lastIndexOf('/');
        return path.indexOf('.', lastSlash + 1) > -1;
    }
}
