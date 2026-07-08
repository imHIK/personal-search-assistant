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
 * Token-based chunking sized by the same family of tokenizer the embedding model uses, so chunks fit
 * the model's token window precisely instead of guessing from character length. It uses the
 * HuggingFace tokenizer already on the classpath (via DJL) to measure the document's token density,
 * then slides a window over the <em>original</em> text calibrated to that density — the chunk text is
 * always a verbatim substring (good for display/citation and for the embedder to re-encode), while
 * {@code maxSize}/{@code overlap} are honoured in tokens rather than characters.
 *
 * <p>The tokenizer is loaded lazily and, if unavailable (offline, missing model), the strategy
 * degrades gracefully to a ~4-chars-per-token approximation so indexing never breaks. Density is
 * estimated from a sample of the document head, keeping this O(1) tokenizer calls per document.
 */
@ApplicationScoped
public class TokenChunkingStrategy implements ChunkingStrategy {

    static final String NAME = "token";

    private static final Logger LOG = Logger.getLogger(TokenChunkingStrategy.class.getName());

    /** Chars per token-count probe — small enough that no single probe hits a tokenizer length cap. */
    private static final int PROBE_WINDOW = 1000;

    /** Chars sampled from the head to estimate token density; density is ~uniform within a document. */
    private static final int DENSITY_SAMPLE = 20_000;

    @ConfigProperty(name = "app.chunking.token.tokenizer", defaultValue = "bert-base-uncased")
    String tokenizerId;

    /** Lazily initialised token counter; HF-backed when available, else an approximation. Package-private for tests. */
    volatile TokenCounter counter;

    /** How chunk size is measured: number of tokens in a piece of text. */
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

    /** Estimate characters-per-token from a head sample, so window sizing tracks the real token rate. */
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
            // encode(String) is the version-stable overload; the few special tokens it adds are a
            // negligible constant for the density estimate this feeds.
            return text -> tokenizer.encode(text).getIds().length;
        } catch (Throwable t) {
            LOG.warning("HuggingFace tokenizer '" + tokenizerId + "' unavailable (" + t.getMessage()
                    + "); falling back to ~4 chars/token approximation for token chunking");
            return text -> Math.max(1, Math.round(text.length() / 4f));
        }
    }
}
