package io.akka.pocketbase.domain;

/** One lifecycle event on a record. Action is {@code create}, {@code update} or {@code delete}. */
public record RecordChange(String action, RecordSnapshot record) {}
