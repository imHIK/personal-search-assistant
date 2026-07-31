package io.personalassistant.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.personalassistant.domain.model.Knowledge;
import io.personalassistant.domain.model.enums.SourceType;
import io.personalassistant.testsupport.TestData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkingSpecResolverTest {

    private ChunkingSpecResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ChunkingSpecResolver();
        resolver.defaultStrategy = "recursive";
        resolver.defaultSize = 1000;
        resolver.defaultOverlap = 150;
        resolver.defaultTokenSize = 256;
        resolver.defaultTokenOverlap = 32;
    }

    @Test
    void inheritsGlobalDefaultsWhenUnset() {
        var kn = TestData.knowledgeWithChunking("kn", SourceType.LOCAL_FS, Knowledge.ChunkingSettings.inherit());
        ChunkingSpec spec = resolver.resolve(kn);

        assertEquals("recursive", spec.strategy());
        assertEquals(1000, spec.maxSize());
        assertEquals(150, spec.overlap());
    }

    @Test
    void overridesStrategyAndSizeButInheritsUnsetLeaves() {
        var kn = TestData.knowledgeWithChunking("kn", SourceType.LOCAL_FS,
                new Knowledge.ChunkingSettings("character", 500, null, List.of()));
        ChunkingSpec spec = resolver.resolve(kn);

        assertEquals("character", spec.strategy());
        assertEquals(500, spec.maxSize());
        assertEquals(150, spec.overlap(), "unset overlap inherits the global default");
    }

    @Test
    void tokenStrategyDefaultsToTokenSizes() {
        var kn = TestData.knowledgeWithChunking("kn", SourceType.LOCAL_FS,
                new Knowledge.ChunkingSettings("token", null, null, List.of()));
        ChunkingSpec spec = resolver.resolve(kn);

        assertEquals("token", spec.strategy());
        assertEquals(256, spec.maxSize(), "token strategy inherits the token-scaled default size");
        assertEquals(32, spec.overlap());
    }

    @Test
    void customSeparatorsArePassedThrough() {
        var kn = TestData.knowledgeWithChunking("kn", SourceType.LOCAL_FS,
                new Knowledge.ChunkingSettings("recursive", null, null, List.of("##", "\n")));
        ChunkingSpec spec = resolver.resolve(kn);

        assertEquals(List.of("##", "\n"), spec.separators());
    }
}
