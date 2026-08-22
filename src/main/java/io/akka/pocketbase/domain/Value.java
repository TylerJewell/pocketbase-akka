package io.akka.pocketbase.domain;

/**
 * One side of a comparison, reduced to the three kinds a rule can tell apart.
 *
 * <p>Numeric and textual are distinct kinds rather than a formatting question: a text field holding
 * "9" orders as text and a number field holding 9 orders as a number, and the two disagree against
 * "10". {@code Absent} exists because a rule cannot tell an unset field from a blank one and both
 * have to compare equal to the empty string.
 */
public sealed interface Value {

  record Text(String value) implements Value {}

  record Num(double value) implements Value {}

  record Absent() implements Value {}

  Value ABSENT = new Absent();

  static Value of(Object raw) {
    if (raw == null) {
      return ABSENT;
    }
    if (raw instanceof Boolean b) {
      return new Num(b ? 1 : 0);
    }
    if (raw instanceof Number n) {
      return new Num(n.doubleValue());
    }
    return new Text(raw.toString());
  }

  default boolean isNumeric() {
    return this instanceof Num;
  }

  /** The value as a rule would print it. A whole number loses its trailing ".0". */
  default String text() {
    if (this instanceof Text t) {
      return t.value();
    }
    if (this instanceof Num n) {
      double v = n.value();
      return v == Math.rint(v) && !Double.isInfinite(v)
          ? Long.toString((long) v)
          : Double.toString(v);
    }
    return "";
  }

  default double number() {
    return this instanceof Num n ? n.value() : 0;
  }

  /** Absent and the empty string are the same thing to a rule (SPEC-001 A8). */
  default boolean isBlank() {
    return this instanceof Absent || (this instanceof Text t && t.value().isEmpty());
  }
}
