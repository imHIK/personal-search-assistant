package io.personalassistant.indexing.chunking;

import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Document;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Default chunker: fixed-size character windows with overlap. Simple and dependency-free;
 * a sentence/heading-aware strategy can replace it by implementing the same port.
 */
@ApplicationScoped
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    @ConfigProperty(name = "app.chunking.size", defaultValue = "1000")
    int size;

    @ConfigProperty(name = "app.chunking.overlap", defaultValue = "150")
    int overlap;

    @Override
    public String name() {
        return "fixed-size";
    }

    @Override
    public List<Chunk> chunk(Document document) {
        String text = document.text() == null ? "" : document.text();
        List<Chunk> out = new ArrayList<>();
        if (text.isBlank()) {
            return out;
        }
        int step = Math.max(1, size - overlap);
        int ordinal = 0;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + size);
            String piece = text.substring(start, end);
            String id = document.id() + "_" + ordinal;
            out.add(new Chunk(id, document.id(), document.sourceId(), ordinal,
                    piece, estimateTokens(piece), null, Map.of()));
            ordinal++;
            if (end == text.length()) {
                break;
            }
        }
        return out;
    }

    /** Rough heuristic: ~4 characters per token. */
    private int estimateTokens(String s) {
        return Math.max(1, s.length() / 4);
    }
}
