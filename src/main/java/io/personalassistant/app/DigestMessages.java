package io.personalassistant.app;

import io.personalassistant.domain.model.Digest;
import io.personalassistant.domain.model.DigestRun;
import io.personalassistant.domain.model.PublishMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a digest run says when it is published: the decision whether it is worth a message at all, and
 * the projection of the run onto a channel-neutral {@link PublishMessage}.
 *
 * <p>Mirrors what the console shows for a run, so the email and the page tell the same story: the items
 * with their annotations, and the task's reply as a summary only when it annotated nothing — the same
 * text twice, once as prose and once per item, is noise in either place.
 */
final class DigestMessages {

    private DigestMessages() {
    }

    /**
     * A run is sent when it found something or failed. A quiet run is not: a daily "nothing new" trains
     * people to ignore the message that matters. A failure is, because a digest someone relies on by email
     * is exactly the one whose run history nobody opens — and "broken for a week" is what the recorded
     * failure exists to make visible.
     */
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
        return new PublishMessage(title, null, items, link, annotated ? null : run.taskOutput());
    }

    /**
     * Annotation keys as the console labels them — {@code next_step} and {@code nextStep} both read as
     * "Next step" / "Next Step" rather than as an identifier. Keys come from user-written tasks, so this
     * is the only thing that can be done with them generically.
     */
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
