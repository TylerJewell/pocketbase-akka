package io.akka.pocketbase.domain;

import java.util.Map;
import java.util.Set;

/**
 * What a rule's identifiers resolve against: the changed record's own fields and the request —
 * SPEC-001 §2.4.
 *
 * <p>{@code declaredFields} is the collection's schema rather than the record's key set, because a
 * declared field the record happens not to carry is absent (A8) while an undeclared one is an error
 * (A5), and those are different answers.
 */
public record RuleContext(
    Map<String, Object> recordFields, Set<String> declaredFields, RequestInfo request) {

  private static final String REQUEST_PREFIX = "@request.";

  public Value resolve(String identifier) {
    if (identifier.startsWith(REQUEST_PREFIX)) {
      return resolveRequest(identifier.substring(REQUEST_PREFIX.length()), identifier);
    }
    if (declaredFields.contains(identifier)) {
      return Value.of(recordFields.get(identifier));
    }
    throw new UnknownIdentifierException(identifier);
  }

  private Value resolveRequest(String path, String whole) {
    if (path.equals("context")) {
      return new Value.Text(request.context());
    }
    if (path.equals("method")) {
      return new Value.Text(request.method());
    }
    if (path.startsWith("auth.")) {
      return resolveAuth(path.substring("auth.".length()));
    }
    if (path.startsWith("query.")) {
      return Value.of(request.query().get(path.substring("query.".length())));
    }
    if (path.startsWith("headers.")) {
      return Value.of(request.headers().get(path.substring("headers.".length())));
    }
    throw new UnknownIdentifierException(whole);
  }

  /**
   * An auth field the identity does not carry reads as absent rather than denying (A11).
   *
   * <p>Deliberately unlike an undeclared record field: {@code @request.auth.tenant = ""} is a rule
   * people write to mean "this caller has no tenant", and denying it would invert that.
   */
  private Value resolveAuth(String field) {
    var auth = request.auth();
    if (auth == null) {
      return Value.ABSENT;
    }
    return switch (field) {
      case "id" -> new Value.Text(auth.id());
      case "collectionName" -> new Value.Text(auth.collectionName());
      default -> Value.of(auth.fields().get(field));
    };
  }
}
