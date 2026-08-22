package io.akka.pocketbase.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.CommandException;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Delete;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Patch;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.javasdk.http.HttpResponses;
import io.akka.pocketbase.application.CollectionEntity;
import io.akka.pocketbase.application.RecordEntity;
import io.akka.pocketbase.domain.CollectionDef;
import io.akka.pocketbase.domain.FieldDef;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The surface that puts records in and takes them out, so the capability under test is reachable
 * without a test harness around it.
 *
 * <p>Deliberately thin: everything interesting about a record change happens after it commits, in
 * {@code RecordChangeConsumer}. What this has to get right is the one thing dispatch depends on —
 * that a change is written exactly when it is accepted, and not at all when it is not.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/collections")
public class CollectionEndpoint extends AbstractHttpEndpoint {

  public record DefineCollection(
      String name,
      String id,
      String type,
      List<FieldDef> fields,
      String listRule,
      String viewRule,
      String manageRule) {}

  public record RecordBody(String id, Map<String, Object> fields) {}

  /**
   * A collection as this API reports it.
   *
   * <p>Field-for-field the same as {@link CollectionDef} today, and separate anyway: the three
   * rules distinguish null from the empty string (SPEC-001 §2.1) and that distinction is part of
   * what this endpoint promises a caller, not an internal detail that may be changed with the
   * domain type.
   */
  public record CollectionView(
      String name,
      String id,
      String type,
      List<FieldDef> fields,
      String listRule,
      String viewRule,
      String manageRule) {

    static CollectionView of(CollectionDef definition) {
      return new CollectionView(
          definition.name(),
          definition.id(),
          definition.type(),
          definition.fields(),
          definition.listRule(),
          definition.viewRule(),
          definition.manageRule());
    }
  }

  private final ComponentClient componentClient;

  public CollectionEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("")
  public HttpResponse define(DefineCollection request) {
    if (request.name() == null || request.name().isEmpty()) {
      throw HttpException.badRequest("name: cannot be blank.");
    }
    var definition =
        new CollectionDef(
            request.name(),
            request.id() == null ? request.name() : request.id(),
            request.type() == null ? "base" : request.type(),
            request.fields() == null ? List.of() : request.fields(),
            request.listRule(),
            request.viewRule(),
            request.manageRule());

    componentClient
        .forKeyValueEntity(definition.name())
        .method(CollectionEntity::define)
        .invoke(definition);

    return HttpResponses.created(CollectionView.of(definition));
  }

  @Get("/{name}")
  public CollectionView collection(String name) {
    return CollectionView.of(requireCollection(name));
  }

  @Post("/{name}/records")
  public HttpResponse create(String name, RecordBody body) {
    requireCollection(name);
    var recordId = body.id() == null || body.id().isEmpty() ? newId() : body.id();

    onRecord(
        name,
        recordId,
        () ->
            componentClient
                .forEventSourcedEntity(RecordEntity.entityId(name, recordId))
                .method(RecordEntity::create)
                .invoke(
                    new RecordEntity.Create(
                        name, body.fields() == null ? Map.of() : body.fields())));

    return HttpResponses.created(Map.of("id", recordId, "collectionName", name));
  }

  @Patch("/{name}/records/{recordId}")
  public HttpResponse update(String name, String recordId, RecordBody body) {
    requireCollection(name);
    onRecord(
        name,
        recordId,
        () ->
            componentClient
                .forEventSourcedEntity(RecordEntity.entityId(name, recordId))
                .method(RecordEntity::update)
                .invoke(
                    new RecordEntity.Update(body.fields() == null ? Map.of() : body.fields())));

    return HttpResponses.ok(Map.of("id", recordId, "collectionName", name));
  }

  @Delete("/{name}/records/{recordId}")
  public HttpResponse delete(String name, String recordId) {
    requireCollection(name);
    onRecord(
        name,
        recordId,
        () ->
            componentClient
                .forEventSourcedEntity(RecordEntity.entityId(name, recordId))
                .method(RecordEntity::delete)
                .invoke());

    return HttpResponses.noContent();
  }

  @Get("/{name}/records/{recordId}")
  public Map<String, Object> read(String name, String recordId) {
    var state =
        componentClient
            .forEventSourcedEntity(RecordEntity.entityId(name, recordId))
            .method(RecordEntity::get)
            .invoke();
    if (!state.exists()) {
      throw HttpException.notFound();
    }
    return state.snapshot().withId();
  }

  private CollectionDef requireCollection(String name) {
    var definition =
        componentClient.forKeyValueEntity(name).method(CollectionEntity::get).invoke();
    if (definition.name() == null) {
      throw HttpException.notFound();
    }
    return definition;
  }

  /**
   * Runs an entity call and gives its refusals the status a caller can act on.
   *
   * <p>Uncaught, a {@code CommandException} reaches the caller as a bare 400 whatever it says, so
   * a write aimed at a record that is not there and one aimed at a record that already is would be
   * the same answer. The entity's two refusals are the only two there are, and neither carries
   * anything the caller did not send.
   */
  private void onRecord(String name, String recordId, Runnable call) {
    try {
      call.run();
    } catch (CommandException e) {
      if (NO_SUCH_RECORD.equals(e.getMessage())) {
        throw HttpException.notFound();
      }
      throw HttpException.badRequest(e.getMessage());
    }
  }

  private static final String NO_SUCH_RECORD = "no such record";

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 15);
  }
}
