package io.personalassistant.domain.service;

/**
 * One field of a partial edit, in the three states a PATCH body can actually express.
 *
 * <p>{@link Optional}-based patches can only say two of them — "not part of this edit" and "set to
 * this value" — and that gap is not academic. Every console control that offers a way <em>back</em> to
 * nothing ("Look back: no time limit", "Then: nothing, just list what is new", "one result per
 * document: off") sends a JSON {@code null}, which an {@code Optional.ofNullable} mapping turns into
 * "leave it alone". The edit returned 200, the console said "Saved", and the field never moved. A
 * digest could be given a look-back window but never have it taken away.
 *
 * @param present whether the edit mentions this field at all
 * @param value   what to set it to when it does — {@code null} meaning "clear it"
 */
public record Patched<T>(boolean present, T value) {

    private static final Patched<?> ABSENT = new Patched<>(false, null);

    /** Not part of this edit: leave whatever is stored. */
    @SuppressWarnings("unchecked")
    public static <T> Patched<T> absent() {
        return (Patched<T>) ABSENT;
    }

    /** Part of this edit. A {@code null} value clears the field rather than being ignored. */
    public static <T> Patched<T> of(T value) {
        return new Patched<>(true, value);
    }

    /**
     * Whichever of the two this edit means: the new value, or the stored one when the edit is silent
     * about this field.
     */
    public T orElse(T current) {
        return present ? value : current;
    }

    /** Normalizes a {@code null} reference to {@link #absent()}, so callers may pass either. */
    static <T> Patched<T> orAbsent(Patched<T> value) {
        return value == null ? absent() : value;
    }
}
