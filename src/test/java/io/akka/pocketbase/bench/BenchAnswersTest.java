package io.akka.pocketbase.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.pocketbase.application.Delivery;
import io.akka.pocketbase.application.RealtimeClient;
import io.akka.pocketbase.application.RealtimeDispatcher;
import io.akka.pocketbase.domain.AuthIdentity;
import io.akka.pocketbase.domain.CollectionDef;
import io.akka.pocketbase.domain.FieldDef;
import io.akka.pocketbase.domain.RecordChange;
import io.akka.pocketbase.domain.RecordSnapshot;
import io.akka.pocketbase.domain.SubscriptionSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The benchmark's port side — SPEC-001 §5 read the other way round.
 *
 * <p>Runs every workload in {@code ../pocketbase-port/bench/workloads.json} through the same
 * decision the running service uses, and writes the answers in the shape
 * {@code probes/probe_05_workloads} writes them for the source, so {@code bench/compare.py} can
 * diff the two files rather than two descriptions of them.
 *
 * <p>No runtime is started. What is being compared is which subscriber hears what, and that is
 * {@link RealtimeDispatcher#plan} — the endpoints and the journal carry the answer to a socket but
 * do not decide it. The routes themselves are covered over a real socket by the three integration
 * tests.
 */
public class BenchAnswersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The same two identity records the source side resolves out of PocketBase's test fixture. */
  private static final String MEMBER_ID = "4q1xlclmfloku33";

  private static final String SUPERUSER_ID = "sywbhecnh46rhm0";

  private static final long WINDOW_FLOOR_NANOS = 50_000_000L;

  private static Path benchDir() {
    return Path.of("..", "pocketbase-port", "bench").toAbsolutePath().normalize();
  }

  // ---- the workload file, read as the source side reads it ----------------------------

  private record ClientSpec(String id, String auth, List<String> subscriptions) {}

  private record StepSpec(String action, String recordId, Map<String, Object> fields) {}

  private record Workload(
      String name,
      String sequence,
      boolean expectsDistinctAnswers,
      CollectionDef collection,
      List<String> fieldOrder,
      List<ClientSpec> clients,
      List<StepSpec> setup,
      List<StepSpec> steps,
      List<StepSpec> rows) {

    List<StepSpec> body() {
      return "arrival-orders".equals(sequence) ? rows : steps;
    }
  }

  private static List<Workload> loadWorkloads() throws IOException {
    var root = MAPPER.readTree(Files.readString(benchDir().resolve("workloads.json")));
    var out = new ArrayList<Workload>();
    for (var node : root) {
      out.add(readWorkload(node));
    }
    return out;
  }

  private static Workload readWorkload(JsonNode node) {
    var c = node.get("collection");
    var fields = new ArrayList<FieldDef>();
    var order = new ArrayList<String>();
    for (var f : c.get("fields")) {
      fields.add(new FieldDef(f.get(0).asText(), f.get(1).asText()));
      order.add(f.get(0).asText());
    }
    var collection =
        new CollectionDef(
            c.get("name").asText(),
            c.get("id").asText(),
            "base",
            fields,
            ruleOf(c, "listRule"),
            ruleOf(c, "viewRule"),
            null);

    var clients = new ArrayList<ClientSpec>();
    for (var cl : node.get("clients")) {
      var subs = new ArrayList<String>();
      cl.get("subscriptions").forEach(s -> subs.add(s.asText()));
      var auth = cl.get("auth");
      clients.add(new ClientSpec(cl.get("id").asText(), auth.isNull() ? null : auth.asText(), subs));
    }

    return new Workload(
        node.get("name").asText(),
        node.get("sequence").asText(),
        node.hasNonNull("expectsDistinctAnswers") && node.get("expectsDistinctAnswers").asBoolean(),
        collection,
        order,
        clients,
        steps(node.get("setup")),
        steps(node.get("steps")),
        steps(node.get("rows")));
  }

  /** Null and the empty string are different rules, so an absent key is not read as blank. */
  private static String ruleOf(JsonNode collection, String name) {
    var rule = collection.get(name);
    return rule == null || rule.isNull() ? null : rule.asText();
  }

  private static List<StepSpec> steps(JsonNode node) {
    var out = new ArrayList<StepSpec>();
    if (node == null) {
      return out;
    }
    for (var s : node) {
      var fields = new LinkedHashMap<String, Object>();
      var given = s.get("fields");
      if (given != null) {
        given.fields().forEachRemaining(e -> fields.put(e.getKey(), plain(e.getValue())));
      }
      out.add(new StepSpec(s.get("action").asText(), s.get("recordId").asText(), fields));
    }
    return out;
  }

  private static Object plain(JsonNode value) {
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      return value.numberValue();
    }
    return value.asText();
  }

  // ---- running one workload -----------------------------------------------------------

  private static AuthIdentity identity(String kind) {
    return switch (kind) {
      case "member" -> new AuthIdentity("users", MEMBER_ID, Map.of(), false);
      case "superuser" -> new AuthIdentity("_superusers", SUPERUSER_ID, Map.of(), true);
      default -> throw new IllegalArgumentException("unknown auth kind " + kind);
    };
  }

  private static List<RealtimeClient> clientsOf(Workload w) {
    var out = new ArrayList<RealtimeClient>();
    for (var spec : w.clients()) {
      var client = new RealtimeClient(spec.id(), "127.0.0.1");
      if (spec.auth() != null) {
        client.setAuth(identity(spec.auth()));
      }
      client.subscribe(SubscriptionSet.of(spec.subscriptions()));
      out.add(client);
    }
    return out;
  }

  /**
   * Replays a workload's steps and returns, per client, every delivery it earned.
   *
   * <p>The record store is a map because the rules need it: an update replaces the named fields and
   * leaves the rest, and a delete decides on the values the record held immediately before it went.
   */
  /** One step of a sequence and what it produced, for `toolkit/sequence_probe.py`. */
  private record OutcomeRow(String step, String outcome) {}

  private static Map<String, List<String>> run(Workload w, List<StepSpec> body) {
    return run(w, body, new ArrayList<>());
  }

  private static Map<String, List<String>> run(
      Workload w, List<StepSpec> body, List<OutcomeRow> perStep) {
    var clients = clientsOf(w);
    var store = new LinkedHashMap<String, Map<String, Object>>();

    var answers = new LinkedHashMap<String, List<String>>();
    for (var spec : w.clients()) {
      answers.put(spec.id(), new ArrayList<>());
    }

    for (var step : w.setup()) {
      applyStep(w, clients, store, step);
    }
    // Sorted within a step and appended across them, the way the source side collects: one
    // change fans out to a client's topics in an order neither system fixes, but the order the
    // changes themselves arrived in is the thing the arrival-order workloads vary.
    for (var step : body) {
      var byClient = new LinkedHashMap<String, List<String>>();
      for (var delivery : applyStep(w, clients, store, step)) {
        byClient.computeIfAbsent(delivery.clientId(), k -> new ArrayList<>())
            .add(render(w, delivery));
      }
      var outcome = new ArrayList<String>();
      for (var spec : w.clients()) {
        var delivered = byClient.getOrDefault(spec.id(), List.of());
        var sorted = new ArrayList<>(delivered);
        Collections.sort(sorted);
        for (var line : sorted) {
          outcome.add(spec.id() + ":" + line);
        }
        answers.get(spec.id()).addAll(sorted);
      }
      perStep.add(
          new OutcomeRow(
              step.action() + " " + step.recordId(),
              outcome.isEmpty() ? "none" : String.join(";", outcome)));
    }
    return answers;
  }

  private static List<Delivery> applyStep(
      Workload w,
      List<RealtimeClient> clients,
      Map<String, Map<String, Object>> store,
      StepSpec step) {

    Map<String, Object> fields;
    switch (step.action()) {
      case "create" -> {
        fields = new LinkedHashMap<>(step.fields());
        store.put(step.recordId(), fields);
      }
      case "update" -> {
        var merged = new LinkedHashMap<>(store.getOrDefault(step.recordId(), Map.of()));
        merged.putAll(step.fields());
        store.put(step.recordId(), merged);
        fields = merged;
      }
      case "delete" -> fields = store.remove(step.recordId());
      default -> throw new IllegalArgumentException("unknown action " + step.action());
    }

    var change =
        new RecordChange(
            step.action(),
            new RecordSnapshot(w.collection().name(), step.recordId(), fields));
    return RealtimeDispatcher.plan(w.collection(), change, clients);
  }

  // ---- rendering, field for field with the source side --------------------------------

  private static String render(Workload w, Delivery delivery) {
    return delivery.topic()
        + "|"
        + delivery.payload().action()
        + "|"
        + renderRecord(w, delivery.payload().record());
  }

  private static String renderRecord(Workload w, Map<String, Object> record) {
    var wanted = new ArrayList<String>();
    wanted.add("id");
    wanted.addAll(w.fieldOrder());

    var parts = new TreeSet<String>();
    for (var name : wanted) {
      if (record.containsKey(name)) {
        parts.add(name + "=" + renderValue(record.get(name)));
      }
    }
    return String.join(",", parts);
  }

  private static String renderValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Boolean b) {
      return b ? "1" : "0";
    }
    if (value instanceof Number n) {
      double d = n.doubleValue();
      return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }
    return value.toString();
  }

  // ---- arrival order ------------------------------------------------------------------

  private static List<String> distinctAnswersOverOrders(Workload w) {
    var seen = new TreeSet<String>();
    for (var order : permutations(w.body())) {
      seen.add(renderAnswer(w, run(w, order)));
    }
    return new ArrayList<>(seen);
  }

  private static String renderAnswer(Workload w, Map<String, List<String>> byClient) {
    var parts = new ArrayList<String>();
    for (var spec : w.clients()) {
      parts.add(spec.id() + ":" + String.join(";", byClient.get(spec.id())));
    }
    return String.join(" | ", parts);
  }

  private static List<List<StepSpec>> permutations(List<StepSpec> rows) {
    if (rows.size() <= 1) {
      return List.of(rows);
    }
    var out = new ArrayList<List<StepSpec>>();
    for (int i = 0; i < rows.size(); i++) {
      var rest = new ArrayList<>(rows);
      var head = rest.remove(i);
      for (var tail : permutations(rest)) {
        var one = new ArrayList<StepSpec>();
        one.add(head);
        one.addAll(tail);
        out.add(one);
      }
    }
    return out;
  }

  // ---- timing -------------------------------------------------------------------------
  //
  // A window rather than a repetition: repetitions double until the window passes the floor,
  // and the figure is the window divided by what was in it. The shapes match the source side's,
  // where the same decision can only be reached through a save and is isolated by subtracting
  // the save with nobody subscribed.

  /**
   * One figure, and how it was arrived at. {@code windows} is how many windows the figure is
   * the median of, which {@code toolkit/timing_check.py} reads: a single window is a reading,
   * not a measurement.
   */
  private record Timing(int repetitions, int windows, long windowNanos, long nanosPerRun) {}

  private static Timing time(Workload w, int clientCount, List<String> topics, String rule) {
    var collection =
        new CollectionDef(
            w.collection().name(),
            w.collection().id(),
            w.collection().type(),
            w.collection().fields(),
            rule,
            rule,
            null);
    var clients = new ArrayList<RealtimeClient>();
    for (int i = 0; i < clientCount; i++) {
      var client = new RealtimeClient("timing-" + i, "127.0.0.1");
      client.subscribe(SubscriptionSet.of(topics));
      clients.add(client);
    }
    var change =
        new RecordChange(
            "update",
            new RecordSnapshot(
                w.collection().name(),
                "timing000000001",
                Map.of("title", "timing", "active", false, "total", 1)));

    for (int i = 0; i < 20_000; i++) {
      RealtimeDispatcher.plan(collection, change, clients);
    }

    // Windows are sized until one passes the floor, then five more are run and the median
    // taken. One window on a warmed JVM still moves by a quarter run to run, and the median
    // of several is what makes two rows of this table comparable with each other; the minimum
    // is not, because it picks whichever window the platform clock under-reported.
    int repetitions = 1_000;
    while (window(collection, change, clients, repetitions) < WINDOW_FLOOR_NANOS) {
      repetitions *= 2;
    }

    var windows = new ArrayList<Long>();
    for (int i = 0; i < 5; i++) {
      windows.add(window(collection, change, clients, repetitions));
    }
    Collections.sort(windows);
    long median = windows.get(windows.size() / 2);
    return new Timing(repetitions, windows.size(), median, median / repetitions);
  }

  private static long window(
      CollectionDef collection, RecordChange change, List<RealtimeClient> clients, int repetitions) {
    long start = System.nanoTime();
    int sink = 0;
    for (int i = 0; i < repetitions; i++) {
      sink += RealtimeDispatcher.plan(collection, change, clients).size();
    }
    long elapsed = System.nanoTime() - start;
    assertThat(sink).isGreaterThanOrEqualTo(0);
    return elapsed;
  }

  private static Map<String, Object> timings(Workload w) {
    var name = w.collection().name();
    var id = w.collection().id();
    var one = List.of(name + "/*");
    var six =
        List.of(
            name, name + "/*", name + "/timing000000001",
            id, id + "/*", id + "/timing000000001");

    // The same three rule shapes the source side is measured under, so the two tables compare
    // row for row: an empty rule is answered without reading anything, and a rule with a term
    // in it is parsed and evaluated once per subscriber.
    var allowing = "title = 'timing'";
    var denying = "title = 'nothing-matches-this'";

    var shipped = new LinkedHashMap<String, Object>();
    shipped.put("no-subscribers", time(w, 0, one, ""));
    shipped.put("one-client-one-topic", time(w, 1, one, ""));
    shipped.put("one-client-six-topics", time(w, 1, six, ""));
    shipped.put("ten-clients-six-topics", time(w, 10, six, ""));

    var withTerm = new LinkedHashMap<String, Object>();
    withTerm.put("no-subscribers", time(w, 0, one, allowing));
    withTerm.put("ten-clients-six-topics", time(w, 10, six, allowing));
    withTerm.put("ten-clients-six-topics-all-denied", time(w, 10, six, denying));

    var out = new LinkedHashMap<String, Object>();
    out.put("as-shipped", shipped);
    out.put("rule-with-a-term", withTerm);
    return out;
  }

  // ---- the run ------------------------------------------------------------------------

  @Test
  public void writeAnswersAndTimings() throws IOException {
    var workloads = loadWorkloads();

    var byWorkload = new TreeMap<String, Object>();
    var orderAnswers = new TreeMap<String, Object>();
    var perStepAnswers = new TreeMap<String, Object>();

    for (var w : workloads) {
      var perStep = new ArrayList<OutcomeRow>();
      byWorkload.put(w.name(), run(w, w.body(), perStep));
      perStepAnswers.put(w.name(), perStep);

      if ("arrival-orders".equals(w.sequence())) {
        var distinct = distinctAnswersOverOrders(w);
        orderAnswers.put(w.name(), distinct);
        if (w.expectsDistinctAnswers()) {
          assertThat(distinct)
              .as("%s declares that the answer moves with delivery order", w.name())
              .hasSizeGreaterThan(1);
        } else {
          assertThat(distinct)
              .as("%s is the control and was expected to give one answer", w.name())
              .hasSize(1);
        }
      }
    }

    write(
        "answers-port.json",
        new TreeMap<>(
            Map.of(
                "runner", "pocketbase-akka (port)",
                "workloads", byWorkload,
                "orderAnswers", orderAnswers,
                "answers", perStepAnswers)));

    write(
        "port-timings.json",
        new TreeMap<>(
            Map.of("runner", "pocketbase-akka (port)", "timing", timings(workloads.get(0)))));
  }

  private static void write(String name, Object value) throws IOException {
    Files.writeString(
        benchDir().resolve(name),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
  }
}
