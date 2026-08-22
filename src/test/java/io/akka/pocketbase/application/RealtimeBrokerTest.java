package io.akka.pocketbase.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.pocketbase.domain.AuthIdentity;
import io.akka.pocketbase.domain.SubscriptionSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Session — S-5, and the registry the other session rules are enforced against. */
class RealtimeBrokerTest {

  @Test
  void registerAndUnregister() {
    var broker = new RealtimeBroker();
    var client = broker.register("127.0.0.1");

    assertEquals(40, client.id().length());
    assertTrue(broker.byId(client.id()).isPresent());
    assertEquals(1, broker.clients().size());

    broker.unregister(client.id());

    assertTrue(broker.byId(client.id()).isEmpty());
    assertTrue(broker.clients().isEmpty());
    assertTrue(client.isClosed());
  }

  /** S-5 — a dispatch to a client that has gone is dropped, not raised. */
  @Test
  void dispatchToAClosedClientIsDiscarded() {
    var broker = new RealtimeBroker();
    var client = broker.register("127.0.0.1");
    var seen = new CopyOnWriteArrayList<RealtimeFrame>();
    client.attach(seen::add);

    broker.unregister(client.id());

    client.send(new RealtimeFrame(client.id(), "demo2/*", new RecordPayload("create", Map.of())));

    assertTrue(seen.isEmpty());
    assertTrue(client.isClosed());
    client.close(); // closing twice is a no-op, not a failure
  }

  /** A frame offered before anything has attached is dropped rather than queued forever. */
  @Test
  void aFrameSentBeforeTheStreamIsAttachedIsDropped() {
    var broker = new RealtimeBroker();
    var client = broker.register("127.0.0.1");

    client.send(new RealtimeFrame(client.id(), "demo2/*", new RecordPayload("create", Map.of())));

    var seen = new CopyOnWriteArrayList<RealtimeFrame>();
    client.attach(seen::add);
    assertTrue(seen.isEmpty());
  }

  /** S-4 — the identity may be raised from guest once and never changed after. */
  @Test
  void authMayOnlyBeUpgradedFromGuest() {
    var client = new RealtimeClient("c1", "127.0.0.1");
    var user = new AuthIdentity("users", "u1", Map.of(), false);
    var other = new AuthIdentity("users", "u2", Map.of(), false);

    client.requireAuthUpgradeAllowed(user);
    client.setAuth(user);

    client.requireAuthUpgradeAllowed(user); // the same identity again is fine
    assertThrows(AuthChangeRejected.class, () -> client.requireAuthUpgradeAllowed(other));
    assertThrows(AuthChangeRejected.class, () -> client.requireAuthUpgradeAllowed(null));
  }

  /** S-2 — subscribing replaces rather than adds. */
  @Test
  void subscribeReplacesTheWholeSet() {
    var client = new RealtimeClient("c1", "127.0.0.1");

    client.subscribe(SubscriptionSet.of(List.of("a", "b")));
    assertEquals(2, client.subscriptions().all().size());

    client.subscribe(SubscriptionSet.of(List.of("c")));
    assertEquals(1, client.subscriptions().all().size());
    assertFalse(client.subscriptions().matching("c").isEmpty());

    client.subscribe(SubscriptionSet.of(List.of()));
    assertTrue(client.subscriptions().all().isEmpty());
  }
}
