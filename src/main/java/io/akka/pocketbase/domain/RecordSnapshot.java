package io.akka.pocketbase.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** A record's field values at one instant — SPEC-001 §2.2. */
public record RecordSnapshot(String collectionName, String recordId, Map<String, Object> fields) {

  /** The record as a rule and a payload see it: the declared fields, with the id alongside. */
  public Map<String, Object> withId() {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", recordId);
    out.putAll(fields);
    return out;
  }
}
