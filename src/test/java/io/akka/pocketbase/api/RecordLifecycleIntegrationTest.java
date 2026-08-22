package io.akka.pocketbase.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.pocketbase.domain.FieldDef;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §5 — the lifecycle rules that only a sequence exhibits.
 *
 * <p>D7 in particular: no single event shows it. A create, a delete that does not commit and an
 * update, in that order, are what separate "the failed delete broadcast nothing" from "nothing was
 * dispatched at all".
 */
public class RecordLifecycleIntegrationTest extends TestKitSupport {

  private static final String CALLER_IP = "127.0.0.9";

  private void defineCollection(String name) {
    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                name,
                name + "-id",
                "base",
                List.of(new FieldDef("title", "text")),
                "",
                "",
                null))
        .invoke();
  }

  private void subscribe(String clientId, List<String> topics) {
    httpClient
        .POST("/api/realtime")
        .addHeader("X-Forwarded-For", CALLER_IP)
        .withRequestBody(new RealtimeEndpoint.SubscribeRequest(clientId, topics))
        .invoke();
  }

  private int createRecord(String collection, String id, Map<String, Object> fields) {
    return httpClient
        .POST("/api/collections/" + collection + "/records")
        .withRequestBody(new CollectionEndpoint.RecordBody(id, fields))
        .invoke()
        .status()
        .intValue();
  }

  private int updateRecord(String collection, String id, Map<String, Object> fields) {
    return httpClient
        .PATCH("/api/collections/" + collection + "/records/" + id)
        .withRequestBody(new CollectionEndpoint.RecordBody(id, fields))
        .invoke()
        .status()
        .intValue();
  }

  /** D5, over the wire: one record's three lifecycle events, in order, on one stream. */
  @Test
  public void createUpdateAndDeleteArriveInOrder() {
    defineCollection("notes");

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      subscribe(session.clientId(), List.of("notes/*"));

      assertThat(createRecord("notes", "note-one", Map.of("title", "first"))).isEqualTo(201);
      assertThat(updateRecord("notes", "note-one", Map.of("title", "second"))).isEqualTo(200);
      httpClient.DELETE("/api/collections/notes/records/note-one").invoke();

      var frames = session.awaitFrames(4, Duration.ofSeconds(20));
      assertThat(frames).hasSize(4);
      assertThat(frames.get(1).data()).contains("\"action\":\"create\"");
      assertThat(frames.get(2).data()).contains("\"action\":\"update\"");
      assertThat(frames.get(3).data()).contains("\"action\":\"delete\"");

      // D6 — the delete carries the values the record held on the way out
      assertThat(frames.get(3).data()).contains("\"title\":\"second\"");
    }
  }

  /**
   * D7 — a delete that does not commit broadcasts nothing, and leaves the record broadcasting
   * normally afterwards.
   *
   * <p>The delete is made to fail by aiming it at the wrong collection, which is a route the port
   * has and the source does not; what is being checked is not that route but the property it
   * exercises, which the source reaches by a hook returning an error.
   */
  @Test
  public void failedDeleteBroadcastsNothing() {
    defineCollection("items");
    defineCollection("others");

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      subscribe(session.clientId(), List.of("items/*"));

      assertThat(createRecord("items", "item-one", Map.of("title", "kept"))).isEqualTo(201);
      assertThat(session.awaitFrames(2, Duration.ofSeconds(20))).hasSize(2);

      var failed = httpClient.DELETE("/api/collections/others/records/item-one").invoke();
      assertThat(failed.status().isSuccess()).isFalse();
      assertThat(session.framesAfterSettling()).hasSize(2);

      assertThat(updateRecord("items", "item-one", Map.of("title", "still here")))
          .isEqualTo(200);
      var frames = session.awaitFrames(3, Duration.ofSeconds(20));
      assertThat(frames).hasSize(3);
      assertThat(frames.get(2).data()).contains("\"action\":\"update\"");
      assertThat(frames.get(2).data()).contains("\"title\":\"still here\"");
    }
  }

  /** D1 over the wire: two subscribers, one change, different answers from the same rule. */
  @Test
  public void anAuthOnlyRuleReachesOneOfTwoSubscribers() {
    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                "members", "members-id", "auth", List.of(new FieldDef("role", "text")), "", "",
                null))
        .invoke();
    createRecord("members", "member-one", Map.of("role", "editor"));

    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                "private",
                "private-id",
                "base",
                List.of(new FieldDef("title", "text")),
                "@request.auth.id != ''",
                "",
                null))
        .invoke();

    try (var guest = new SseSession(testKit.getPort(), CALLER_IP);
        var authed = new SseSession(testKit.getPort(), CALLER_IP)) {

      subscribe(guest.clientId(), List.of("private/*"));
      httpClient
          .POST("/api/realtime")
          .addHeader("X-Forwarded-For", CALLER_IP)
          .addHeader("Authorization", "Record members/member-one")
          .withRequestBody(
              new RealtimeEndpoint.SubscribeRequest(authed.clientId(), List.of("private/*")))
          .invoke();

      assertThat(createRecord("private", "secret-one", Map.of("title", "hidden")))
          .isEqualTo(201);

      assertThat(authed.awaitFrames(2, Duration.ofSeconds(20))).hasSize(2);
      assertThat(guest.framesAfterSettling()).hasSize(1);
    }
  }

  /** The wire frame's own shape, recorded rather than assumed — SPEC-001 §4, decision D-1. */
  @Test
  public void theWireFrameCarriesIdEventAndData() {
    defineCollection("shapes");

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();
      subscribe(clientId, List.of("shapes/*"));
      assertThat(createRecord("shapes", "shape-one", Map.of("title", "square"))).isEqualTo(201);

      var frames = session.awaitFrames(2, Duration.ofSeconds(20));
      var lines = frames.get(1).rawLines();

      assertThat(lines).hasSize(3);
      assertThat(lines).anyMatch(l -> l.equals("id:" + clientId));
      assertThat(lines).anyMatch(l -> l.equals("event:shapes/*"));
      assertThat(lines).anyMatch(l -> l.startsWith("data:{"));
    }
  }

  /**
   * The collection route reports the rules it was given, including the difference between a rule
   * that is absent and one that is blank — SPEC-001 §2.1.
   */
  @Test
  public void theCollectionRouteReportsItsRulesAndTellsNullFromBlank() {
    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                "reported",
                "reported-id",
                "base",
                List.of(new FieldDef("title", "text")),
                "",
                null,
                null))
        .invoke();

    var view =
        httpClient
            .GET("/api/collections/reported")
            .responseBodyAs(CollectionEndpoint.CollectionView.class)
            .invoke()
            .body();

    assertThat(view.name()).isEqualTo("reported");
    assertThat(view.id()).isEqualTo("reported-id");
    assertThat(view.listRule()).isEqualTo("");
    assertThat(view.viewRule()).isNull();
    assertThat(view.fields()).containsExactly(new FieldDef("title", "text"));

    assertThat(httpClient.GET("/api/collections/absent").invoke().status().intValue())
        .isEqualTo(404);
  }

  /**
   * A write aimed at a record that is not there and one aimed at a record that already is are
   * different answers, rather than the one status an unmapped entity refusal would give both.
   */
  @Test
  public void aMissingRecordIs404AndADuplicateIs400() {
    defineCollection("statuses");

    assertThat(updateRecord("statuses", "never-made", Map.of("title", "x"))).isEqualTo(404);
    assertThat(
            httpClient.DELETE("/api/collections/statuses/records/never-made").invoke().status()
                .intValue())
        .isEqualTo(404);

    assertThat(createRecord("statuses", "made-once", Map.of("title", "x"))).isEqualTo(201);
    assertThat(createRecord("statuses", "made-once", Map.of("title", "y"))).isEqualTo(400);
  }
}
