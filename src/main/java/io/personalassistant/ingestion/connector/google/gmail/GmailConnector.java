package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabContext;
import io.personalassistant.ingestion.connector.SourceIterable;
import io.personalassistant.ingestion.connector.TimeWindow;
import io.personalassistant.ingestion.connector.TokenWindowGrabber;
import io.personalassistant.ingestion.connector.google.GoogleAccessTokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gmail connector. Where the local filesystem is a no-auth walk, Gmail is the interesting case that
 * proves the {@link TokenWindowGrabber} base against a real cloud API: OAuth bearer auth, opaque
 * page-token pagination, an N+1 list→get fetch shape, and a source whose native order (newest-first)
 * differs from the anchor-relative window the framework asks for.
 *
 * <h2>Iterables</h2>
 * When {@code inputs.labelIds} lists specific labels, each becomes its own independently-paged
 * iterable (a natural sub-stream — INBOX, a project label…). With none configured the connector
 * exposes a single {@code all-mail} iterable that pages every message. A message carrying several
 * labels is grabbed once per matching label iterable but upserts to the same {@code (knowledge,
 * externalId)} entity, so storage never duplicates it.
 *
 * <h2>Pagination</h2>
 * Gmail's {@code messages.list} always returns newest-first and paginates by opaque token; the only
 * lever on the window is the {@code after:}/{@code before:} search predicate. That is exactly the shape
 * {@link TokenWindowGrabber} drives, so this connector extends it and implements a single
 * {@link #fetchWindow}: translate the {@link TimeWindow} to {@code after:}/{@code before:}, list, map.
 * The base owns the forward high-water floor and the backward token sweep — the connector never
 * branches on direction. {@code after:} is applied at second granularity, so a boundary second can
 * re-list a handful of already-seen messages; the ingestion runner's checksum change-detection drops
 * them, guaranteeing progress without gaps. Bodies travel inline in {@link RawItem#text()} (subject +
 * participants + plain-text body), never as a file ref.
 */
@ApplicationScoped
public class GmailConnector extends TokenWindowGrabber {

    static final String ALL_MAIL_ITERABLE = "all-mail";
    private static final String LABEL_KEY = "labelId";

    private final GmailApi api;
    private final GoogleAccessTokens tokens;
    private final ConnectionResolver connections;

    @Inject
    public GmailConnector(GmailApi api, GoogleAccessTokens tokens, ConnectionResolver connections) {
        this.api = api;
        this.tokens = tokens;
        this.connections = connections;
    }

    @Override
    public SourceType type() {
        return SourceType.GMAIL;
    }

    @Override
    public boolean requiresConnection() {
        return true; // Gmail is OAuth-authenticated through a Connection
    }

    @Override
    public boolean hasDynamicIterables() {
        return true; // new labels can appear after activation
    }

    @Override
    public SyncSchedule defaultSchedule() {
        // No push channel wired up, so incremental sync is a poll. A 15-minute cadence keeps mail
        // reasonably fresh without hammering the API; users can override per-knowledge.
        return SyncSchedule.ofInterval(Duration.ofMinutes(15));
    }

    @Override
    public String membershipSignature(Map<String, Object> inputs) {
        // Only `query` is a within-iterable filter (design §3.2): it is combined into EVERY iterable's
        // fetch, so changing it moves the membership boundary *inside* each surviving iterable — the
        // case a discovery diff can't see, which must trigger a re-walk. `labelIds` is deliberately
        // NOT here: each label is its own iterable, so adding/removing a label is an iterable
        // add/remove handled by reconcile (§3.1: create/park), and a given label iterable's messages
        // don't change because a *different* label was added. Including labelIds would force a
        // needless re-walk of the untouched labels on every label add/remove.
        Object query = inputs == null ? null : inputs.get("query");
        return "query=" + query;
    }

    @Override
    public void verifyConnection(Connection connection) {
        String token = tokens.bearer(connection);
        JsonNode profile = api.getProfile(token);
        if (!profile.hasNonNull("emailAddress")) {
            throw new IllegalArgumentException(
                    "Gmail credentials did not resolve to a mailbox for connection " + connection.id());
        }
    }

    @Override
    public void verify(Knowledge knowledge) {
        // Inputs are all optional for Gmail (labelIds/query); credentials are verified at the
        // connection level via verifyConnection. Nothing knowledge-specific to validate here.
    }

    @Override
    public List<SourceIterable> discover(Knowledge knowledge) {
        List<String> configured = configuredLabelIds(knowledge);
        if (configured.isEmpty()) {
            return List.of(new SourceIterable(ALL_MAIL_ITERABLE, "All mail", Map.of()));
        }
        String token = tokens.bearer(connections.resolve(knowledge));
        Map<String, String> labelNames = labelNames(token);
        List<SourceIterable> iterables = new ArrayList<>(configured.size());
        for (String labelId : configured) {
            String name = labelNames.getOrDefault(labelId, labelId);
            iterables.add(new SourceIterable(labelId, name, Map.of(LABEL_KEY, labelId)));
        }
        return iterables;
    }

    @Override
    protected Page fetchWindow(GrabContext ctx, TimeWindow window, String pageToken, int cap) {
        String token = tokens.bearer(connections.resolve(ctx.knowledge()));
        Object labelId = ctx.attributes().get(LABEL_KEY);
        List<String> labelIds = labelId == null ? List.of() : List.of(labelId.toString());
        String query = combine(str(ctx.knowledge().inputs(), "query"), windowQuery(window));

        JsonNode list = api.listMessages(token, labelIds, query, pageToken, cap);
        List<RawItem> items = new ArrayList<>();
        for (JsonNode ref : list.path("messages")) {
            RawItem item = fetchAndMap(token, ref.path("id").asText());
            if (item != null) {
                items.add(item);
            }
        }
        return new Page(items, list.path("nextPageToken").asText(null));
    }

    /**
     * Translate the window's bounds into Gmail's {@code after:}/{@code before:} search predicates
     * (second granularity). An open bound emits no predicate on that side, so the same method serves
     * the forward window ({@code after:<floor>}) and the backward window ({@code before:<anchor>}).
     */
    private static String windowQuery(TimeWindow window) {
        StringBuilder q = new StringBuilder();
        if (window.hasLo()) {
            q.append("after:").append(window.lo().toEpochMilli() / 1000);
        }
        if (window.hasHi()) {
            if (q.length() > 0) {
                q.append(' ');
            }
            q.append("before:").append(window.hi().toEpochMilli() / 1000);
        }
        return q.toString();
    }

    // ---- message -> RawItem ------------------------------------------------------------------

    private RawItem fetchAndMap(String token, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        JsonNode msg = api.getMessage(token, id);
        return toRawItem(msg);
    }

    private RawItem toRawItem(JsonNode msg) {
        String id = msg.path("id").asText();
        long internalMs = msg.path("internalDate").asLong(0L);
        Instant modifiedAt = Instant.ofEpochMilli(internalMs);

        JsonNode payload = msg.path("payload");
        Map<String, String> headers = headers(payload);
        String subject = headers.getOrDefault("subject", "(no subject)");
        String from = headers.getOrDefault("from", "");
        String to = headers.getOrDefault("to", "");
        String snippet = msg.path("snippet").asText("");
        String body = extractText(payload);
        if (body.isBlank()) {
            body = snippet; // fall back to Gmail's snippet when no text part decoded
        }

        String text = buildSearchText(subject, from, to, headers.get("date"), body);
        String uri = "https://mail.google.com/mail/u/0/#all/" + id;
        // Gmail message content is immutable except labels; historyId bumps on any change to it.
        String checksum = "gmail:" + id + ";hist:" + msg.path("historyId").asText("");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("threadId", msg.path("threadId").asText(null));
        raw.put("internalDate", internalMs);
        raw.put("labelIds", labelList(msg));
        raw.put("historyId", msg.path("historyId").asText(null));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", subject);
        metadata.put("uri", uri);
        metadata.put("from", from);
        metadata.put("to", to);
        metadata.put("modifiedAt", modifiedAt);

        return new RawItem(id, EntityType.EMAIL, "text/plain", subject, uri, checksum,
                modifiedAt, raw, text, null, metadata, false);
    }

    private static String buildSearchText(String subject, String from, String to, String date,
                                          String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(subject).append('\n');
        if (from != null && !from.isBlank()) {
            sb.append("From: ").append(from).append('\n');
        }
        if (to != null && !to.isBlank()) {
            sb.append("To: ").append(to).append('\n');
        }
        if (date != null && !date.isBlank()) {
            sb.append("Date: ").append(date).append('\n');
        }
        sb.append('\n').append(body);
        return sb.toString();
    }

    /** Depth-first search of the MIME tree for the best textual body (prefer text/plain). */
    private static String extractText(JsonNode payload) {
        String plain = findPart(payload, "text/plain");
        if (plain != null) {
            return plain;
        }
        String html = findPart(payload, "text/html");
        return html == null ? "" : stripHtml(html);
    }

    private static String findPart(JsonNode node, String mimeType) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (mimeType.equals(node.path("mimeType").asText(""))) {
            String data = node.path("body").path("data").asText(null);
            if (data != null) {
                return decodeBase64Url(data);
            }
        }
        for (JsonNode part : node.path("parts")) {
            String found = findPart(part, mimeType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Map<String, String> headers(JsonNode payload) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode h : payload.path("headers")) {
            out.put(h.path("name").asText("").toLowerCase(), h.path("value").asText(""));
        }
        return out;
    }

    private static List<String> labelList(JsonNode msg) {
        List<String> labels = new ArrayList<>();
        for (JsonNode l : msg.path("labelIds")) {
            labels.add(l.asText());
        }
        return labels;
    }

    private static String decodeBase64Url(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data.replaceAll("\\s", "")));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Crude tag strip — good enough to make an HTML-only mail searchable; Tika is not on this path. */
    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style).*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    // ---- inputs / labels ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> configuredLabelIds(Knowledge knowledge) {
        Object v = knowledge.inputs() == null ? null : knowledge.inputs().get("labelIds");
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        return List.of();
    }

    private Map<String, String> labelNames(String token) {
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode label : api.listLabels(token).path("labels")) {
            names.put(label.path("id").asText(), label.path("name").asText());
        }
        return names;
    }

    private static String combine(String extraQuery, String window) {
        return extraQuery == null || extraQuery.isBlank() ? window : "(" + extraQuery + ") " + window;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }
}
