package io.akka.pocketbase.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.pocketbase.domain.AuthIdentity;
import io.akka.pocketbase.domain.CollectionDef;
import io.akka.pocketbase.domain.FieldDef;
import io.akka.pocketbase.domain.RecordChange;
import io.akka.pocketbase.domain.RecordSnapshot;
import io.akka.pocketbase.domain.SubscriptionSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Dispatch — D1 to D6. */
class RealtimeDispatcherTest {

  private static final String DEMO2_ID = "sz5l5z67tg7gku0";

  private static CollectionDef demo2(String listRule, String viewRule) {
    return new CollectionDef(
        "demo2",
        DEMO2_ID,
        "base",
        List.of(new FieldDef("title", "text"), new FieldDef("active", "bool")),
        listRule,
        viewRule,
        null);
  }

  private static final RecordSnapshot RECORD =
      new RecordSnapshot("demo2", "llvuca81nly1qls", Map.of("title", "test1", "active", false));

  private static RealtimeClient client(String id, AuthIdentity auth, String... subs) {
    var c = new RealtimeClient(id, "127.0.0.1");
    c.setAuth(auth);
    c.subscribe(SubscriptionSet.of(List.of(subs)));
    return c;
  }

  private static TreeSet<String> topics(List<Delivery> plan) {
    var out = new TreeSet<String>();
    plan.forEach(d -> out.add(d.topic()));
    return out;
  }

  /** D2 — six topic keys per change, three gated by the list rule and three by the view rule. */
  @Test
  void topicFormsAreGatedByListAndViewRules() {
    var guest =
        client(
            "c1",
            null,
            "demo2",
            "demo2/*",
            "demo2/" + RECORD.recordId(),
            DEMO2_ID,
            DEMO2_ID + "/*",
            DEMO2_ID + "/" + RECORD.recordId(),
            "demo2/some-other-id");

    var plan =
        RealtimeDispatcher.plan(
            demo2("", ""), new RecordChange("update", RECORD), List.of(guest));

    assertEquals(
        new TreeSet<>(
            List.of(
                "demo2",
                "demo2/*",
                "demo2/" + RECORD.recordId(),
                DEMO2_ID,
                DEMO2_ID + "/*",
                DEMO2_ID + "/" + RECORD.recordId())),
        topics(plan));

    // the view rule alone gates the single-record topics
    var viewOnly =
        RealtimeDispatcher.plan(
            demo2(null, ""), new RecordChange("update", RECORD), List.of(guest));
    assertEquals(
        new TreeSet<>(List.of("demo2/" + RECORD.recordId(), DEMO2_ID + "/" + RECORD.recordId())),
        topics(viewOnly));

    // and the list rule alone gates the collection-wide ones
    var listOnly =
        RealtimeDispatcher.plan(
            demo2("", null), new RecordChange("update", RECORD), List.of(guest));
    assertEquals(
        new TreeSet<>(List.of("demo2", "demo2/*", DEMO2_ID, DEMO2_ID + "/*")), topics(listOnly));
  }

  @Test
  void nullRulesReachOnlySuperusers() {
    var guest = client("guest", null, "demo2/*");
    var user = client("user", new AuthIdentity("users", "u1", Map.of(), false), "demo2/*");
    var superuser =
        client("su", new AuthIdentity("_superusers", "s1", Map.of(), true), "demo2/*");

    var plan =
        RealtimeDispatcher.plan(
            demo2(null, null),
            new RecordChange("update", RECORD),
            List.of(guest, user, superuser));

    assertEquals(1, plan.size());
    assertEquals("su", plan.get(0).clientId());
  }

  /** D1 — one change, two subscribers on the same topic, two different answers. */
  @Test
  void ruleIsEvaluatedPerSubscriber() {
    var guest = client("guest", null, "demo2/*");
    var user = client("user", new AuthIdentity("users", "u1", Map.of(), false), "demo2/*");

    var plan =
        RealtimeDispatcher.plan(
            demo2("@request.auth.id != ''", ""),
            new RecordChange("create", RECORD),
            List.of(guest, user));

    assertEquals(1, plan.size());
    assertEquals("user", plan.get(0).clientId());
  }

