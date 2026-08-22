package io.akka.pocketbase.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A parsed access rule — SPEC-001 §3 Access.
 *
 * <p>Grammar, smallest to largest:
 *
 * <pre>
 *   rule       := or
 *   or         := and ( "||" and )*
 *   and        := primary ( "&amp;&amp;" primary )*
 *   primary    := "(" rule ")" | comparison
 *   comparison := operand OP operand
 *   operand    := identifier | string | number
 * </pre>
 *
 * <p>The nesting is where {@code &amp;&amp;} binding tighter than {@code ||} lives (A6). The source
 * reaches the same grouping by emitting the joins flat and letting SQL's precedence apply, which is
 * the same answer by a different route — and the route matters only if you try to read the grouping
 * off the source's own structure, where it is not written down.
 */
public final class Rule {

  private static final int PARSE_CACHE_LIMIT = 1024;
  private static final Map<String, Outcome> PARSED = new ConcurrentHashMap<>();

  private final Node root;

  private Rule(Node root) {
    this.root = root;
  }

  /**
   * Parses a rule, reusing an earlier parse of the same text.
   *
   * <p>A rule string is evaluated once per subscription per record change, and the same handful of
   * strings — a collection's own rules, and whatever filters its subscribers wrote — recur on every
   * dispatch, so lexing and parsing them again each time is the cost of the whole decision several
   * times over.
   *
   * <p>The cache holds outcomes rather than results: a rule that does not parse denies (A5) and
   * would otherwise be re-lexed on every change for as long as the subscription lives.
   *
   * <p>Bounded, and cleared rather than evicted when it fills. The keys are caller-supplied — a
   * subscription filter is any string a client sends — so an unbounded map is reachable from
   * outside; and the working set is small enough that a coarse reset costs one re-parse per live
   * rule.
   */
  public static Rule parse(String rule) {
    var cached = PARSED.get(rule);
    if (cached == null) {
      cached = Outcome.of(rule);
      if (PARSED.size() >= PARSE_CACHE_LIMIT) {
        PARSED.clear();
      }
      PARSED.put(rule, cached);
    }
    return cached.value();
  }

  private static Rule compile(String rule) {
    var tokens = Lexer.tokenize(rule);
    if (tokens.isEmpty()) {
      throw new RuleSyntaxException("empty rule");
    }
    var parser = new Parser(tokens);
    var node = parser.parseRule();
    parser.expectEnd();
    return new Rule(node);
  }

  /** A parse that succeeded, or the syntax error it failed with. */
  private record Outcome(Rule rule, RuleSyntaxException error) {

    static Outcome of(String text) {
      try {
        return new Outcome(compile(text), null);
      } catch (RuleSyntaxException e) {
        return new Outcome(null, e);
      }
    }

    Rule value() {
      if (error != null) {
        throw error;
      }
      return rule;
    }
  }

  public boolean evaluate(RuleContext context) {
    return root.evaluate(context);
  }

  // ---- syntax tree ------------------------------------------------------------------

  private sealed interface Node {
    boolean evaluate(RuleContext context);
  }

  private record Or(List<Node> parts) implements Node {
    public boolean evaluate(RuleContext c) {
      for (var p : parts) {
        if (p.evaluate(c)) {
          return true;
        }
      }
      return false;
    }
  }

  private record And(List<Node> parts) implements Node {
    public boolean evaluate(RuleContext c) {
      for (var p : parts) {
        if (!p.evaluate(c)) {
          return false;
        }
      }
      return true;
    }
  }

  private record Comparison(Operand left, String op, Operand right) implements Node {
    public boolean evaluate(RuleContext c) {
      return Comparisons.apply(left.value(c), op, right.value(c));
    }
  }

  private sealed interface Operand {
    Value value(RuleContext context);
  }

  private record Literal(Value value) implements Operand {
    public Value value(RuleContext context) {
      return value;
    }
  }

  /**
   * A bare name. Resolved against the record and the request first; only if nothing there answers
   * to it is it read as one of the three word-shaped literals, which is what lets a collection with
   * a field called {@code null} still address that field.
   */
  private record Identifier(String name) implements Operand {
    public Value value(RuleContext context) {
      try {
        return context.resolve(name);
      } catch (UnknownIdentifierException e) {
        return switch (name.toLowerCase()) {
          case "null" -> Value.ABSENT;
          case "true" -> new Value.Num(1);
          case "false" -> new Value.Num(0);
          default -> throw e;
        };
      }
    }
  }

