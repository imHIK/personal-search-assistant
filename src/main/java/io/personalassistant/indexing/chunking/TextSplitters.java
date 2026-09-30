package io.personalassistant.indexing.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/** LangChain's splitting algorithm, so behaviour matches what users of that ecosystem expect. */
final class TextSplitters {

    private TextSplitters() {
    }

    /** An empty separator splits into characters. Drops empties. */
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
     * A single fragment longer than maxSize is emitted whole; the recursive split descends before that
     * happens.
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
                // Drop from the front until the retained tail fits the overlap budget.
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

    /** The list must end with {@code ""} so the recursion bottoms out. */
    static List<String> recursiveSplit(String text, List<String> separators, int maxSize, int overlap) {
        List<String> finalChunks = new ArrayList<>();

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
                if (!goodSplits.isEmpty()) {
                    finalChunks.addAll(mergeSplits(goodSplits, separator, maxSize, overlap));
                    goodSplits.clear();
                }
                if (remaining.isEmpty()) {
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
