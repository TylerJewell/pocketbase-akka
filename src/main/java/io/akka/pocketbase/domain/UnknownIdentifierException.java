package io.akka.pocketbase.domain;

/** A rule naming something the collection and the request between them do not have. */
public class UnknownIdentifierException extends RuntimeException {
  public UnknownIdentifierException(String identifier) {
    super("unknown identifier " + identifier);
  }
}
