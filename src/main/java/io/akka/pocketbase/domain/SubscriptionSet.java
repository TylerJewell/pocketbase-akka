package io.akka.pocketbase.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of topics one client is listening to, and how a dispatch finds the ones that apply —
 * SPEC-001 §3 Matching.
 *
 * <p>The match is a prefix match terminated by "?" on both sides, which is what stops the key
 * {@code demo2} from reaching a subscriber on {@code demo2x}. A subscription may carry its own
 * options after that "?", so the terminator has to be part of the comparison rather than trimmed
 * off first.
 */
public final class SubscriptionSet {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, SubscriptionOptions> subscriptions;

  /**
   * The same subscriptions grouped under the topic key each one answers to.
   *
   * <p>The match in {@link #matching} is decided by the subscription alone — {@code sub + "?"}
   * starts with {@code key + "?"} exactly when {@code key} is the part of {@code sub} before its
   * first "?" — so every subscription answers to exactly one key and the grouping can be built
   * once, when the set is. A dispatch asks six keys per record change per client, and without this
   * each of those six walks the whole set.
   */
  private final Map<String, Map<String, SubscriptionOptions>> byTopic;

  private SubscriptionSet(Map<String, SubscriptionOptions> subscriptions) {
    this.subscriptions = subscriptions;
    this.byTopic = index(subscriptions);
  }

  private static Map<String, Map<String, SubscriptionOptions>> index(
      Map<String, SubscriptionOptions> subscriptions) {
    var out = new LinkedHashMap<String, Map<String, SubscriptionOptions>>();
    subscriptions.forEach(
        (sub, options) ->
            out.computeIfAbsent(topicKeyOf(sub), k -> new LinkedHashMap<>()).put(sub, options));
    out.replaceAll((k, v) -> Collections.unmodifiableMap(v));
    return Collections.unmodifiableMap(out);
  }

  /** The topic a subscription listens to: everything before its first "?". */
  private static String topicKeyOf(String subscription) {
    int q = subscription.indexOf('?');
    return q < 0 ? subscription : subscription.substring(0, q);
  }

  public static SubscriptionSet empty() {
    return new SubscriptionSet(Map.of());
  }

  public static SubscriptionSet of(List<String> raw) {
    var parsed = new LinkedHashMap<String, SubscriptionOptions>();
    for (var s : raw) {
      if (s == null || s.isEmpty()) {
        continue; // M2
      }
      parsed.put(s, parseOptions(s));
    }
    return new SubscriptionSet(Collections.unmodifiableMap(parsed));
  }

  public Map<String, SubscriptionOptions> all() {
    return subscriptions;
  }

  /** The subscriptions this topic key reaches. */
  public Map<String, SubscriptionOptions> matching(String topicKey) {
    return byTopic.getOrDefault(topicKey, Map.of());
  }

  public boolean isEmpty() {
    return subscriptions.isEmpty();
  }

  /**
   * Reads {@code ?options={...}} off the end of a subscription string.
   *
   * <p>A payload that does not parse yields empty options rather than an error: the subscription
   * survives it, and so do its neighbours in the same call (M3).
   */
  private static SubscriptionOptions parseOptions(String subscription) {
    int q = subscription.indexOf('?');
    if (q < 0 || q == subscription.length() - 1) {
      return SubscriptionOptions.EMPTY;
    }
    String raw = queryParam(subscription.substring(q + 1), "options");
    if (raw == null || raw.isEmpty()) {
      return SubscriptionOptions.EMPTY;
    }
    try {
      JsonNode root = MAPPER.readTree(raw);
      return new SubscriptionOptions(
          flatten(root.get("query"), false), flatten(root.get("headers"), true));
    } catch (Exception e) {
      return SubscriptionOptions.EMPTY;
    }
  }

  private static String queryParam(String query, String name) {
    for (var pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      if (pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  /** Every option value is a string, whatever it was written as; header names are snake-cased. */
  private static Map<String, String> flatten(JsonNode node, boolean snakeCaseKeys) {
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    var out = new LinkedHashMap<String, String>();
    node.fields()
        .forEachRemaining(
            e -> {
              var value = e.getValue();
              var text = value.isValueNode() ? value.asText() : value.toString();
              out.put(snakeCaseKeys ? snakeCase(e.getKey()) : e.getKey(), text);
            });
    return Collections.unmodifiableMap(out);
  }

  /** "X-Token" becomes "x_token"; "anotherOne" becomes "another_one". */
  static String snakeCase(String name) {
    var sb = new StringBuilder(name.length() + 4);
    boolean previousWasSeparator = true;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        boolean boundary =
            Character.isUpperCase(c) && i > 0 && Character.isLowerCase(name.charAt(i - 1));
        if (boundary && !previousWasSeparator) {
          sb.append('_');
        }
        sb.append(Character.toLowerCase(c));
        previousWasSeparator = false;
      } else if (!previousWasSeparator) {
        sb.append('_');
        previousWasSeparator = true;
      }
    }
    int end = sb.length();
    while (end > 0 && sb.charAt(end - 1) == '_') {
      end--;
    }
    return sb.substring(0, end);
  }
}
