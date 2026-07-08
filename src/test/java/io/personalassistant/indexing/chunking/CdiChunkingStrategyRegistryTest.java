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
}
