package io.akka.pocketbase.domain;

import java.util.Map;

/**
 * What a rule can see of the caller — SPEC-001 §2.4.
 *
 * <p>{@code auth} is null for a guest. On the realtime path {@code context} is always "realtime"
 * and {@code method} is always "GET"; both are addressable from a rule.
 */
public record RequestInfo(
    AuthIdentity auth,
    Map<String, String> query,
    Map<String, String> headers,
    String context,
    String method) {

  public static RequestInfo guest(Map<String, String> query, Map<String, String> headers) {
    return new RequestInfo(null, query, headers, "realtime", "GET");
  }

  public static RequestInfo authenticated(
      AuthIdentity auth, Map<String, String> query, Map<String, String> headers) {
    return new RequestInfo(auth, query, headers, "realtime", "GET");
  }

  public boolean isSuperuser() {
    return auth != null && auth.superuser();
  }
}
