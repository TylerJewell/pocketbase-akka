package io.akka.pocketbase.domain;

import java.util.Map;

/** The per-topic query and headers a subscription string carries — SPEC-001 §2.3. */
public record SubscriptionOptions(Map<String, String> query, Map<String, String> headers) {

  public static final SubscriptionOptions EMPTY = new SubscriptionOptions(Map.of(), Map.of());
}
