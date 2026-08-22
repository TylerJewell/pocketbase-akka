package io.akka.pocketbase.domain;

import akka.javasdk.annotations.TypeName;
import java.util.Map;

/**
 * What happened to a record.
 *
 * <p>Every event carries the field values it left behind — including the delete, which carries the
 * values as they stood immediately before it. That is what lets a rule still decide who may hear
 * about a deletion once the record is gone (SPEC-001 D6), and it is why this port needs no
 * equivalent of the source's pre-delete message cache.
 */
public sealed interface RecordEvent {

  String collectionName();

  String recordId();

  Map<String, Object> fields();

  @TypeName("record-created")
  record Created(String collectionName, String recordId, Map<String, Object> fields)
      implements RecordEvent {}

  @TypeName("record-updated")
  record Updated(String collectionName, String recordId, Map<String, Object> fields)
      implements RecordEvent {}

  @TypeName("record-deleted")
  record Deleted(String collectionName, String recordId, Map<String, Object> fields)
      implements RecordEvent {}
}
