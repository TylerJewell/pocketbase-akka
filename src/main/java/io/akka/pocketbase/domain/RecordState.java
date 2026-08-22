package io.akka.pocketbase.domain;

import java.util.Map;

/** A record as the entity holds it. {@code exists} is false before creation and after deletion. */
public record RecordState(
    String collectionName, String recordId, Map<String, Object> fields, boolean exists) {

  public static RecordState empty() {
    return new RecordState(null, null, Map.of(), false);
  }

  public RecordSnapshot snapshot() {
    return new RecordSnapshot(collectionName, recordId, fields);
  }
}
