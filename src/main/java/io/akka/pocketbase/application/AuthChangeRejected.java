package io.akka.pocketbase.application;

/** A subscribe call that would change who a client is, rather than raise it from guest. */
public class AuthChangeRejected extends RuntimeException {
  public AuthChangeRejected(String message) {
    super(message);
  }
}
