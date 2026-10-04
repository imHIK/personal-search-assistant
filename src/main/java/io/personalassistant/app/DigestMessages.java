package io.personalassistant.app;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.PublishMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mirrors the console's run view: the task's reply is the summary only when it annotated nothing. */
final class DigestMessages {

    private DigestMessages() {
    }

    /** A quiet run sends nothing: a daily "nothing new" trains people to ignore the message that matters. */
    static boolean worthSending(DigestRun run) {
        return run.error() != null || !run.items().isEmpty();
    }

    /**
     * @param consoleUrl where the console is served, for the link back to the digest; blank for no link
     */
    static PublishMessage forRun(Digest digest, DigestRun run, String consoleUrl) {
        String link = consoleUrl == null || consoleUrl.isBlank()
                ? null
                : consoleUrl.replaceAll("/+$", "") + "/digests/" + digest.id();
        if (run.error() != null) {
            return new PublishMessage(digest.name() + " failed",
                    "The digest could not run: " + run.error() + "\n\nNothing from this run was marked as seen, so "
                            + "the next run that succeeds reports everything this one would have.",
                    List.of(), link, null);
        }

        List<PublishMessage.Item> items = new ArrayList<>(run.items().size());
        boolean annotated = false;
        for (DigestRun.Item item : run.items()) {
            annotated |= !item.annotations().isEmpty();
            items.add(new PublishMessage.Item(item.title(), item.uri(), item.snippet(),
                    labelled(item.annotations())));
        }
        int count = items.size();
        String title = digest.name() + " — " + count + (digest.onlyNew() ? " new" : "")
                + (count == 1 ? " result" : " results");
        String intro = run.taskError() == null ? null : "The task could not run: " + run.taskError();
        return new PublishMessage(title, intro, items, link, annotated ? null : run.taskOutput());
    }

    /** Annotation keys as the console labels them ({@code next_step} reads "Next step"). */
    static Map<String, Object> labelled(Map<String, Object> annotations) {
        Map<String, Object> out = new LinkedHashMap<>();
        annotations.forEach((key, value) -> out.put(label(key), value));
        return out;
    }

    static String label(String key) {
        String spaced = key.replaceAll("[_-]+", " ").replaceAll("([a-z])([A-Z])", "$1 $2").trim();
        return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
