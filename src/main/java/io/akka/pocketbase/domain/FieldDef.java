package io.akka.pocketbase.domain;

/** One declared field of a collection. Type is one of {@code text}, {@code number}, {@code bool}. */
public record FieldDef(String name, String type) {}
