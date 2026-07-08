package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenChunkingStrategyTest {

    private static ChunkingSpec spec(int size, int overlap) {
        return new ChunkingSpec(TokenChunkingStrategy.NAME, size, overlap, List.of());
    }

    /** With one token per character, token windows equal character windows — a clean check of the math. */
    @Test
    void windowsByTokensUsingInjectedCounter() {
        TokenChunkingStrategy chunker = new TokenChunkingStrategy();
        chunker.counter = String::length; // 1 token == 1 char
        var entity = TestData.ingestedText("ent_1", "kn_1", "doc", "x");

        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "abcdefghij", spec(5, 0));

        assertEquals(List.of("abcde", "fghij"), chunks.stream().map(Chunk::text).toList());
    }

    /** With ~4 chars per token, a 5-token window calibrates to ~20 characters. */
    @Test
    void calibratesWindowToTokenDensity() {
        TokenChunkingStrategy chunker = new TokenChunkingStrategy();
        chunker.counter = s -> Math.max(1, Math.round(s.length() / 4f)); // ~4 chars/token
        var entity = TestData.ingestedText("ent_2", "kn_1", "doc", "x");
        String text = "a".repeat(40);

        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, text, spec(5, 0));

        assertEquals(2, chunks.size());
        for (Chunk c : chunks) {
            assertEquals(20, c.text().length(), "5 tokens * ~4 chars/token ≈ 20-char window");
        }
    }

    @Test
    void overlapAndBlankHandling() {
        TokenChunkingStrategy chunker = new TokenChunkingStrategy();
        chunker.counter = String::length; // 1 token == 1 char
        var entity = TestData.ingestedText("ent_3", "kn_1", "doc", "x");

        // size 5, overlap 2 → step 3 chars over 10 chars: [0,5) [3,8) [6,10).
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "abcdefghij", spec(5, 2));
        assertEquals(List.of("abcde", "defgh", "ghij"), chunks.stream().map(Chunk::text).toList());

        assertTrue(chunker.chunk(entity, SourceType.LOCAL_FS, "   ", spec(5, 2)).isEmpty(), "blank yields none");
    }
}
