package io.akka.pocketbase.application;

import io.akka.pocketbase.domain.AuthIdentity;
import io.akka.pocketbase.domain.SubscriptionSet;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One open realtime connection — SPEC-001 §3 Session.
 *
 * <p>Holds the three things a subscribe call is checked against (the id it was told, the IP it came
 * from, the identity it currently has) and the sink its frames go to. The sink is attached after
 * construction because the client id has to exist before the stream that carries it can be built:
 * the first frame on that stream announces the id.
 */
public final class RealtimeClient {

  private final String id;
  private final String ip;

  private volatile AuthIdentity auth;
  private volatile SubscriptionSet subscriptions = SubscriptionSet.empty();
  private volatile Consumer<RealtimeFrame> sink;
  private volatile boolean closed;

  public RealtimeClient(String id, String ip) {
    this.id = id;
    this.ip = ip;
  }

  public String id() {
    return id;
  }

  public String ip() {
    return ip;
  }

  public AuthIdentity auth() {
    return auth;
  }

  public void setAuth(AuthIdentity auth) {
    this.auth = auth;
  }

  public SubscriptionSet subscriptions() {
    return subscriptions;
  }

  /** S-2: a subscribe call replaces the whole set rather than adding to it. */
  public void subscribe(SubscriptionSet next) {
    this.subscriptions = next;
  }

  public void attach(Consumer<RealtimeFrame> sink) {
    this.sink = sink;
  }

  /**
   * S-5: a frame for a client that has gone, or has not yet attached its stream, is dropped.
   *
   * <p>Silently, and on purpose — one departed subscriber must not fail the dispatch that the
   * remaining subscribers are also in.
   */
  public void send(RealtimeFrame frame) {
    var target = sink;
    if (closed || target == null) {
      return;
    }
    try {
      target.accept(frame);
    } catch (RuntimeException e) {
      close();
    }
  }

  public boolean isClosed() {
    return closed;
  }

  /** Idempotent: unregistering a client that a closing stream has already closed is normal. */
  public void close() {
    closed = true;
    sink = null;
  }

  /**
   * S-4: guest may be raised to an identity once; any later change is refused.
   *
   * <p>Refusing a change back to guest as well is deliberate — a client that could drop its
   * identity could re-acquire a different one on the next call, which is the case this rule exists
   * to close.
   */
  public void requireAuthUpgradeAllowed(AuthIdentity next) {
    var current = auth;
    if (current == null) {
      return;
    }
    if (next == null
        || !Objects.equals(current.id(), next.id())
        || !Objects.equals(current.collectionName(), next.collectionName())) {
      throw new AuthChangeRejected(
          "the current and the previous request authorization don't match");
    }
  }
}
