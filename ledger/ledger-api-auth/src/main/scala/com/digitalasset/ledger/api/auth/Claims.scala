// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.auth

import java.time.Instant

import com.daml.lf.data.Ref

/** A claim is a single statement about what an authenticated user can do with the ledger API.
  *
  * Note: this ADT is expected to evolve in the future by adding new cases for more fine grained claims.
  * The existing cases should be treated as immutable in order to guarantee backwards compatibility for
  * [[AuthService]] implementations.
  */
sealed abstract class Claim

/** Authorized to use all admin services.
  * Does not authorize to use non-admin services.
  */
case object ClaimAdmin extends Claim

/** Authorized to use all "public" services, i.e.,
  * those that do not require admin rights and do not depend on any Daml party.
  * Examples include the LedgerIdentityService or the PackageService.
  */
case object ClaimPublic extends Claim

/** Authorized to act as any party, including:
  * - Reading all data for all parties
  * - Creating contract on behalf of any party
  * - Exercising choices on behalf of any party
  */
case object ClaimActAsAnyParty extends Claim

/** Authorized to act as the given party, including:
  * - Reading all data for the given party
  * - Creating contracts on behalf of the given party
  * - Exercising choices on behalf of the given party
  */
final case class ClaimActAsParty(name: Ref.Party) extends Claim

/** Authorized to read all data for the given party.
  *
  * Does NOT authorize to issue commands.
  */
final case class ClaimReadAsParty(name: Ref.Party) extends Claim

sealed trait ClaimSet

object ClaimSet {
  object Unauthenticated extends ClaimSet

  /** [[Claims]] define what actions an authenticated user can perform on the Ledger API.
    *
    * They also optionally specify an expiration epoch time that statically specifies the
    * time on or after which the token will no longer be considered valid by the Ledger API.
    *
    * Please note that Health and ServerReflection services do NOT require authentication.
    *
    * The following is a full list of services and the corresponding required claims:
    * +-------------------------------------+----------------------------+------------------------------------------+
    * | Ledger API service                  | Method                     | Access with                              |
    * +-------------------------------------+----------------------------+------------------------------------------+
    * | LedgerIdentityService               | GetLedgerIdentity          | isPublic                                 |
    * | ActiveContractsService              | GetActiveContracts         | for each requested party p: canReadAs(p) |
    * | CommandSubmissionService            | Submit                     | for submitting party p: canActAs(p)      |
    * | CommandCompletionService            | CompletionEnd              | isPublic                                 |
    * | CommandCompletionService            | CompletionStream           | for each requested party p: canReadAs(p) |
    * | CommandService                      | *                          | for submitting party p: canActAs(p)      |
    * | Health                              | *                          | N/A (authentication not required)        |
    * | LedgerConfigurationService          | GetLedgerConfiguration     | isPublic                                 |
    * | PackageService                      | *                          | isPublic                                 |
    * | PackageManagementService            | *                          | isAdmin                                  |
    * | PartyManagementService              | *                          | isAdmin                                  |
    * | ResetService                        | *                          | isAdmin                                  |
    * | ServerReflection                    | *                          | N/A (authentication not required)        |
    * | TimeService                         | GetTime                    | isPublic                                 |
    * | TimeService                         | SetTime                    | isAdmin                                  |
    * | TransactionService                  | LedgerEnd                  | isPublic                                 |
    * | TransactionService                  | *                          | for each requested party p: canReadAs(p) |
    * +-------------------------------------+----------------------------+------------------------------------------+
    *
    * @param claims         List of [[Claim]]s describing the authorization this object describes.
    * @param ledgerId       If set, the claims will only be valid on the given ledger identifier.
    * @param participantId  If set, the claims will only be valid on the given participant identifier.
    * @param applicationId  If set, the claims will only be valid on the given application identifier.
    * @param expiration     If set, the claims will cease to be valid at the given time.
    * @param resolvedFromUser  If set, then the claims were resolved from a user in the user management service.
    */
  final case class Claims(
      claims: Seq[Claim],
      ledgerId: Option[String],
      participantId: Option[String],
      applicationId: Option[String],
      expiration: Option[Instant],
      resolvedFromUser: Boolean,
  ) extends ClaimSet {
    def validForLedger(id: String): Either[AuthorizationError, Unit] =
      Either.cond(ledgerId.forall(_ == id), (), AuthorizationError.InvalidLedger(ledgerId.get, id))

    def validForParticipant(id: String): Either[AuthorizationError, Unit] =
      Either.cond(
        participantId.forall(_ == id),
        (),
        AuthorizationError.InvalidParticipant(participantId.get, id),
      )

    def validForApplication(id: String): Either[AuthorizationError, Unit] =
      Either.cond(
        applicationId.forall(_ == id),
        (),
        AuthorizationError.InvalidApplication(applicationId.get, id),
      )

    /** Returns false if the expiration timestamp exists and is greater than or equal to the current time */
    def notExpired(now: Instant): Either[AuthorizationError, Unit] =
      Either.cond(
        expiration.forall(now.isBefore),
        (),
        AuthorizationError.Expired(expiration.get, now),
      )

    /** Returns true if the set of claims authorizes the user to use admin services, unless the claims expired */
    def isAdmin: Either[AuthorizationError, Unit] =
      Either.cond(claims.contains(ClaimAdmin), (), AuthorizationError.MissingAdminClaim)

    /** Returns true if the set of claims authorizes the user to use public services, unless the claims expired */
    def isPublic: Either[AuthorizationError, Unit] =
      Either.cond(claims.contains(ClaimPublic), (), AuthorizationError.MissingPublicClaim)

    /** Returns true if the set of claims authorizes the user to act as the given party, unless the claims expired */
    def canActAs(party: String): Either[AuthorizationError, Unit] = {
      Either.cond(
        claims.exists {
          case ClaimActAsAnyParty => true
          case ClaimActAsParty(p) if p == party => true
          case _ => false
        },
        (),
        AuthorizationError.MissingActClaim(party),
      )
    }

    /** Returns true if the set of claims authorizes the user to read data for the given party, unless the claims expired */
    def canReadAs(party: String): Either[AuthorizationError, Unit] = {
      Either.cond(
        claims.exists {
          case ClaimActAsAnyParty => true
          case ClaimActAsParty(p) if p == party => true
          case ClaimReadAsParty(p) if p == party => true
          case _ => false
        },
        (),
        AuthorizationError.MissingReadClaim(party),
      )
    }
  }

  /** The representation of a user that was authenticated, but whose [[Claims]] have not yet been resolved. */
  final case class AuthenticatedUser(
      userId: String, // TODO (i12049): use Ref.UserId here
      participantId: Option[String],
      expiration: Option[Instant],
  ) extends ClaimSet

  object Claims {

    /** A set of [[Claims]] that does not have any authorization */
    val Empty: Claims = Claims(
      claims = List.empty[Claim],
      ledgerId = None,
      participantId = None,
      applicationId = None,
      expiration = None,
      resolvedFromUser = false,
    )

    /** A set of [[Claims]] that has all possible authorizations */
    val Wildcard: Claims =
      Empty.copy(claims = List[Claim](ClaimPublic, ClaimAdmin, ClaimActAsAnyParty))

  }

}
