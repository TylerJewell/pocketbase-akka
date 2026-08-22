package io.akka.pocketbase.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Access — A1 to A5 and A11, the decision around the rule rather than inside it. */
class AccessDecisionTest {

  private static final CollectionDef DEMO2 =
      new CollectionDef(
          "demo2",
          "sz5l5z67tg7gku0",
          "base",
          List.of(
              new FieldDef("title", "text"),
              new FieldDef("active", "bool")),
          "",
          "",
          null);

  private static final RecordSnapshot RECORD =
      new RecordSnapshot("demo2", "llvuca81nly1qls", Map.of("title", "test1", "active", false));

  private static RequestInfo guest() {
    return RequestInfo.guest(Map.of(), Map.of());
  }

  private static RequestInfo asUser() {
    return RequestInfo.authenticated(
        new AuthIdentity("users", "4q1xlclmfloku33", Map.of("verified", true), false),
        Map.of(),
        Map.of());
  }

  private static RequestInfo asSuperuser() {
    return RequestInfo.authenticated(
        new AuthIdentity("_superusers", "sywbhecnh46rhm0", Map.of(), true), Map.of(), Map.of());
  }

  private static boolean can(RequestInfo info, String rule) {
    return AccessDecision.allows(DEMO2, RECORD, info, rule);
  }

  @Test
  void superuserIsAllowedBeforeTheRuleIsRead() {
    assertTrue(can(asSuperuser(), null));
    assertTrue(can(asSuperuser(), ""));
    assertTrue(can(asSuperuser(), "title = 'nope'"));
    assertTrue(can(asSuperuser(), "this is not a rule at all"));
  }

  @Test
  void nullRuleDeniesEveryoneElse() {
    assertFalse(can(guest(), null));
    assertFalse(can(asUser(), null));
  }

  @Test
  void emptyRuleAllowsEveryone() {
    assertTrue(can(guest(), ""));
    assertTrue(can(asUser(), ""));
  }

  @Test
  void ruleSeesTheRecordAndTheRequest() {
    assertFalse(can(guest(), "@request.auth.id != ''"));
    assertTrue(can(asUser(), "@request.auth.id != ''"));
    assertTrue(can(asUser(), "@request.auth.collectionName = 'users'"));
    assertTrue(can(guest(), "title = 'test1'"));
    assertFalse(can(guest(), "title = 'nope'"));
    assertTrue(can(guest(), "active = false"));
    assertFalse(can(guest(), "active = true"));
  }

  @Test
  void ruleSeesTheSubscriptionQueryAndHeaders() {
    var info =
        RequestInfo.guest(Map.of("tenant", "acme"), Map.of("x_token", "abc"));

    assertTrue(AccessDecision.allows(DEMO2, RECORD, info, "@request.query.tenant = 'acme'"));
    assertFalse(AccessDecision.allows(DEMO2, RECORD, info, "@request.query.tenant = 'other'"));
    assertTrue(AccessDecision.allows(DEMO2, RECORD, info, "@request.query.missing = ''"));
    assertTrue(AccessDecision.allows(DEMO2, RECORD, info, "@request.headers.x_token = 'abc'"));
    assertFalse(AccessDecision.allows(DEMO2, RECORD, info, "@request.headers.x_token = 'zzz'"));
    assertTrue(AccessDecision.allows(DEMO2, RECORD, info, "@request.context = 'realtime'"));
    assertTrue(AccessDecision.allows(DEMO2, RECORD, info, "@request.method = 'GET'"));
  }

  @Test
  void unparsableRuleDenies() {
    assertFalse(can(guest(), "title ==== 'x'"));
    assertFalse(can(asUser(), "title ==== 'x'"));
  }

  @Test
  void unknownFieldDenies() {
    assertFalse(can(guest(), "missingField = 'x'"));
    assertFalse(can(guest(), "@request.nope.id = 'x'"));
  }

  /** A11 — deliberately not symmetric with the record-field case above. */
  @Test
  void unknownAuthFieldReadsAsAbsent() {
    assertTrue(can(asUser(), "@request.auth.nosuchfield = ''"));
    assertTrue(can(guest(), "@request.auth.nosuchfield = ''"));
    assertFalse(can(asUser(), "@request.auth.nosuchfield != ''"));
  }

  /** A field the collection declares but this record does not carry is not an unknown field. */
  @Test
  void aDeclaredFieldTheRecordDoesNotCarryIsAbsentRatherThanUnknown() {
    var sparse = new RecordSnapshot("demo2", "x", Map.of("title", "only"));
    assertTrue(AccessDecision.allows(DEMO2, sparse, guest(), "active = ''"));
    assertFalse(AccessDecision.allows(DEMO2, sparse, guest(), "active = true"));
  }
}
