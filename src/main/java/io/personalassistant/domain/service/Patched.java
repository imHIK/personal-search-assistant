package io.personalassistant.domain.service;

/**
 * One field of a PATCH: absent, set, or set to null to clear it. Optional can say only two of those, and read
 * a console's null (turning something off) as "unchanged".
 */
public record Patched<T>(boolean present, T value) {

    private static final Patched<?> ABSENT = new Patched<>(false, null);

    @SuppressWarnings("unchecked")
    public static <T> Patched<T> absent() {
        return (Patched<T>) ABSENT;
    }

    /** A null value clears the field. */
    public static <T> Patched<T> of(T value) {
        return new Patched<>(true, value);
    }

    public T orElse(T current) {
        return present ? value : current;
    }

    static <T> Patched<T> orAbsent(Patched<T> value) {
        return value == null ? absent() : value;
    }
}