  // ---- comparison semantics ---------------------------------------------------------

  static final class Comparisons {

    /**
     * Compiled LIKE patterns, bounded and cleared for the same reason the parse cache is: the right
     * side of a {@code ~} can be a caller-supplied literal.
     */
    private static final int PATTERN_CACHE_LIMIT = 1024;

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    static boolean apply(Value left, String op, Value right) {
      return switch (op) {
        case "=" -> equal(left, right);
        case "!=" -> !equal(left, right);
        case "~" -> like(left, right);
        case "!~" -> !like(left, right);
        case "<" -> compare(left, right) < 0;
        case "<=" -> compare(left, right) <= 0;
        case ">" -> compare(left, right) > 0;
        case ">=" -> compare(left, right) >= 0;
        default -> throw new RuleSyntaxException("unknown operator " + op);
      };
    }

    /** A8: absent and empty are one value; otherwise numeric when both sides are. */
    private static boolean equal(Value a, Value b) {
      if (a.isBlank() || b.isBlank()) {
        return a.isBlank() && b.isBlank();
      }
      if (a.isNumeric() && b.isNumeric()) {
        return a.number() == b.number();
      }
      return a.text().equals(b.text());
    }

    /** A9: numeric only when both sides are numeric values, lexicographic otherwise. */
    private static int compare(Value a, Value b) {
      if (a.isNumeric() && b.isNumeric()) {
        return Double.compare(a.number(), b.number());
      }
      return a.text().compareTo(b.text());
    }

    private static boolean like(Value subject, Value pattern) {
      return likeRegex(pattern.text()).matcher(subject.text()).matches();
    }

    /**
     * A7: the pattern is wrapped in "%" on both sides unless it already carries an unescaped one,
     * in which case it is used verbatim — so a pattern with a wildcard in it is anchored and one
     * without is not. Case-insensitive, matching the source's LIKE.
     */
    private static Pattern likeRegex(String raw) {
      var cached = PATTERNS.get(raw);
      if (cached != null) {
        return cached;
      }
      var compiled = buildRegex(raw);
      if (PATTERNS.size() >= PATTERN_CACHE_LIMIT) {
        PATTERNS.clear();
      }
      PATTERNS.put(raw, compiled);
      return compiled;
    }

    private static Pattern buildRegex(String raw) {
      String pattern = containsUnescaped(raw, '%') ? raw : "%" + escapeWildcards(raw) + "%";
      var sb = new StringBuilder(pattern.length() * 2);
      for (int i = 0; i < pattern.length(); i++) {
        char c = pattern.charAt(i);
        if (c == '\\' && i + 1 < pattern.length()) {
          sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
        } else if (c == '%') {
          sb.append(".*");
        } else if (c == '_') {
          sb.append('.');
        } else {
          sb.append(Pattern.quote(String.valueOf(c)));
        }
      }
      return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static boolean containsUnescaped(String s, char target) {
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\\') {
          i++;
        } else if (c == target) {
          return true;
        }
      }
      return false;
    }

