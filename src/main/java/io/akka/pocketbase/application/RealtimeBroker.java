package io.akka.pocketbase.application;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of open connections on this node — SPEC-001 §4, decision D-4.
 *
 * <p>Per node, not per cluster. An open response stream is reachable only from the node holding it
 * and the SDK offers no way to write into one from elsewhere, so a multi-node deployment needs the
 * subscribe call routed to the same node as the stream. PocketBase has the same shape for the same
 * reason — one process — so this is the source's constraint kept rather than a new one introduced.
 */
public final class RealtimeBroker {

  private static final String ID_ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int ID_LENGTH = 40;

  private static final RealtimeBroker INSTANCE = new RealtimeBroker();

  private final Map<String, RealtimeClient> clients = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  /**
   * The dispatcher and the endpoint are separate components with no reference to one another, so
   * the registry they share is reached through here.
   */
  public static RealtimeBroker instance() {
    return INSTANCE;
  }

  public RealtimeClient register(String ip) {
    var client = new RealtimeClient(newId(), ip);
    clients.put(client.id(), client);
    return client;
  }

  public Optional<RealtimeClient> byId(String id) {
    return Optional.ofNullable(clients.get(id));
  }

  public List<RealtimeClient> clients() {
    return List.copyOf(clients.values());
  }

  public void unregister(String id) {
    var client = clients.remove(id);
    if (client != null) {
      client.close();
    }
  }

  private String newId() {
    var sb = new StringBuilder(ID_LENGTH);
    for (int i = 0; i < ID_LENGTH; i++) {
      sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
    }
    return sb.toString();
  }
}
