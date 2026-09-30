package io.personalassistant.testsupport;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

/** Only iteration is supported; selection and lifecycle belong to the real container. */
public class StubInstance<T> implements Instance<T> {

    private final List<T> beans;

    public StubInstance(List<T> beans) {
        this.beans = List.copyOf(beans);
    }

    @Override
    public Iterator<T> iterator() {
        return beans.iterator();
    }

    @Override
    public T get() {
        if (beans.size() != 1) {
            throw new IllegalStateException("expected exactly one bean, had " + beans.size());
        }
        return beans.get(0);
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUnsatisfied() {
        return beans.isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return beans.size() > 1;
    }

    @Override
    public void destroy(T instance) {
    }

    @Override
    public Handle<T> getHandle() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        throw new UnsupportedOperationException();
    }
}
