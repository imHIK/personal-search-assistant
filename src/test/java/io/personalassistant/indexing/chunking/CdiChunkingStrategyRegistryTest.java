package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CdiChunkingStrategyRegistryTest {

    private final ChunkingStrategyRegistry registry = new CdiChunkingStrategyRegistry(
            List.of(new RecursiveCharacterChunkingStrategy(), new CharacterChunkingStrategy(),
                    new FixedSizeChunkingStrategy(), new TokenChunkingStrategy()),
            CharacterChunkingStrategy.NAME);

    @Test
    void selectsStrategyByName() {
        assertEquals("character", registry.get("character").name());
        assertEquals("recursive", registry.get("recursive").name());
        assertEquals("fixed-size", registry.get("fixed-size").name());
        assertEquals("token", registry.get("token").name());
    }

    @Test
    void unknownOrMissingNameFallsBackToDefault() {
        assertEquals("character", registry.get("no-such-strategy").name());
        assertEquals("character", registry.get(null).name());
        assertEquals("character", registry.defaultName());
    }

    @Test
    void exposesAllRegisteredNames() {
        assertTrue(registry.names().containsAll(Set.of("recursive", "character", "fixed-size", "token")));
    }

    @Test
    void unknownConfiguredDefaultFallsBackToRecursive() {
        ChunkingStrategyRegistry r = new CdiChunkingStrategyRegistry(
                List.of(new RecursiveCharacterChunkingStrategy(), new FixedSizeChunkingStrategy()),
                "not-registered");
        assertEquals("recursive", r.defaultName());
        assertEquals("recursive", r.get(null).name());
    }

    // ---- content-type-aware selection ---------------------------------------------------------

    private static CdiChunkingStrategyRegistry mimeAware(String configuredDefault) {
        return new CdiChunkingStrategyRegistry(
                List.of(new RecursiveCharacterChunkingStrategy(), new TableAwareChunkingStrategy(),
                        new TokenChunkingStrategy()),
                configuredDefault);
    }

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * Selection used to be keyed purely on a per-knowledge name, so a spreadsheet living in a knowledge
     * of mostly prose was chunked as prose — its rows split by character count, its header row confined
     * to the first chunk.
     */
    @Test
    void contentTypeBreaksTheTieWhenTheStrategyIsOnlyTheInheritedDefault() {
        CdiChunkingStrategyRegistry registry = mimeAware(RecursiveCharacterChunkingStrategy.NAME);

        assertEquals("table", registry.get(RecursiveCharacterChunkingStrategy.NAME, XLSX).name());
        assertEquals("recursive", registry.get(RecursiveCharacterChunkingStrategy.NAME, "application/pdf").name());
        assertEquals("recursive", registry.get(RecursiveCharacterChunkingStrategy.NAME, null).name(),
                "no content type behaves exactly like name-only lookup");
    }

    /** A deliberate per-knowledge choice must never be second-guessed by content type. */
    @Test
    void anExplicitStrategyWinsOverTheContentTypePreference() {
        assertEquals("token", mimeAware(RecursiveCharacterChunkingStrategy.NAME).get("token", XLSX).name());
    }

    @Test
    void theFeatureCanBeTurnedOff() {
        CdiChunkingStrategyRegistry registry = mimeAware(RecursiveCharacterChunkingStrategy.NAME);
        registry.mimeAware = false;

        assertEquals("recursive", registry.get(RecursiveCharacterChunkingStrategy.NAME, XLSX).name());
    }

    @Test
    void aTypeNoStrategyPrefersFallsBackToTheDefault() {
        assertEquals("recursive",
                mimeAware(RecursiveCharacterChunkingStrategy.NAME).get(null, "text/plain").name());
    }
}
