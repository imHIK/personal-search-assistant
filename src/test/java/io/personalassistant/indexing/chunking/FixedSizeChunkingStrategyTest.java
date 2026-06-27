package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedSizeChunkingStrategyTest {

    private FixedSizeChunkingStrategy chunker;

    @BeforeEach
    void setUp() {
        chunker = new FixedSizeChunkingStrategy();
        chunker.size = 10;
        chunker.overlap = 4;
    }

    @Test
    void splitsWithOverlapAndStableIds() {
        var entity = TestData.ingestedText("ent_1", "kn_1", "doc.txt", "x");
        String text = "abcdefghijklmnopqrstuvwxy"; // length 25, step = size-overlap = 6
        List<Chunk> chunks = chunker.chunk(entity, SourceType.LOCAL_FS, text);

        assertEquals(4, chunks.size());
        assertEquals("ent_1_0", chunks.get(0).id());
        assertEquals("abcdefghij", chunks.get(0).text());
        assertEquals("ghijklmnop", chunks.get(1).text(), "windows should overlap by 4 chars");
        assertEquals("kn_1", chunks.get(0).knowledgeId());
        assertEquals(SourceType.LOCAL_FS, chunks.get(0).sourceType());
        assertEquals("doc.txt", chunks.get(0).title());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).ordinal());
        }
    }

    @Test
    void blankTextYieldsNoChunks() {
        var entity = TestData.ingestedText("ent_2", "kn_1", "empty.txt", "x");
        assertTrue(chunker.chunk(entity, SourceType.LOCAL_FS, "   ").isEmpty());
        assertFalse(chunker.chunk(entity, SourceType.LOCAL_FS, "hello").isEmpty());
        Instant.now(); // touch import
    }
}
