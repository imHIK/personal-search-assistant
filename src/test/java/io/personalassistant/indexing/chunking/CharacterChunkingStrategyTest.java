package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterChunkingStrategyTest {

    private final CharacterChunkingStrategy chunker = new CharacterChunkingStrategy();

    private static ChunkingSpec spec(int size, int overlap, List<String> separators) {
        return new ChunkingSpec(CharacterChunkingStrategy.NAME, size, overlap, separators);
    }

    @Test
    void splitsOnDefaultBlankLineSeparator() {
        var entity = TestData.ingestedText("ent_1", "kn_1", "doc", "x");
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "aaaa\n\nbbbb\n\ncccc", spec(5, 0, List.of()));

        assertEquals(List.of("aaaa", "bbbb", "cccc"), chunks.stream().map(Chunk::text).toList());
    }

    @Test
    void hardWindowsAnOversizedSegmentAsSafetyNet() {
        var entity = TestData.ingestedText("ent_2", "kn_1", "doc", "x");
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "abcdefghij", spec(5, 0, List.of()));

        assertEquals(List.of("abcde", "fghij"), chunks.stream().map(Chunk::text).toList());
    }

    @Test
    void honoursACustomSeparator() {
        var entity = TestData.ingestedText("ent_3", "kn_1", "doc", "x");
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, "one|two|three", spec(5, 0, List.of("|")));

        assertEquals(List.of("one", "two", "three"), chunks.stream().map(Chunk::text).toList());
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 5);
        }
    }
}
