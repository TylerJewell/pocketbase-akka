package io.akka.pocketbase.api;

import akka.NotUsed;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import io.akka.pocketbase.application.AuthChangeRejected;
import io.akka.pocketbase.application.Identities;
import io.akka.pocketbase.application.RealtimeBroker;
import io.akka.pocketbase.application.RealtimeClient;
import io.akka.pocketbase.application.RealtimeFrame;
import io.akka.pocketbase.domain.SubscriptionSet;
import java.util.List;
import java.util.Map;

/**
 * The two calls that make up a realtime session — SPEC-001 §3 Session.
 *
 * <p>{@code GET} opens the stream and announces the client id on it; {@code POST} sets that
 * client's topics. They are two calls rather than one because the topic set changes over the life
 * of a stream that is already open.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/realtime")
public class RealtimeEndpoint extends AbstractHttpEndpoint {

  private static final String CONNECT_EVENT = "PB_CONNECT";
  private static final int STREAM_BUFFER = 256;
  private static final int MAX_SUBSCRIPTIONS = 1000;
  private static final int MAX_SUBSCRIPTION_LENGTH = 2500;
  private static final int MAX_CLIENT_ID_LENGTH = 255;

  public record SubscribeRequest(String clientId, List<String> subscriptions) {}

  private final ComponentClient componentClient;

  public RealtimeEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("")
  public HttpResponse connect() {
    var broker = RealtimeBroker.instance();
    var client = broker.register(callerIp());

    var connect =
        new RealtimeFrame(client.id(), CONNECT_EVENT, Map.of("clientId", client.id()));

    Source<RealtimeFrame, NotUsed> live =
        Source.<RealtimeFrame>queue(STREAM_BUFFER)
            .mapMaterializedValue(
                queue -> {
                  client.attach(queue::offer);
                  return NotUsed.getInstance();
                });

    // concat rather than concatLazy: the queue has to be materialised, and therefore attached,
    // before the first frame is written, or a change arriving in that window is dropped.
    var frames =
        Source.single(connect)
            .concat(live)
            .watchTermination(
                (mat, done) -> {
                  done.whenComplete((ignored, error) -> broker.unregister(client.id()));
                  return mat;
                });

    return HttpResponses.serverSentEvents(
        frames, RealtimeFrame::clientId, RealtimeFrame::topic);
  }

  @Post("")
  public HttpResponse subscribe(SubscribeRequest request) {
    validate(request);

    var client =
        RealtimeBroker.instance()
            .byId(request.clientId())
            .orElseThrow(() -> HttpException.notFound());

    requireSameIp(client);

    var identity = Identities.resolve(componentClient, header("Authorization"));
    try {
      client.requireAuthUpgradeAllowed(identity);
    } catch (AuthChangeRejected e) {
      throw HttpException.forbidden(e.getMessage());
    }

    client.setAuth(identity);
    client.subscribe(
        SubscriptionSet.of(
            request.subscriptions() == null ? List.of() : request.subscriptions()));

    return HttpResponses.noContent();
  }

  private void validate(SubscribeRequest request) {
    if (request == null
        || request.clientId() == null
        || request.clientId().isEmpty()
        || request.clientId().length() > MAX_CLIENT_ID_LENGTH) {
      throw HttpException.badRequest("clientId: cannot be blank.");
    }
    var subscriptions = request.subscriptions();
    if (subscriptions == null) {
      return;
    }
    if (subscriptions.size() > MAX_SUBSCRIPTIONS) {
      throw HttpException.badRequest("subscriptions: the length must be no more than 1000.");
    }
    for (var subscription : subscriptions) {
      if (subscription != null && subscription.length() > MAX_SUBSCRIPTION_LENGTH) {
        throw HttpException.badRequest("subscriptions: the length must be no more than 2500.");
      }
    }
  }

  /**
   * S-3. An empty recorded IP means no check, matching the source's allowance for clients that
   * were registered without one.
   */
  private void requireSameIp(RealtimeClient client) {
    if (!client.ip().isEmpty() && !client.ip().equals(callerIp())) {
      throw HttpException.badRequest(
          "Invalid realtime client: the subscription request IP doesn't match the client IP.");
    }
  }

  /**
   * The SDK gives an endpoint no access to the socket's peer address, so the caller's address is
   * whatever a proxy in front of it declared. An absent header is an empty address, which switches
   * the check off rather than failing closed — the alternative would reject every subscribe call
   * made without a proxy.
   */
  private String callerIp() {
    var forwarded = header("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) {
      return "";
    }
    int comma = forwarded.indexOf(',');
    return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
  }

  private String header(String name) {
    return requestContext().requestHeader(name).map(h -> h.value()).orElse(null);
  }
}
