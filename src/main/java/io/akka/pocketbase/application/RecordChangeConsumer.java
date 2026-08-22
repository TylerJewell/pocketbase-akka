package io.akka.pocketbase.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.pocketbase.domain.RecordChange;
import io.akka.pocketbase.domain.RecordEvent;
import io.akka.pocketbase.domain.RecordSnapshot;

/**
 * Turns a record's journal into subscriber messages — SPEC-001 §3 Dispatch.
 *
 * <p>This sits where the source's `OnModelAfterCreateSuccess` / `AfterUpdateSuccess` /
 * `AfterDeleteSuccess` hooks sit, and reaches the same place from a shorter route: because an event
 * exists only if the change committed, there is nothing here corresponding to the source's
 * pre-delete cache and its two extra hooks for releasing and discarding it.
 */
@Component(id = "record-change-consumer")
@Consume.FromEventSourcedEntity(RecordEntity.class)
public class RecordChangeConsumer extends Consumer {

  private final ComponentClient componentClient;

  public RecordChangeConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(RecordEvent event) {
    var action =
        switch (event) {
          case RecordEvent.Created ignored -> "create";
          case RecordEvent.Updated ignored -> "update";
          case RecordEvent.Deleted ignored -> "delete";
        };

    var collection =
        componentClient
            .forKeyValueEntity(event.collectionName())
            .method(CollectionEntity::get)
            .invoke();

    if (collection.name() == null) {
      // A change in a collection nobody has defined has no rules to gate it, and a rule that
      // cannot be read denies (A5). Dropping it is the same answer, reached without a dispatch.
      return effects().done();
    }

    var change =
        new RecordChange(
            action,
            new RecordSnapshot(event.collectionName(), event.recordId(), event.fields()));

    var broker = RealtimeBroker.instance();
    for (var delivery : RealtimeDispatcher.plan(collection, change, broker.clients())) {
      broker
          .byId(delivery.clientId())
          .ifPresent(
              client ->
                  client.send(
                      new RealtimeFrame(
                          delivery.clientId(), delivery.topic(), delivery.payload())));
    }
    return effects().done();
  }
}
