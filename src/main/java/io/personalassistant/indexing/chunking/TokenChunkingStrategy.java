package io.personalassistant.indexing.chunking;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.personalassistant.domain.model.Chunk;
import io.personalassistant.domain.model.Entity;
import io.personalassistant.domain.model.enums.SourceType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Measures the document's token density with the embedding model's tokenizer family, then slides a character
 * window calibrated to it: chunks are verbatim substrings sized in tokens. Without the tokenizer (offline) it
 * assumes about 4 characters per token.
 */
@ApplicationScoped
public class TokenChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "token";

    private static final Logger LOG = Logger.getLogger(TokenChunkingStrategy.class.getName());

    /** Small enough that no probe hits a tokenizer length cap. */
    private static final int PROBE_WINDOW = 1000;

    private static final int DENSITY_SAMPLE = 20_000;

    @ConfigProperty(name = "app.chunking.token.tokenizer", defaultValue = "bert-base-uncased")
    String tokenizerId;

    volatile TokenCounter counter;

    interface TokenCounter {
        int count(String text);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Chunk> chunk(Entity entity, SourceType sourceType, String text, ChunkingSpec spec) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        double charsPerToken = charsPerToken(text);
        int charSize = Math.max(1, (int) Math.round(spec.maxSize() * charsPerToken));
        int charOverlap = Math.min(charSize - 1, Math.max(0, (int) Math.round(spec.overlap() * charsPerToken)));
        int step = Math.max(1, charSize - charOverlap);

        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + charSize);
            pieces.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return ChunkSupport.toChunks(entity, sourceType, pieces);
    }

    private double charsPerToken(String text) {
        int sampleChars = Math.min(text.length(), DENSITY_SAMPLE);
        int tokens = countTokens(text.substring(0, sampleChars));
        return tokens <= 0 ? 4.0 : Math.max(1.0, (double) sampleChars / tokens);
    }

    private int countTokens(String text) {
        TokenCounter c = counter();
        int total = 0;
        for (int i = 0; i < text.length(); i += PROBE_WINDOW) {
            total += c.count(text.substring(i, Math.min(text.length(), i + PROBE_WINDOW)));
        }
        return total;
    }

    private TokenCounter counter() {
        TokenCounter c = counter;
        if (c == null) {
            synchronized (this) {
                c = counter;
                if (c == null) {
                    c = loadCounter();
                    counter = c;
                }
            }
        }
        return c;
    }

    private TokenCounter loadCounter() {
        try {
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerId);
            LOG.info("Token chunking using HuggingFace tokenizer '" + tokenizerId + "'");
            // encode(String) is the version-stable overload.
            return text -> tokenizer.encode(text).getIds().length;
        } catch (Throwable t) {
            LOG.warning("HuggingFace tokenizer '" + tokenizerId + "' unavailable (" + t.getMessage()
                    + "); falling back to ~4 chars/token approximation for token chunking");
            return text -> Math.max(1, Math.round(text.length() / 4f));
        }
    }
}
