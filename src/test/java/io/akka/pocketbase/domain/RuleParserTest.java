package io.akka.pocketbase.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 Access — A6 and A10, the two rules about how a rule string is read. */
class RuleParserTest {

  private static RequestInfo guest() {
    return RequestInfo.guest(Map.of(), Map.of());
  }

  private static boolean eval(String rule, Map<String, Object> fields) {
    return Rule.parse(rule).evaluate(new RuleContext(fields, fields.keySet(), guest()));
  }

  /**
   * A6. The record is chosen so the two groupings disagree: {@code true || false && false} is true
   * when {@code &&} binds tighter and false when the joins are read left to right.
   */
  @Test
  void andBindsTighterThanOr() {
    var fields = Map.<String, Object>of("title", "test1", "active", false);

    assertTrue(eval("title = 'test1' || title = 'nope' && active = true", fields));
    assertEquals(false, eval("(title = 'test1' || title = 'nope') && active = true", fields));
    assertTrue(eval("active = true && title = 'nope' || title = 'test1'", fields));
  }

  @Test
  void lineCommentsAreStripped() {
    var fields = Map.<String, Object>of("text", "abc");
    assertTrue(eval("// a leading comment\ntext != ''\n// a trailing one", fields));
    assertTrue(eval("text = 'abc' // trailing on the same line", fields));
  }

  @Test
  void parenthesesNest() {
    var fields = Map.<String, Object>of("a", 1.0, "b", 2.0, "c", 3.0);
    assertTrue(eval("(a = 1 && (b = 2 || c = 9))", fields));
    assertEquals(false, eval("(a = 1 && (b = 8 || c = 9))", fields));
  }

  @Test
  void bothQuoteStylesAreAccepted() {
    var fields = Map.<String, Object>of("title", "it's");
    assertTrue(eval("title = \"it's\"", fields));
    assertTrue(eval("title = 'it\\'s'", fields));
  }

  @Test
  void aRuleThatDoesNotParseThrows() {
    for (var bad : new String[] {"title ==== 'x'", "title =", "= 'x'", "(title = 'x'", "&&"}) {
      assertThrows(RuleSyntaxException.class, () -> Rule.parse(bad), bad);
    }
  }

  @Test
  void anEmptyRuleStringIsNotAParsableRule() {
    assertThrows(RuleSyntaxException.class, () -> Rule.parse("   "));
    assertThrows(RuleSyntaxException.class, () -> Rule.parse("// only a comment"));
  }
}
