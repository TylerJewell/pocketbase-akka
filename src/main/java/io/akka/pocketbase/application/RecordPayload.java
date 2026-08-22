package io.akka.pocketbase.application;

import java.util.Map;

/** What a subscriber is told about a change — SPEC-001 D5. */
public record RecordPayload(String action, Map<String, Object> record) {}
