package io.akka.pocketbase.domain;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether one caller may see one record under one rule — SPEC-001 §3 Access, A1 to A5.
 *
 * <p>The three answers that need no rule read at all come first: a superuser is allowed before
 * anything is parsed, a null rule denies everyone else, and an empty rule allows everyone. Only
 * then is there a rule to evaluate.
 */
public final class AccessDecision {

  private AccessDecision() {}

  public static boolean allows(
      CollectionDef collection, RecordSnapshot record, RequestInfo request, String rule) {
    return allows(view(collection, record), request, rule);
  }

  public static boolean allows(RecordView view, RequestInfo request, String rule) {
    if (request.isSuperuser()) {
      return true; // A1
    }
    if (rule == null) {
      return false; // A2
    }
    if (rule.isEmpty()) {
      return true; // A3
    }
    return matches(view, request, rule);
  }

  /**
   * Evaluates a rule with no superuser or null-rule shortcut in front of it — the subscription
   * filter path, which narrows what a rule already allowed rather than deciding on its own.
   *
   * <p>A rule that cannot be read denies (A5). It is caught here rather than left to the caller
   * because the alternative — an exception escaping into a dispatch loop — would drop the rest of
   * that dispatch, turning one client's bad filter into everybody's missing message.
   */
  public static boolean matches(
      CollectionDef collection, RecordSnapshot record, RequestInfo request, String rule) {
    return matches(view(collection, record), request, rule);
  }

  public static boolean matches(RecordView view, RequestInfo request, String rule) {
    try {
      var context = new RuleContext(view.fields(), view.declaredFields(), request);
      return Rule.parse(rule).evaluate(context);
    } catch (RuleSyntaxException | UnknownIdentifierException e) {
      return false;
    }
  }

  /**
   * One record as every rule in one dispatch sees it: the field values with the id alongside, and
   * the collection's declared names.
   *
   * <p>Neither half depends on who is asking, so both are derived once per record change rather
   * than once per subscription — one dispatch asks the same question of every open subscriber, and
   * rebuilding the map and the name set for each of them was the larger part of the work.
   *
   * <p>The field map is unmodifiable because one instance is now read by every rule in a dispatch
   * and reaches every payload it produces.
   */
  public record RecordView(Map<String, Object> fields, Set<String> declaredFields) {}

  public static RecordView view(CollectionDef collection, RecordSnapshot record) {
    return new RecordView(
        Collections.unmodifiableMap(record.withId()), declaredNames(collection));
  }

  private static Set<String> declaredNames(CollectionDef collection) {
    var names = new HashSet<String>();
    names.add("id");
    collection.fields().forEach(f -> names.add(f.name()));
    return names;
  }
}
