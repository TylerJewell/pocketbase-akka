package io.akka.pocketbase.application;

import akka.javasdk.client.ComponentClient;
import io.akka.pocketbase.domain.AuthIdentity;

/**
 * Resolves the {@code Authorization: Record <collection>/<recordId>} header into the identity a
 * rule reads — SPEC-001 §2.4.
 *
 * <p>Token issuance and verification are out of the slice: a rule's input is an identity, and
 * minting one is a different capability. What is in the slice is that the identity resolves to a
 * real record with real fields, because {@code @request.auth.<field>} reads them.
 */
public final class Identities {

  public static final String SUPERUSERS = "_superusers";
  private static final String PREFIX = "Record ";

  private Identities() {}

  /** Null for a guest — an absent header, a header in another scheme, or an unknown record. */
  public static AuthIdentity resolve(ComponentClient componentClient, String authorization) {
    if (authorization == null || !authorization.startsWith(PREFIX)) {
      return null;
    }
    var reference = authorization.substring(PREFIX.length()).trim();
    int slash = reference.indexOf('/');
    if (slash <= 0 || slash == reference.length() - 1) {
      return null;
    }
    var collectionName = reference.substring(0, slash);
    var recordId = reference.substring(slash + 1);

    var state =
        componentClient
            .forEventSourcedEntity(RecordEntity.entityId(collectionName, recordId))
            .method(RecordEntity::get)
            .invoke();

    if (!state.exists()) {
      return null;
    }
    return new AuthIdentity(
        collectionName, recordId, state.fields(), SUPERUSERS.equals(collectionName));
  }
}
