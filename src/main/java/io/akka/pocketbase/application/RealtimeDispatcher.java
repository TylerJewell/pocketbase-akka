package io.akka.pocketbase.application;

import io.akka.pocketbase.domain.AccessDecision;
import io.akka.pocketbase.domain.CollectionDef;
import io.akka.pocketbase.domain.RecordChange;
import io.akka.pocketbase.domain.RequestInfo;
import io.akka.pocketbase.domain.SubscriptionOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who hears about a record change, and what they are told — SPEC-001 §3 Dispatch.
 *
 * <p>Planning is separated from sending because the decision is the capability and the stream is
 * not: a plan can be compared against the source's answer for the same inputs without a connection
 * in the way.
 */
public final class RealtimeDispatcher {

  private static final String FILTER = "filter";
  private static final String FIELDS = "fields";

  private RealtimeDispatcher() {}

  public static List<Delivery> plan(
      CollectionDef collection, RecordChange change, List<RealtimeClient> clients) {
    var record = change.record();
    var topicRules = topicRules(collection, record.recordId());
    var view = AccessDecision.view(collection, record);

    var plan = new ArrayList<Delivery>();
    for (var client : clients) {
      for (var topicRule : topicRules.entrySet()) {
        var matches = client.subscriptions().matching(topicRule.getKey());
        for (var subscription : matches.entrySet()) {
          var delivery =
              deliveryFor(
                  view,
                  change.action(),
                  client,
                  subscription.getKey(),
                  subscription.getValue(),
                  topicRule.getValue());
          if (delivery != null) {
            plan.add(delivery);
          }
        }
      }
    }
    return plan;
  }

  /**
   * The six keys one record change is announced under, and the rule each is gated by — SPEC-001 D2.
   *
   * <p>A collection is addressable by name and by id, and a subscriber may name the record, the
   * wildcard, or the collection alone; the bare form is the deprecated one and is kept because
   * removing it would silently stop delivering to clients that still use it.
   */
  private static Map<String, String> topicRules(CollectionDef collection, String recordId) {
    var rules = new LinkedHashMap<String, String>();
    rules.put(collection.name() + "/" + recordId, collection.viewRule());
    rules.put(collection.id() + "/" + recordId, collection.viewRule());
    rules.put(collection.name() + "/*", collection.listRule());
    rules.put(collection.id() + "/*", collection.listRule());
    rules.put(collection.name(), collection.listRule());
    rules.put(collection.id(), collection.listRule());
    return rules;
  }

  /** Null where this subscriber hears nothing about this change. */
  private static Delivery deliveryFor(
      AccessDecision.RecordView view,
      String action,
      RealtimeClient client,
      String subscription,
      SubscriptionOptions options,
      String rule) {

    var request = requestFor(client, options);

    if (!AccessDecision.allows(view, request, rule)) {
      return null;
    }
    var filter = options.query().get(FILTER);
    if (filter != null && !filter.isEmpty() && !AccessDecision.matches(view, request, filter)) {
      return null; // D3
    }

    var payload = new RecordPayload(action, pick(view.fields(), options.query().get(FIELDS)));
    return new Delivery(client.id(), subscription, payload);
  }

  private static RequestInfo requestFor(RealtimeClient client, SubscriptionOptions options) {
    return client.auth() == null
        ? RequestInfo.guest(options.query(), options.headers())
        : RequestInfo.authenticated(client.auth(), options.query(), options.headers());
  }

  /** D4 — a name the record does not carry is skipped rather than emitted as a null. */
  private static Map<String, Object> pick(Map<String, Object> full, String fields) {
    if (fields == null || fields.isBlank()) {
      return full;
    }
    var out = new LinkedHashMap<String, Object>();
    for (var name : fields.split(",")) {
      var trimmed = name.trim();
      if (full.containsKey(trimmed)) {
        out.put(trimmed, full.get(trimmed));
      }
    }
    return out;
  }
}
