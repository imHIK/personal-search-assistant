package io.personalassistant.indexing.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure text-splitting primitives shared by the {@code character} and {@code recursive} strategies,
 * following the well-known LangChain algorithm so behaviour matches what users expect from that
 * ecosystem:
 *
 * <ul>
 *   <li>{@link #splitBySeparator} breaks text on a literal separator (or into characters when the
 *       separator is empty), dropping empty fragments.</li>
 *   <li>{@link #mergeSplits} greedily re-packs those fragments into chunks up to {@code maxSize},
 *       carrying {@code overlap} characters from the tail of one chunk into the next.</li>
 *   <li>{@link #recursiveSplit} walks a separator hierarchy (paragraph → line → sentence → word →
 *       character), only descending to a finer separator for fragments that are still too big — so
 *       natural boundaries are preserved wherever possible and hard character cuts are the last
 *       resort.</li>
 * </ul>
 *
 * <p>These operate purely on {@link String} pieces; assembling {@link io.personalassistant.domain.model.Chunk}
 * records is left to {@link ChunkSupport}.
 */
final class TextSplitters {

    private TextSplitters() {
    }

    /** Split on a literal separator; an empty separator splits into individual characters. Drops empties. */
    static List<String> splitBySeparator(String text, String separator) {
        List<String> out = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                out.add(String.valueOf(text.charAt(i)));
            }
            return out;
        }
        for (String part : text.split(Pattern.quote(separator), -1)) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    /**
     * Greedily merge fragments into chunks of at most {@code maxSize}, re-joining with {@code separator}
     * and keeping {@code overlap} trailing characters as the head of the next chunk. This is the
     * LangChain {@code _merge_splits} algorithm; a single fragment longer than {@code maxSize} is
     * emitted whole (the recursive strategy prevents that by descending first).
     */
    static List<String> mergeSplits(List<String> splits, String separator, int maxSize, int overlap) {
        int sepLen = separator.length();
        List<String> chunks = new ArrayList<>();
        Deque<String> current = new ArrayDeque<>();
        int total = 0;
        for (String piece : splits) {
            int len = piece.length();
            if (total + len + (current.isEmpty() ? 0 : sepLen) > maxSize && !current.isEmpty()) {
                String chunk = String.join(separator, current).strip();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                // Drop from the front until the retained tail fits the overlap budget (and the new
                // piece will fit), so consecutive chunks share ~overlap characters of context.
                while (!current.isEmpty()
                        && (total > overlap
                            || (total + len + (current.isEmpty() ? 0 : sepLen) > maxSize && total > 0))) {
                    total -= current.peekFirst().length() + (current.size() > 1 ? sepLen : 0);
                    current.pollFirst();
                }
            }
            current.addLast(piece);
            total += len + (current.size() > 1 ? sepLen : 0);
        }
        String tail = String.join(separator, current).strip();
        if (!tail.isEmpty()) {
            chunks.add(tail);
        }
        return chunks;
    }

    /**
     * Recursively split {@code text} using the first separator in {@code separators} that appears in
     * it, descending to finer separators only for fragments that still exceed {@code maxSize}. The
     * list must end with {@code ""} so the recursion always bottoms out at character granularity.
     */
    static List<String> recursiveSplit(String text, List<String> separators, int maxSize, int overlap) {
        List<String> finalChunks = new ArrayList<>();

        // Choose the first separator that occurs in the text; "" always matches (character split).
        String separator = separators.get(separators.size() - 1);
        List<String> remaining = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String candidate = separators.get(i);
            if (candidate.isEmpty()) {
                separator = candidate;
                break;
            }
            if (text.contains(candidate)) {
                separator = candidate;
                remaining = separators.subList(i + 1, separators.size());
                break;
            }
        }

        List<String> splits = splitBySeparator(text, separator);
        List<String> goodSplits = new ArrayList<>();
        for (String piece : splits) {
            if (piece.length() < maxSize) {
                goodSplits.add(piece);
            } else {
                // Flush the run of small pieces gathered so far, then handle the oversized one.
                if (!goodSplits.isEmpty()) {
                    finalChunks.addAll(mergeSplits(goodSplits, separator, maxSize, overlap));
                    goodSplits.clear();
                }
                if (remaining.isEmpty()) {
                    // No finer separator left — hard-window the stubborn fragment by characters.
                    finalChunks.addAll(mergeSplits(splitBySeparator(piece, ""), "", maxSize, overlap));
                } else {
                    finalChunks.addAll(recursiveSplit(piece, remaining, maxSize, overlap));
                }
            }
        }
        if (!goodSplits.isEmpty()) {
            finalChunks.addAll(mergeSplits(goodSplits, separator, maxSize, overlap));
        }
        return finalChunks;
    }
}
