package io.akka.pocketbase.domain;

import java.util.List;

/**
 * A collection and the rules that gate it — SPEC-001 §2.1.
 *
 * <p>The three rules are nullable and the null is load-bearing: null means superusers only, the
 * empty string means everyone, and anything else is a rule to evaluate. A representation that
 * cannot tell an absent rule from a blank one cannot express this capability.
 */
public record CollectionDef(
    String name,
    String id,
    String type,
    List<FieldDef> fields,
    String listRule,
    String viewRule,
    String manageRule) {

  public boolean isAuth() {
    return "auth".equals(type);
  }
}
