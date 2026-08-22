package io.akka.pocketbase.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.pocketbase.domain.RecordEvent;
import io.akka.pocketbase.domain.RecordState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One record, keyed {@code <collection>::<recordId>} — SPEC-001 §2.2.
 *
 * <p>The journal is what makes the delete rule (D7) hold without a cache in front of it: a delete
 * that does not commit writes no event, so there is nothing for a subscriber to be told about, and
 * a delete that does commit leaves an event carrying the values the record held on the way out.
 */
@Component(id = "record")
public class RecordEntity extends EventSourcedEntity<RecordState, RecordEvent> {

  public record Create(String collectionName, Map<String, Object> fields) {}

  public record Update(Map<String, Object> fields) {}

  public static String entityId(String collectionName, String recordId) {
    return collectionName + "::" + recordId;
  }

  private final String recordId;

  public RecordEntity(EventSourcedEntityContext context) {
    var id = context.entityId();
    int sep = id.indexOf("::");
    this.recordId = sep < 0 ? id : id.substring(sep + 2);
  }

  @Override
  public RecordState emptyState() {
    return RecordState.empty();
  }

  public Effect<String> create(Create command) {
    if (currentState().exists()) {
      return effects().error("record already exists");
    }
    return effects()
        .persist(new RecordEvent.Created(command.collectionName(), recordId, command.fields()))
        .thenReply(state -> state.recordId());
  }

  /** A partial update: the named fields replace their previous values and the rest stand. */
  public Effect<String> update(Update command) {
    if (!currentState().exists()) {
      return effects().error("no such record");
    }
    var merged = new LinkedHashMap<>(currentState().fields());
    merged.putAll(command.fields());
    return effects()
        .persist(new RecordEvent.Updated(currentState().collectionName(), recordId, merged))
        .thenReply(state -> state.recordId());
  }

  public Effect<String> delete() {
    if (!currentState().exists()) {
      return effects().error("no such record");
    }
    return effects()
        .persist(
            new RecordEvent.Deleted(
                currentState().collectionName(), recordId, currentState().fields()))
        .thenReply(state -> recordId);
  }

  public ReadOnlyEffect<RecordState> get() {
    return effects().reply(currentState());
  }

  @Override
  public RecordState applyEvent(RecordEvent event) {
    return switch (event) {
      case RecordEvent.Created e ->
          new RecordState(e.collectionName(), e.recordId(), e.fields(), true);
      case RecordEvent.Updated e ->
          new RecordState(e.collectionName(), e.recordId(), e.fields(), true);
      case RecordEvent.Deleted e ->
          new RecordState(e.collectionName(), e.recordId(), Map.of(), false);
    };
  }
}
