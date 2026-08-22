package io.akka.pocketbase.domain;

/** A rule string that cannot be read. Denies, per SPEC-001 A5. */
public class RuleSyntaxException extends RuntimeException {
  public RuleSyntaxException(String message) {
    super(message);
  }
}
