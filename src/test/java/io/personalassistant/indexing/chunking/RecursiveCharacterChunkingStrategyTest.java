package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecursiveCharacterChunkingStrategyTest {

    private final RecursiveCharacterChunkingStrategy chunker = new RecursiveCharacterChunkingStrategy();

    private static ChunkingSpec spec(int size, int overlap) {
        return new ChunkingSpec(RecursiveCharacterChunkingStrategy.NAME, size, overlap, List.of());
    }

    @Test
    void splitsOnParagraphBoundariesFirst() {
        var entity = TestData.ingestedText("ent_1", "kn_1", "doc", "x");
        // Three 4-char paragraphs separated by blank lines; with size 5 none can be merged (join
        // would exceed 5), so each paragraph becomes its own chunk on the top separator.
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "aaaa\n\nbbbb\n\ncccc", spec(5, 0));

        assertEquals(List.of("aaaa", "bbbb", "cccc"), chunks.stream().map(Chunk::text).toList());
        assertEquals("ent_1_0", chunks.get(0).id());
        assertEquals(2, chunks.get(2).ordinal());
    }

    @Test
    void neverExceedsMaxSizeAndDropsNothingBlank() {
        var entity = TestData.ingestedText("ent_2", "kn_1", "doc", "x");
        String prose = "Sentence one is here. Sentence two follows. Sentence three ends it all now.";
        // Overlap 0 so the target is a hard ceiling (overlap carry can push a chunk slightly over).
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, prose, spec(30, 0));

        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 30, "recursive chunks must respect maxSize: <" + c.text() + ">");
            assertFalse(c.text().isBlank(), "no blank chunks");
        }
    }

    @Test
    void blankTextYieldsNoChunks() {
        var entity = TestData.ingestedText("ent_3", "kn_1", "doc", "x");
        assertTrue(chunker.chunk(entity, SourceType.LOCAL_FS, "   ", spec(20, 5)).isEmpty());
    }
}