  /** D3 — the subscription's own filter narrows what the rule already allowed. */
  @Test
  void subscriptionFilterNarrowsDelivery() {
    var matching =
        client("m", null, "demo2/*?options={\"query\":{\"filter\":\"title='test1'\"}}");
    var nonMatching =
        client("n", null, "demo2/*?options={\"query\":{\"filter\":\"title='other'\"}}");

    var plan =
        RealtimeDispatcher.plan(
            demo2("", ""), new RecordChange("create", RECORD), List.of(matching, nonMatching));

    assertEquals(1, plan.size());
    assertEquals("m", plan.get(0).clientId());
  }

  /** A filter that does not parse denies, the same way an unparsable rule does. */
  @Test
  void anUnparsableSubscriptionFilterDeliversNothing() {
    var broken = client("b", null, "demo2/*?options={\"query\":{\"filter\":\"title ==== 'x'\"}}");

    var plan =
        RealtimeDispatcher.plan(demo2("", ""), new RecordChange("create", RECORD), List.of(broken));

    assertTrue(plan.isEmpty());
  }

  /** D4 — the fields option trims the record in the payload. */
  @Test
  void fieldsOptionTrimsThePayload() {
    var c = client("c", null, "demo2/*?options={\"query\":{\"fields\":\"id,title\"}}");

    var plan =
        RealtimeDispatcher.plan(demo2("", ""), new RecordChange("create", RECORD), List.of(c));

    assertEquals(1, plan.size());
    var record = plan.get(0).payload().record();
    assertEquals(new TreeSet<>(List.of("id", "title")), new TreeSet<>(record.keySet()));
  }

  /** The event name a subscriber sees is its own subscription string, options and all. */
  @Test
  void theTopicIsTheSubscriptionStringNotTheBareTopic() {
    var sub = "demo2/*?options={\"query\":{\"fields\":\"id\"}}";
    var c = client("c", null, sub);

    var plan =
        RealtimeDispatcher.plan(demo2("", ""), new RecordChange("create", RECORD), List.of(c));

    assertEquals(1, plan.size());
    assertEquals(sub, plan.get(0).topic());
  }

  /** D5 — the payload shape, and that the id is always carried. */
  @Test
  void payloadCarriesActionAndRecord() {
    var c = client("c", null, "demo2/*");

    for (var action : List.of("create", "update", "delete")) {
      var plan =
          RealtimeDispatcher.plan(demo2("", ""), new RecordChange(action, RECORD), List.of(c));
      assertEquals(1, plan.size(), action);
      assertEquals(action, plan.get(0).payload().action());
      assertEquals(RECORD.recordId(), plan.get(0).payload().record().get("id"));
      assertEquals("test1", plan.get(0).payload().record().get("title"));
    }
  }

  /** D6 — a delete decides on the values the record held immediately before it went. */
  @Test
  void deleteCarriesThePreDeleteFieldsToTheRule() {
    var c = client("c", null, "demo2/*");

    var allowed =
        RealtimeDispatcher.plan(
            demo2("title = 'test1'", ""), new RecordChange("delete", RECORD), List.of(c));
    assertEquals(1, allowed.size());
    assertEquals("test1", allowed.get(0).payload().record().get("title"));

    var denied =
        RealtimeDispatcher.plan(
            demo2("title = 'something-else'", ""), new RecordChange("delete", RECORD), List.of(c));
    assertTrue(denied.isEmpty());
  }

  /** M1, seen from the dispatch side: a longer collection name is not a prefix match. */
  @Test
  void aSubscriberOnALongerCollectionNameHearsNothing() {
    var c = client("c", null, "demo2x/*", "demo2x");

    var plan =
        RealtimeDispatcher.plan(demo2("", ""), new RecordChange("create", RECORD), List.of(c));

    assertTrue(plan.isEmpty());
  }
}
