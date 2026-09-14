package io.personalassistant.storage.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.common.ratelimit.RateLimitPolicy;
import io.personalassistant.common.ratelimit.RateLimitRule;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/**
 * The persistence mapping for a rate limit, exercised without a database — {@code BsonSupport} is pure
 * {@link Document} ↔ record conversion, and it is the only untested step between the console and Mongo.
 *
 * <p>The interesting cases are the two flavours of "no limit", which must survive the round trip as
 * different things: <em>absent</em> means the account never set one, <em>empty</em> means the user
 * removed the one it had. Both resolve to unlimited at call time, but collapsing them in storage would
 * make a cleared limit indistinguishable from an untouched one.
 */
class BsonSupportRateLimitTest {

    @Test
    void roundTripsEveryRule() {
        RateLimitPolicy policy = new RateLimitPolicy(
                List.of(new RateLimitRule(10, 1), new RateLimitRule(500, 60), new RateLimitRule(10_000, 86_400)));

        RateLimitPolicy back = BsonSupport.rateLimitPolicy(BsonSupport.rateLimit(policy));

        assertEquals(policy, back);
    }

    @Test
    void storesTheWindowAsWholeSecondsRatherThanADurationString() {
        Document doc = BsonSupport.rateLimit(RateLimitPolicy.of(new RateLimitRule(500, 60)));

        Document rule = doc.getList("rules", Document.class).get(0);
        assertEquals(500, rule.get("permits"));
        assertEquals(60L, rule.get("windowSeconds"));
    }

    @Test
    void aNullPolicyIsStoredAsAbsent() {
        assertNull(BsonSupport.rateLimit(null));
        assertNull(BsonSupport.rateLimitPolicy(null), "a document with no rateLimit reads back as null");
    }

    /** Every connection written before this field existed. */
    @Test
    void aDocumentPredatingTheFieldReadsBackAsNoLimit() {
        assertNull(BsonSupport.rateLimitPolicy(new Document("name", "Work Gmail").get("rateLimit")));
    }

    @Test
    void anEmptyRuleListSurvivesAsEmptyRatherThanAbsent() {
        Document doc = BsonSupport.rateLimit(RateLimitPolicy.UNLIMITED);

        RateLimitPolicy back = BsonSupport.rateLimitPolicy(doc);

        assertNotNull(back, "a cleared limit is stored, not dropped");
        assertTrue(back.isUnlimited());
    }

    /** Hand-edited or half-written documents must not take the whole connection down on read. */
    @Test
    void skipsMalformedRulesInsteadOfThrowing() {
        Document doc = new Document("rules", List.of(
                new Document("permits", 5).append("windowSeconds", 60),
                new Document("permits", "not a number").append("windowSeconds", 60),
                new Document("permits", 7)));

        RateLimitPolicy back = BsonSupport.rateLimitPolicy(doc);

        assertEquals(List.of(new RateLimitRule(5, 60)), back.rules());
    }
}
