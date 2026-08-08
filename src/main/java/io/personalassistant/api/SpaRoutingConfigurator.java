package io.personalassistant.api;

import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.logging.Logger;

/**
 * Serves {@code index.html} for the web console's client-side routes.
 *
 * <p>The console is a single-page app: React Router owns paths like {@code /knowledge/kn_123}, but
 * nothing on the server does. Without this, opening such a URL directly — or simply refreshing the
 * page — 404s, because Quarkus' static-resource handler only knows about files that exist on disk.
 *
 * <p>The rule is deliberately narrow: only {@code GET}/{@code HEAD} requests that the browser is
 * navigating with (an {@code Accept} that includes {@code text/html}) and that are not API,
 * management or asset paths get rewritten. Anything else must keep its real status, or a mistyped
 * API call would silently return an HTML page instead of a 404 and be far harder to debug.
 */
@ApplicationScoped
public class SpaRoutingConfigurator {

    private static final Logger LOG = Logger.getLogger(SpaRoutingConfigurator.class.getName());

    private static final String INDEX = "/index.html";

    /** Prefixes the server owns; never rewritten. {@code /q} covers health and other management endpoints. */
    private static final String[] SERVER_PREFIXES = {"/api/", "/q/", "/assets/"};

    void configure(@Observes Router router) {
        // Registered last so it only ever sees requests no static resource or resource method
        // matched. Vert.x runs same-order routes in registration order, and the platform's static
        // handler is installed during startup before this observer fires.
        router.route().order(Integer.MAX_VALUE).handler(spaFallback());
        LOG.fine("SPA fallback route registered for the web console");
    }

    private Handler<RoutingContext> spaFallback() {
        return context -> {
            if (!isNavigation(context) || isServerPath(context.normalizedPath())) {
                context.next();
                return;
            }
            // reroute rather than redirect: the address bar keeps the deep link, and the router
            // in the browser picks it up from there.
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
        // A path with a file extension is a real asset request (favicon.ico, a .woff2, a source
        // map). Rewriting those to index.html would mask genuinely missing files.
        int lastSlash = path.lastIndexOf('/');
        return path.indexOf('.', lastSlash + 1) > -1;
    }
}