    private static String escapeWildcards(String s) {
      var sb = new StringBuilder(s.length() + 4);
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '\\' && i + 1 < s.length()) {
          sb.append(c).append(s.charAt(++i));
        } else {
          if (c == '_' || c == '%') {
            sb.append('\\');
          }
          sb.append(c);
        }
      }
      return sb.toString();
    }
  }

  // ---- lexer ------------------------------------------------------------------------

  private record Token(Kind kind, String text) {}

  private enum Kind {
    IDENTIFIER,
    STRING,
    NUMBER,
    OPERATOR,
    JOIN,
    OPEN,
    CLOSE
  }

  private static final class Lexer {

    private static final List<String> OPERATORS =
        List.of("!=", "!~", "<=", ">=", "=", "~", "<", ">");

    static List<Token> tokenize(String rule) {
      var tokens = new ArrayList<Token>();
      int i = 0;
      int n = rule.length();
      while (i < n) {
        char c = rule.charAt(i);
        if (Character.isWhitespace(c)) {
          i++;
          continue;
        }
        if (c == '/' && i + 1 < n && rule.charAt(i + 1) == '/') {
          while (i < n && rule.charAt(i) != '\n') {
            i++;
          }
          continue;
        }
        if (c == '(') {
          tokens.add(new Token(Kind.OPEN, "("));
          i++;
          continue;
        }
        if (c == ')') {
          tokens.add(new Token(Kind.CLOSE, ")"));
          i++;
          continue;
        }
        if (rule.startsWith("&&", i) || rule.startsWith("||", i)) {
          tokens.add(new Token(Kind.JOIN, rule.substring(i, i + 2)));
          i += 2;
          continue;
        }
        if (c == '\'' || c == '"') {
          int[] end = new int[1];
          tokens.add(new Token(Kind.STRING, readQuoted(rule, i, end)));
          i = end[0];
          continue;
        }
        String operator = matchOperator(rule, i);
        if (operator != null) {
          tokens.add(new Token(Kind.OPERATOR, operator));
          i += operator.length();
          continue;
        }
        if (Character.isDigit(c)
            || (c == '-' && i + 1 < n && Character.isDigit(rule.charAt(i + 1)))) {
          int start = i;
          i++;
          while (i < n && (Character.isDigit(rule.charAt(i)) || rule.charAt(i) == '.')) {
            i++;
          }
          tokens.add(new Token(Kind.NUMBER, rule.substring(start, i)));
          continue;
        }
        if (isIdentifierChar(c)) {
          int start = i;
          while (i < n && isIdentifierChar(rule.charAt(i))) {
            i++;
          }
          tokens.add(new Token(Kind.IDENTIFIER, rule.substring(start, i)));
          continue;
        }
        throw new RuleSyntaxException("unexpected character '" + c + "' at " + i);
      }
      return tokens;
    }

    /**
     * The operator alternatives are ordered longest-first, so "!=" is never read as "!" followed by
     * "=" — there is no "!" operator to fall back to and the rule would fail on the next character
     * rather than here, where the message is useful.
     */
    private static String matchOperator(String rule, int i) {
      for (var op : OPERATORS) {
        if (rule.startsWith(op, i)) {
          return op;
        }
      }
      return null;
    }

    private static String readQuoted(String rule, int start, int[] endOut) {
      char quote = rule.charAt(start);
      var sb = new StringBuilder();
      int i = start + 1;
      while (i < rule.length()) {
        char c = rule.charAt(i);
        if (c == '\\' && i + 1 < rule.length()) {
          sb.append(rule.charAt(i + 1));
          i += 2;
          continue;
        }
        if (c == quote) {
          endOut[0] = i + 1;
          return sb.toString();
        }
        sb.append(c);
        i++;
      }
      throw new RuleSyntaxException("unterminated string starting at " + start);
    }

    private static boolean isIdentifierChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '@' || c == '-';
    }
  }

  // ---- parser -----------------------------------------------------------------------

  private static final class Parser {

    private final List<Token> tokens;
    private int position;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    Node parseRule() {
      var parts = new ArrayList<Node>();
      parts.add(parseAnd());
      while (peekJoin("||")) {
        position++;
        parts.add(parseAnd());
      }
      return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }

    private Node parseAnd() {
      var parts = new ArrayList<Node>();
      parts.add(parsePrimary());
      while (peekJoin("&&")) {
        position++;
        parts.add(parsePrimary());
      }
      return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    private Node parsePrimary() {
      var token = next("an expression");
      if (token.kind() == Kind.OPEN) {
        var inner = parseRule();
        var close = next("')'");
        if (close.kind() != Kind.CLOSE) {
          throw new RuleSyntaxException("expected ')' but found " + close.text());
        }
        return inner;
      }
      position--;
      var left = parseOperand();
      var op = next("an operator");
      if (op.kind() != Kind.OPERATOR) {
        throw new RuleSyntaxException("expected an operator but found " + op.text());
      }
      var right = parseOperand();
      return new Comparison(left, op.text(), right);
    }

    private Operand parseOperand() {
      var token = next("an operand");
      return switch (token.kind()) {
        case IDENTIFIER -> new Identifier(token.text());
        case STRING -> new Literal(new Value.Text(token.text()));
        case NUMBER -> new Literal(new Value.Num(Double.parseDouble(token.text())));
        default -> throw new RuleSyntaxException("expected an operand but found " + token.text());
      };
    }

    private boolean peekJoin(String join) {
      return position < tokens.size()
          && tokens.get(position).kind() == Kind.JOIN
          && tokens.get(position).text().equals(join);
    }

    private Token next(String expected) {
      if (position >= tokens.size()) {
        throw new RuleSyntaxException("expected " + expected + " but the rule ended");
      }
      return tokens.get(position++);
    }

    void expectEnd() {
      if (position < tokens.size()) {
        throw new RuleSyntaxException("unexpected trailing " + tokens.get(position).text());
      }
    }
  }
}
