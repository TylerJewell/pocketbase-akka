package io.akka.pocketbase.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.pocketbase.domain.FieldDef;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §5 — what a caller outside a test can actually reach: open a stream, be told who you
 * are, subscribe, and hear about a change.
 */
public class RealtimeStreamIntegrationTest extends TestKitSupport {

  private static final String CALLER_IP = "127.0.0.9";

  private void defineCollection(String name, String listRule, String viewRule) {
    httpClient
        .POST("/api/collections")
        .withRequestBody(
            new CollectionEndpoint.DefineCollection(
                name,
                name + "-id",
                "base",
                List.of(new FieldDef("title", "text"), new FieldDef("active", "bool")),
                listRule,
                viewRule,
                null))
        .invoke();
  }

  private void subscribe(String clientId, List<String> topics) {
    var response =
        httpClient
            .POST("/api/realtime")
            .addHeader("X-Forwarded-For", CALLER_IP)
            .withRequestBody(new RealtimeEndpoint.SubscribeRequest(clientId, topics))
            .invoke();
    assertThat(response.status().intValue()).isEqualTo(204);
  }

  private void createRecord(String collection, String id, Map<String, Object> fields) {
    httpClient
        .POST("/api/collections/" + collection + "/records")
        .withRequestBody(new CollectionEndpoint.RecordBody(id, fields))
        .invoke();
  }

  /** S-1 — the first frame names the client, and is keyed by that same name. */
  @Test
  public void connectAnnouncesTheClientIdOnTheStream() {
    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var frames = session.awaitFrames(1, Duration.ofSeconds(15));

      assertThat(frames).hasSize(1);
      var first = frames.get(0);
      assertThat(first.event()).isEqualTo("PB_CONNECT");
      assertThat(first.data()).contains("\"clientId\":\"");
      assertThat(first.id()).isEqualTo(session.clientId());
      assertThat(first.id()).hasSize(40);
    }
  }

  /** D-4/T2 — a change written after the stream opened arrives on it. */
  @Test
  public void recordChangeReachesAnOpenStream() {
    defineCollection("posts", "", "");

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();
      subscribe(clientId, List.of("posts/*"));

      createRecord("posts", "post-one", Map.of("title", "hello", "active", true));

      var frames = session.awaitFrames(2, Duration.ofSeconds(15));
      assertThat(frames).hasSize(2);

      var change = frames.get(1);
      assertThat(change.event()).isEqualTo("posts/*");
      assertThat(change.id()).isEqualTo(clientId);
      assertThat(change.data()).contains("\"action\":\"create\"");
      assertThat(change.data()).contains("\"title\":\"hello\"");
      assertThat(change.data()).contains("\"id\":\"post-one\"");
    }
  }

  /** D1/D2 over the wire: a null view rule silences the single-record topic for a guest. */
  @Test
  public void aNullRuleSilencesItsTopicOnAnOpenStream() {
    defineCollection("guarded", "", null);

    try (var session = new SseSession(testKit.getPort(), CALLER_IP)) {
      var clientId = session.clientId();
      subscribe(clientId, List.of("guarded/rec-one", "guarded/*"));

      createRecord("guarded", "rec-one", Map.of("title", "hi", "active", false));

      var frames = session.awaitFrames(2, Duration.ofSeconds(15));
      assertThat(frames).hasSize(2);
      assertThat(frames.get(1).event()).isEqualTo("guarded/*");

      // and nothing further arrives for the view-gated topic
      assertThat(session.framesAfterSettling()).hasSize(2);
    }
  }
}
