package io.personalassistant.storage.search.opensearch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenSearchBulkErrorsTest {

    private final OpenSearchSearchIndex index = new OpenSearchSearchIndex(null, "chunks");
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode response(String items) throws JsonProcessingException {
        return mapper.readTree("{\"errors\":true,\"items\":[" + items + "]}");
    }

    private static String failedItem(String id, String type, String reason) {
        return "{\"index\":{\"_id\":\"" + id + "\",\"status\":400,\"error\":{\"type\":\"" + type
                + "\",\"reason\":\"" + reason + "\"}}}";
    }

    private static final String OK_ITEM = "{\"index\":{\"_id\":\"ent_1_0\",\"status\":201}}";

    @Test
    void namesTheFailedDocumentAndTheReason() throws JsonProcessingException {
        String summary = index.bulkFailureSummary(
                response(OK_ITEM + "," + failedItem("ent_1_1", "mapper_parsing_exception", "bad vector")), 2);

        assertTrue(summary.contains("1 of 2"), "counts failures against the batch: " + summary);
        assertTrue(summary.contains("ent_1_1"), "names the rejected document: " + summary);
        assertTrue(summary.contains("mapper_parsing_exception"), "carries the error type: " + summary);
        assertTrue(summary.contains("bad vector"), "carries the reason: " + summary);
    }

    @Test
    void succeededItemsAreNotCountedAsFailures() throws JsonProcessingException {
        String summary = index.bulkFailureSummary(response(OK_ITEM + "," + OK_ITEM), 2);
        assertTrue(summary.startsWith("0 of 2"), "an all-clear batch reports no failures: " + summary);
    }

    @Test
    void manyFailuresAreTruncatedButStillCounted() throws JsonProcessingException {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            items.append(i == 0 ? "" : ",").append(failedItem("ent_1_" + i, "es_rejected_execution", "queue full"));
        }
        String summary = index.bulkFailureSummary(response(items.toString()), 7);

        assertTrue(summary.contains("7 of 7"), "every failure is counted: " + summary);
        assertTrue(summary.contains("and 4 more"), "the tail is summarised rather than dumped: " + summary);
    }
}
