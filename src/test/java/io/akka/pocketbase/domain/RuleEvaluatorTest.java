package io.akka.pocketbase.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Access — A7, A8 and A9, the comparison semantics. */
class RuleEvaluatorTest {

  private static boolean eval(String rule, Map<String, Object> fields, Set<String> declared) {
    return Rule.parse(rule)
        .evaluate(new RuleContext(fields, declared, RequestInfo.guest(Map.of(), Map.of())));
  }

  private static boolean eval(String rule, Map<String, Object> fields) {
    return eval(rule, fields, fields.keySet());
  }

  /** A7. The wrapping is conditional on the pattern not carrying its own unescaped %. */
  @Test
  void containsWrapsUnlessThePatternHasAPercent() {
    var fields = Map.<String, Object>of("title", "test1");

    assertTrue(eval("title ~ 'est'", fields));
    assertTrue(eval("title ~ 'test1'", fields));
    assertFalse(eval("title ~ 'zzz'", fields));
    assertTrue(eval("title !~ 'zzz'", fields));

    // an explicit % suppresses the auto-wrapping, so the pattern anchors at the start
    assertFalse(eval("title ~ 'es%'", fields));
    assertTrue(eval("title ~ 'te%'", fields));
  }

  /**
   * A7 — "_" is a wildcard only on the verbatim path. The auto-wrapping escapes it along with
   * "%", so a pattern gets wildcards at all only by opting out of the wrapping.
   */
  @Test
  void underscoreIsAWildcardOnlyWhenTheWrappingIsSuppressed() {
    var fields = Map.<String, Object>of("title", "test1", "pct", "50% off");

    assertFalse(eval("title ~ 'te_t'", fields));
    assertTrue(eval("title ~ 'te_t1%'", fields));
    assertTrue(eval("title ~ 'te%t1'", fields));
    assertTrue(eval("pct ~ '50\\% off'", fields));
  }

  /** A8. Absent and empty are the same value to a rule, in both directions. */
  @Test
  void absentAndEmptyCompareEqual() {
    var blank = new HashMap<String, Object>();
    blank.put("text", "");
    var declared = Set.of("text");

    assertTrue(eval("text = ''", blank, declared));
    assertTrue(eval("text = null", blank, declared));
    assertFalse(eval("text != ''", blank, declared));
    assertFalse(eval("text != null", blank, declared));
    assertTrue(eval("text ~ ''", blank, declared));

    // the same, for a field the record simply does not carry
    var missing = new HashMap<String, Object>();
    assertTrue(eval("text = ''", missing, declared));
    assertTrue(eval("text = null", missing, declared));
  }

  @Test
  void guestAuthIsBothEmptyAndNull() {
    var fields = Map.<String, Object>of("title", "x");

    assertTrue(eval("@request.auth.id = ''", fields));
    assertTrue(eval("@request.auth.id = null", fields));
    assertFalse(eval("@request.auth.id != ''", fields));
  }

  /** A9. Numeric when both sides are, lexicographic otherwise; booleans compare as 1 and 0. */
  @Test
  void orderingIsNumericWhenBothSidesAre() {
    var fields = Map.<String, Object>of("number", 9.0);

    assertTrue(eval("number > 8", fields));
    assertTrue(eval("number >= 9", fields));
    assertFalse(eval("number < 8", fields));
    assertTrue(eval("number <= 9", fields));
    assertTrue(eval("number < 10", fields));
  }

  /** A9 — "10" sorts before "9" as text; a text field takes the text answer. */
  @Test
  void aTextFieldOrdersAsTextEvenWhenItLooksNumeric() {
    var fields = Map.<String, Object>of("title", "test1", "label", "9");

    assertFalse(eval("title < '10'", fields));
    assertTrue(eval("title > '10'", fields));
    assertFalse(eval("label < '10'", fields));
    assertTrue(eval("label > '10'", fields));
  }

  /** A7 — the two operators disagree about case, and that is not a typo in either. */
  @Test
  void containsIsCaseInsensitiveWhileEqualsIsNot() {
    var fields = Map.<String, Object>of("title", "test1");

    assertTrue(eval("title ~ 'EST'", fields));
    assertTrue(eval("title ~ 'TEST1'", fields));
    assertFalse(eval("title = 'TEST1'", fields));
  }

  @Test
  void booleansCompareAsOneAndZero() {
    var fields = Map.<String, Object>of("active", true, "off", false);

    assertTrue(eval("active = true", fields));
    assertFalse(eval("active = false", fields));
    assertTrue(eval("off = false", fields));
    assertTrue(eval("active = 1", fields));
    assertTrue(eval("off = 0", fields));
    assertTrue(eval("active > off", fields));
  }

  @Test
  void aNumericFieldComparesEqualToItsNumericLiteral() {
    var fields = Map.<String, Object>of("total", 3.0);
    assertTrue(eval("total = 3", fields));
    assertEquals(false, eval("total = 4", fields));
    assertTrue(eval("total != 4", fields));
  }
}
