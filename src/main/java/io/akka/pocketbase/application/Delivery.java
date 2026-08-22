package io.akka.pocketbase.application;

/**
 * One message that one client's one subscription has earned.
 *
 * <p>The dispatcher returns these rather than sending them, so the decision — who hears what — can
 * be checked without a stream, a queue or a clock in the way.
 */
public record Delivery(String clientId, String topic, RecordPayload payload) {}
