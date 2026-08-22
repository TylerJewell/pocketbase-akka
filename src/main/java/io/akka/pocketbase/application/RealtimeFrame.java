package io.akka.pocketbase.application;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One server-sent event: the client id goes in the SSE {@code id} field, the topic in {@code event}
 * and the payload in {@code data}.
 *
 * <p>{@code @JsonValue} is what keeps the other two out of the body — the SSE writer serialises the
 * whole element, so a frame that carried them as ordinary properties would put them in {@code data}
 * as well.
 */
public record RealtimeFrame(String clientId, String topic, @JsonValue Object payload) {}
