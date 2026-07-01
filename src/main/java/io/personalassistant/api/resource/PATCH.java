package io.personalassistant.api.resource;

import jakarta.ws.rs.HttpMethod;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAX-RS binding for the HTTP {@code PATCH} verb. The spec ships {@code @GET}/{@code @POST}/etc. but
 * not {@code @PATCH}, so we declare it ourselves via {@link HttpMethod} — the standard way to add a
 * verb. Used for partial edits (see {@code KnowledgeResource#update}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@HttpMethod("PATCH")
public @interface PATCH {
}
