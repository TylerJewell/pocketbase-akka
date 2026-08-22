package io.akka.pocketbase.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.pocketbase.application.RealtimeBroker;
import io.akka.pocketbase.domain.FieldDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §5 — the session rules S-2 to S-6, over HTTP with a stream held open. */
public class RealtimeSessionIntegrationTest extends TestKitSupport {

  private static final String CALLER_IP = "127.0.0.9";

  private int subscribe(String clientId, List<String> topics, String ip, String authorization) {
    var request = httpClient.POST("/api/realtime");
    if (ip != null) {
      request = request.addHeader("X-Forwarded-For", ip);
    }
    if (authorization != null) {
      request = request.addHeader("Authorization", authorization);
    }
    return request
        .withRequestBody(new RealtimeEndpoint.SubscribeRequest(clientId, topics))
        .invoke()
        .status()
        .intValue();
  }

  private int subscribeRaw(String body, String ip) {
    var request = httpClient.POST("/api/realtime");
    if (ip != null) {
      request = request.addHeader("X-Forwarded-For", ip);
    }
    return request.withRequestBody(body).invoke().status().intValue();
  }

  private void defineAuthCollection(String name) {
    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                name, name + "-id", "auth", List.of(new FieldDef("role", "text")), "", "", null))
        .invoke();
  }

  private void createRecord(String collection, String id, Map<String, Object> fields) {
    httpClient
        .POST("/api/collections/" + collection + "/records")
        .withRequestBody(new CollectionEndpoint.RecordBody(id, fields))
        .invoke();
  }

  /** S-3, first half. */
  @Test
  public void unknownClientIs404() {
    assertThat(subscribe("no-such-client", List.of("demo2/*"), CALLER_IP, null)).isEqualTo(404);
  }

  /** S-3, second half — the IP that opened the stream is the one that may change its topics. */
  @Test
  public void differentIpIs400() {
    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();

      assertThat(subscribe(clientId, List.of("demo2/*"), CALLER_IP, null)).isEqualTo(204);
      assertThat(subscribe(clientId, List.of("demo2/*"), "10.0.0.1", null)).isEqualTo(400);
    }
  }

  /** S-6. */
  @Test
  public void subscribeValidation() {
    assertThat(subscribeRaw("{\"subscriptions\":[\"demo2/*\"]}", CALLER_IP)).isEqualTo(400);

    var oversized = "a".repeat(2501);
    assertThat(subscribe("some-client", List.of(oversized), CALLER_IP, null)).isEqualTo(400);

    var tooMany = new ArrayList<String>();
    for (int i = 0; i <= 1000; i++) {
      tooMany.add("demo2/*");
    }
    assertThat(subscribe("some-client", tooMany, CALLER_IP, null)).isEqualTo(400);
  }

  /** S-2 — the second call replaces the set the first one left, and an empty list clears it. */
  @Test
  public void subscribeReplacesTheWholeSet() {
    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();

      subscribe(clientId, List.of("a", "b"), CALLER_IP, null);
      assertThat(topicsOf(clientId)).containsExactlyInAnyOrder("a", "b");

      subscribe(clientId, List.of("c"), CALLER_IP, null);
      assertThat(topicsOf(clientId)).containsExactly("c");

      subscribe(clientId, List.of(), CALLER_IP, null);
      assertThat(topicsOf(clientId)).isEmpty();
    }
  }

  /** S-4 — guest up to an identity once, and nothing after that. */
  @Test
  public void authMayOnlyBeUpgradedFromGuest() {
    defineAuthCollection("users");
    createRecord("users", "user-one", Map.of("role", "editor"));
    createRecord("users", "user-two", Map.of("role", "editor"));

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();
      var topics = List.of("demo2/*");

      assertThat(subscribe(clientId, topics, CALLER_IP, "Record users/user-one")).isEqualTo(204);
      assertThat(subscribe(clientId, topics, CALLER_IP, "Record users/user-one")).isEqualTo(204);
      assertThat(subscribe(clientId, topics, CALLER_IP, "Record users/user-two")).isEqualTo(403);
      assertThat(subscribe(clientId, topics, CALLER_IP, null)).isEqualTo(403);
    }
  }

  /** S-5 — closing the stream takes the client out of the registry. */
  @Test
  public void closingTheStreamUnregistersTheClient() {
    String clientId;
    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      clientId = session.clientId();
      assertThat(RealtimeBroker.instance().byId(clientId)).isPresent();
    }

    for (int i = 0; i < 100 && RealtimeBroker.instance().byId(clientId).isPresent(); i++) {
      SseSession.sleep(50);
    }
    assertThat(RealtimeBroker.instance().byId(clientId)).isEmpty();
    assertThat(subscribe(clientId, List.of("demo2/*"), CALLER_IP, null)).isEqualTo(404);
  }

  private List<String> topicsOf(String clientId) {
    return List.copyOf(
        RealtimeBroker.instance().byId(clientId).orElseThrow().subscriptions().all().keySet());
  }
}
