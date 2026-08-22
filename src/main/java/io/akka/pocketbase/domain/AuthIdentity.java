package io.akka.pocketbase.domain;

import java.util.Map;

/** Who a request is, as far as a rule is concerned — SPEC-001 §2.4. */
public record AuthIdentity(
    String collectionName, String id, Map<String, Object> fields, boolean superuser) {}
