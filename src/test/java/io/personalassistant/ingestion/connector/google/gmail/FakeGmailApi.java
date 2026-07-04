package io.personalassistant.ingestion.connector.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory {@link GmailApi} for connector tests. It models the parts of Gmail the connector leans
 * on: label filtering, {@code after:}/{@code before:} second-granularity windows, newest-first
 * ordering, and opaque (here: numeric-offset) page tokens. This lets the tests exercise the real
 * pagination + high-water logic without any network.
 */
class FakeGmailApi implements GmailApi {

    private static final Pattern AFTER = Pattern.compile("after:(\\d+)");
    private static final Pattern BEFORE = Pattern.compile("before:(\\d+)");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Msg> messages = new ArrayList<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private String emailAddress = "user@example.com";

    /** Total {@code listMessages} calls — lets a test assert the connector paginates rather than refetches. */
    int listCalls;

    FakeGmailApi add(String id, long internalMs, List<String> labelIds, String subject,
                     String from, String body) {
        messages.add(new Msg(id, internalMs, labelIds, subject, from, "team@example.com", body));
        return this;
    }

    FakeGmailApi label(String id, String name) {
        labels.put(id, name);
        return this;
    }

    @Override
    public JsonNode listMessages(String accessToken, List<String> labelIds, String query,
                                 String pageToken, int maxResults) {
        listCalls++;
        long afterSec = parse(AFTER, query);
        long beforeSec = parse(BEFORE, query);

        List<Msg> matched = new ArrayList<>();
        for (Msg m : messages) {
            if (labelIds != null && !labelIds.isEmpty() && !m.labelIds.containsAll(labelIds)) {
                continue;
            }
            long sec = m.internalMs / 1000;
            if (afterSec >= 0 && sec < afterSec) {
                continue;
            }
            if (beforeSec >= 0 && sec >= beforeSec) {
                continue;
            }
            matched.add(m);
        }
        matched.sort(Comparator.comparingLong((Msg m) -> m.internalMs).reversed()); // newest first

        int offset = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
        int end = Math.min(matched.size(), offset + maxResults);

        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = result.putArray("messages");
        for (int i = offset; i < end; i++) {
            arr.addObject().put("id", matched.get(i).id).put("threadId", "t_" + matched.get(i).id);
        }
        if (end < matched.size()) {
            result.put("nextPageToken", String.valueOf(end));
        }
        return result;
    }

    @Override
    public JsonNode getMessage(String accessToken, String id) {
        Msg m = messages.stream().filter(x -> x.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such message " + id));
        ObjectNode msg = mapper.createObjectNode();
        msg.put("id", m.id);
        msg.put("threadId", "t_" + m.id);
        msg.put("internalDate", String.valueOf(m.internalMs));
        msg.put("historyId", "h" + m.internalMs);
        msg.put("snippet", m.body.length() > 40 ? m.body.substring(0, 40) : m.body);
        ArrayNode labelArr = msg.putArray("labelIds");
        m.labelIds.forEach(labelArr::add);

        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", "text/plain");
        ArrayNode headers = payload.putArray("headers");
        header(headers, "Subject", m.subject);
        header(headers, "From", m.from);
        header(headers, "To", m.to);
        payload.putObject("body").put("data",
                Base64.getUrlEncoder().encodeToString(m.body.getBytes()));
        return msg;
    }

    @Override
    public JsonNode listLabels(String accessToken) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = result.putArray("labels");
        labels.forEach((id, name) -> arr.addObject().put("id", id).put("name", name).put("type", "user"));
        return result;
    }

    @Override
    public JsonNode getProfile(String accessToken) {
        return mapper.createObjectNode().put("emailAddress", emailAddress);
    }

    private void header(ArrayNode headers, String name, String value) {
        headers.addObject().put("name", name).put("value", value);
    }

    private static long parse(Pattern p, String query) {
        if (query == null) {
            return -1;
        }
        Matcher m = p.matcher(query);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private record Msg(String id, long internalMs, List<String> labelIds, String subject,
                       String from, String to, String body) {
    }
}
