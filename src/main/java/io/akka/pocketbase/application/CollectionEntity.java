package io.akka.pocketbase.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.pocketbase.domain.CollectionDef;
import java.util.List;

/**
 * One collection and its rules, keyed by collection name — SPEC-001 §2.1.
 *
 * <p>Keyed by name rather than by id because that is the direction every lookup runs: a record
 * change knows which collection it belongs to by name, and the id is only ever needed to build the
 * three id-shaped topic keys, which the definition itself carries.
 */
@Component(id = "collection")
public class CollectionEntity extends KeyValueEntity<CollectionDef> {

  @Override
  public CollectionDef emptyState() {
    return new CollectionDef(null, null, null, List.of(), null, null, null);
  }

  public Effect<String> define(CollectionDef definition) {
    return effects().updateState(definition).thenReply(definition.name());
  }

  public ReadOnlyEffect<CollectionDef> get() {
    return effects().reply(currentState());
  }

  public boolean exists() {
    return currentState().name() != null;
  }
}
