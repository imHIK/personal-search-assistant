package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import io.personalassistant.domain.model.Connection;
import io.personalassistant.domain.model.CursorPosition;
import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.RawItem;
import io.personalassistant.domain.model.SyncSchedule;
import io.personalassistant.domain.model.enums.EntityType;
import io.personalassistant.domain.model.enums.CursorDirection;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.ingestion.connector.ConnectionResolver;
import io.personalassistant.ingestion.connector.GrabPage;
import io.personalassistant.ingestion.connector.GrabRequest;
import io.personalassistant.ingestion.connector.SourceConnector;
import io.personalassistant.ingestion.connector.SourceIterable;
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
 * proves the {@link SourceConnector} SPI against a real cloud API: OAuth bearer auth, opaque
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
 * <h2>Why pagination is direction-specific</h2>
 * Gmail's {@code messages.list} always returns newest-first and paginates by opaque token; the only
 * lever on the window is the {@code after:}/{@code before:} search predicate. That shapes both walks:
 *
 * <ul>
 *   <li><b>Backward (backfill, {@code internalDate < anchor}).</b> A one-time sweep, so its order is
 *       free: we take Gmail's native newest-first with {@code before:<anchor>} and follow
 *       {@code nextPageToken} until it runs out ({@code EXHAUSTED}). The position is just the page
 *       token.</li>
 *   <li><b>Forward (incremental, {@code internalDate >= anchor}).</b> Catching new mail is
 *       timestamp-driven. We query {@code after:<floor>} (a high-water mark that starts at the
 *       anchor), page the run by token, and record the newest {@code internalDate} seen. When the run
 *       drains we persist that max as the next floor — so the next scheduled re-arm only lists mail
 *       newer than the newest we have (the forward cursor resumes from its stored position; the
 *       scheduler re-arms {@code IDLE → AVAILABLE} without resetting it). {@code after:} is applied at
 *       second granularity, so the boundary second can re-list a handful of already-seen messages;
 *       the ingestion runner's checksum change-detection drops them, guaranteeing progress without
 *       gaps.</li>
 * </ul>
 *
 * <p>The pagination state is a small {@link CursorPosition}: backward {@code {pageToken}}; forward
 * {@code {floorMs, pageToken?, maxMs?}}. Bodies travel inline in {@link RawItem#text()} (subject +
 * participants + plain-text body), never as a file ref.
 */
@ApplicationScoped
public class GmailConnector implements SourceConnector {

    static final String ALL_MAIL_ITERABLE = "all-mail";
    private static final String LABEL_KEY = "labelId";
    private static final int DEFAULT_CAP = 100;

    // CursorPosition field names this connector owns:
    private static final String POS_PAGE_TOKEN = "pageToken";
    private static final String POS_FLOOR_MS = "floorMs";   // forward: high-water boundary for the run
    private static final String POS_MAX_MS = "maxMs";       // forward: newest internalDate seen this run

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
        // Only labelIds + query change which messages belong to an iterable; a display name does not.
        Object labels = inputs == null ? null : inputs.get("labelIds");
        Object query = inputs == null ? null : inputs.get("query");
        return "labelIds=" + labels + ";query=" + query;
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
    public GrabPage grab(GrabRequest request) {
        String token = tokens.bearer(connections.resolve(request.knowledge()));
        int cap = request.maxItems() > 0 ? request.maxItems() : DEFAULT_CAP;

        Object labelId = request.attributes().get(LABEL_KEY);
        List<String> labelIds = labelId == null ? List.of() : List.of(labelId.toString());
        String extraQuery = str(request.knowledge().inputs(), "query");
        long anchorMs = request.knowledge().anchor().toEpochMilli();

        return request.direction() == CursorDirection.FORWARD
                ? grabForward(token, labelIds, extraQuery, anchorMs, request.position(), cap)
                : grabBackward(token, labelIds, extraQuery, anchorMs, request.position(), cap);
    }

    // ---- forward: incremental high-water walk ------------------------------------------------

    private GrabPage grabForward(String token, List<String> labelIds, String extraQuery,
                                 long anchorMs, CursorPosition position, int cap) {
        long floorMs = position.getLong(POS_FLOOR_MS, anchorMs);
        String pageToken = position.getString(POS_PAGE_TOKEN); // null => fresh run for this arm
        long runMaxMs = position.getLong(POS_MAX_MS, floorMs);

        String query = combine(extraQuery, "after:" + (floorMs / 1000));
        JsonNode list = api.listMessages(token, labelIds, query, pageToken, cap);

        List<RawItem> items = new ArrayList<>();
        long pageMax = runMaxMs;
        for (JsonNode ref : list.path("messages")) {
            RawItem item = fetchAndMap(token, ref.path("id").asText());
            if (item == null) {
                continue;
            }
            items.add(item);
            Long ms = item.modifiedAt() == null ? null : item.modifiedAt().toEpochMilli();
            if (ms != null && ms > pageMax) {
                pageMax = ms;
            }
        }

        String next = list.path("nextPageToken").asText(null);
        if (next != null) {
            // more pages this run: keep the same floor, carry the token + running max forward
            CursorPosition pos = CursorPosition.builder()
                    .put(POS_FLOOR_MS, floorMs)
                    .put(POS_PAGE_TOKEN, next)
                    .put(POS_MAX_MS, pageMax)
                    .build();
            return new GrabPage(items, pos, true);
        }
        // run drained: advance the floor to the newest we saw so the next arm only lists newer mail
        CursorPosition pos = CursorPosition.builder().put(POS_FLOOR_MS, pageMax).build();
        return new GrabPage(items, pos, false);
    }

    // ---- backward: newest-first backfill sweep -----------------------------------------------

    private GrabPage grabBackward(String token, List<String> labelIds, String extraQuery,
                                  long anchorMs, CursorPosition position, int cap) {
        String pageToken = position.getString(POS_PAGE_TOKEN);
        String query = combine(extraQuery, "before:" + (anchorMs / 1000));
        JsonNode list = api.listMessages(token, labelIds, query, pageToken, cap);

        List<RawItem> items = new ArrayList<>();
        for (JsonNode ref : list.path("messages")) {
            RawItem item = fetchAndMap(token, ref.path("id").asText());
            if (item != null) {
                items.add(item);
            }
        }

        String next = list.path("nextPageToken").asText(null);
        if (next == null) {
            return new GrabPage(items, position, false); // history drained -> EXHAUSTED
        }
        CursorPosition pos = CursorPosition.builder().put(POS_PAGE_TOKEN, next).build();
        return new GrabPage(items, pos, true);
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
