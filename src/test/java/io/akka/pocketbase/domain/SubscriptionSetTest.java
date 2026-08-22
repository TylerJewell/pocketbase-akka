package io.akka.pocketbase.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Matching — M1 to M4. */
class SubscriptionSetTest {

  private static TreeSet<String> topicsOf(Map<String, SubscriptionOptions> m) {
    return new TreeSet<>(m.keySet());
  }

  @Test
  void prefixMatchIsTerminatedByQuestionMark() {
    var set =
        SubscriptionSet.of(
            List.of(
                "demo2",
                "demo2?options={\"query\":{\"filter\":\"a=1\"}}",
                "demo2x",
                "demo2/abc",
                "demo2/*"));

    assertEquals(
        new TreeSet<>(List.of("demo2", "demo2?options={\"query\":{\"filter\":\"a=1\"}}")),
        topicsOf(set.matching("demo2")));
    assertEquals(new TreeSet<>(List.of("demo2/*")), topicsOf(set.matching("demo2/*")));
    assertEquals(new TreeSet<>(List.of("demo2/abc")), topicsOf(set.matching("demo2/abc")));
    assertEquals(new TreeSet<>(List.of("demo2x")), topicsOf(set.matching("demo2x")));
    assertTrue(set.matching("nope").isEmpty());
    assertEquals(5, set.all().size());
  }

  @Test
  void emptyTopicIsIgnored() {
    var set = SubscriptionSet.of(List.of("", "a", "b"));
    assertEquals(new TreeSet<>(List.of("a", "b")), topicsOf(set.all()));
    assertTrue(SubscriptionSet.of(List.of()).all().isEmpty());
  }

  @Test
  void brokenOptionsKeepTheSubscription() {
    var sub = "demo2?options={not json";
    var set = SubscriptionSet.of(List.of(sub));
    var options = set.all().get(sub);
    assertTrue(options != null, "a malformed options payload dropped the subscription");
    assertTrue(options.query().isEmpty());
    assertTrue(options.headers().isEmpty());
  }

  @Test
  void headerNamesAreSnakeCasedAndValuesFlattened() {
    var sub =
        "demo2/*?options={\"query\":{\"filter\":\"total>1\",\"n\":3},"
            + "\"headers\":{\"X-Token\":\"abc\",\"Another-One\":7}}";
    var options = SubscriptionSet.of(List.of(sub)).all().get(sub);

    assertEquals(Map.of("filter", "total>1", "n", "3"), options.query());
    assertEquals(Map.of("x_token", "abc", "another_one", "7"), options.headers());
  }

  @Test
  void aLongerCollectionNameDoesNotMatchAShorterKey() {
    var set = SubscriptionSet.of(List.of("demo2x/*"));
    assertTrue(set.matching("demo2").isEmpty());
    assertTrue(set.matching("demo2/*").isEmpty());
    assertFalse(set.matching("demo2x/*").isEmpty());
  }
}
