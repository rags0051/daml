// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.daml_lf_dev.DamlLf.Archive
import com.daml.lf.crypto.Hash as LfHash
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.data.{Bytes as LfBytes, ImmArray}
import com.daml.lf.transaction.{BlindingInfo, TransactionOuterClass}
import com.daml.lf.value.ValueCoder.DecodeError
import com.digitalasset.canton.ProtoDeserializationError.{
  TimeModelConversionError,
  ValueConversionError,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.configuration.*
import com.digitalasset.canton.ledger.participant.state.v2.*
import com.digitalasset.canton.participant.protocol.{ProcessingSteps, v30}
import com.digitalasset.canton.participant.store.DamlLfSerializers.*
import com.digitalasset.canton.participant.sync.LedgerSyncEvent
import com.digitalasset.canton.protocol.ContractIdSyntax.*
import com.digitalasset.canton.protocol.{
  LfActionNode,
  LfCommittedTransaction,
  LfNodeCreate,
  LfNodeExercises,
  LfNodeId,
  SerializableDeduplicationPeriod,
  SourceDomainId,
  TargetDomainId,
  TransferId,
}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.{
  DurationConverter,
  InstantConverter,
  ParsingResult,
  parseLFWorkflowIdO,
  parseLedgerTransactionId,
  parseLfPartyId,
  protoParser,
  required,
}
import com.digitalasset.canton.store.db.{DbDeserializationException, DbSerializationException}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.version.{
  HasProtocolVersionedCompanion,
  HasProtocolVersionedWrapper,
  ProtoVersion,
  ProtocolVersion,
  ProtocolVersionedCompanionDbHelpers,
  ReleaseProtocolVersion,
  RepresentativeProtocolVersion,
}
import com.digitalasset.canton.{
  LedgerParticipantId,
  LedgerSubmissionId,
  LfPackageId,
  ProtoDeserializationError,
  TransferCounter,
}
import com.google.protobuf.ByteString
import com.google.rpc.status.Status as RpcStatus

/** Wrapper for converting a [[com.digitalasset.canton.participant.sync.LedgerSyncEvent]] to its protobuf companion.
  * Currently only Intended only for storage due to the unusual exceptions which are thrown that are only permitted in a storage context.
  *
  * @throws com.digitalasset.canton.store.db.DbSerializationException if transactions or contracts fail to serialize
  * @throws com.digitalasset.canton.store.db.DbDeserializationException if transactions or contracts fail to deserialize
  */
private[store] final case class SerializableLedgerSyncEvent(event: LedgerSyncEvent)(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[
      SerializableLedgerSyncEvent.type
    ]
) extends HasProtocolVersionedWrapper[SerializableLedgerSyncEvent] {

  @transient override protected lazy val companionObj: SerializableLedgerSyncEvent.type =
    SerializableLedgerSyncEvent

  def toProtoV0: v30.LedgerSyncEvent = {
    val SyncEventP = v30.LedgerSyncEvent.Value

    v30.LedgerSyncEvent(
      event match {
        case configurationChanged: LedgerSyncEvent.ConfigurationChanged =>
          SyncEventP.ConfigurationChanged(
            SerializableConfigurationChanged(configurationChanged).toProtoV30
          )
        case partyAddedToParticipant: LedgerSyncEvent.PartyAddedToParticipant =>
          SyncEventP.PartyAddedToParticipant(
            SerializablePartyAddedToParticipant(partyAddedToParticipant).toProtoV30
          )
        case partyAllocationRejected: LedgerSyncEvent.PartyAllocationRejected =>
          SyncEventP.PartyAllocationRejected(
            SerializablePartyAllocationRejected(partyAllocationRejected).toProtoV30
          )
        case publicPackageUpload: LedgerSyncEvent.PublicPackageUpload =>
          SyncEventP.PublicPackageUpload(
            SerializablePublicPackageUpload(publicPackageUpload).toProtoV30
          )
        case publicPackageUploadRejected: LedgerSyncEvent.PublicPackageUploadRejected =>
          SyncEventP.PublicPackageUploadRejected(
            SerializablePublicPackageUploadRejected(publicPackageUploadRejected).toProtoV30
          )
        case transactionAccepted: LedgerSyncEvent.TransactionAccepted =>
          SyncEventP.TransactionAccepted(
            SerializableTransactionAccepted(transactionAccepted).toProtoV30
          )
        case contractsAdded: LedgerSyncEvent.ContractsAdded =>
          SyncEventP.ContractsAdded(
            SerializableContractsAdded(contractsAdded).toProtoV30
          )
        case contractsPurged: LedgerSyncEvent.ContractsPurged =>
          SyncEventP.ContractsPurged(
            SerializableContractsPurged(contractsPurged).toProtoV30
          )
        case commandRejected: LedgerSyncEvent.CommandRejected =>
          SyncEventP.CommandRejected(SerializableCommandRejected(commandRejected).toProtoV30)

        case transferOut: LedgerSyncEvent.TransferredOut =>
          SyncEventP.TransferredOut(SerializableTransferredOut(transferOut).toProtoV30)

        case transferIn: LedgerSyncEvent.TransferredIn =>
          SyncEventP.TransferredIn(SerializableTransferredIn(transferIn).toProtoV30)

        case partiesAdded: LedgerSyncEvent.PartiesAddedToParticipant =>
          SyncEventP.PartiesAdded(SerializablePartiesAddedToParticipant(partiesAdded).toProtoV30)

        case partiesRemoved: LedgerSyncEvent.PartiesRemovedFromParticipant =>
          SyncEventP.PartiesRemoved(
            SerializablePartiesRemovedFromParticipant(partiesRemoved).toProtoV30
          )
      }
    )
  }
}

private[store] object SerializableLedgerSyncEvent
    extends HasProtocolVersionedCompanion[SerializableLedgerSyncEvent]
    with ProtocolVersionedCompanionDbHelpers[SerializableLedgerSyncEvent] {
  override val name: String = "SerializableLedgerSyncEvent"

  override val supportedProtoVersions: SupportedProtoVersions =
    SupportedProtoVersions(
      ProtoVersion(0) -> VersionedProtoConverter
        .storage(ReleaseProtocolVersion(ProtocolVersion.v30), v30.LedgerSyncEvent)(
          supportedProtoVersion(_)(fromProtoV0),
          _.toProtoV0.toByteString,
        )
    )

  def apply(
      event: LedgerSyncEvent,
      protocolVersion: ProtocolVersion,
  ): SerializableLedgerSyncEvent =
    SerializableLedgerSyncEvent(event)(protocolVersionRepresentativeFor(protocolVersion))

  private[store] def trySerializeNode(node: LfActionNode): ByteString =
    DamlLfSerializers
      .serializeNode(node)
      .valueOr(err =>
        throw new DbSerializationException(
          s"Failed to serialize versioned node: ${err.errorMessage}"
        )
      )

  private def deserializeNode[N <: LfActionNode](
      deserialize: TransactionOuterClass.Node => Either[DecodeError, N]
  )(field: String, serializedNode: ByteString): ParsingResult[N] =
    ProtoConverter.parse(
      TransactionOuterClass.Node.parseFrom,
      (serializedNode: TransactionOuterClass.Node) =>
        deserialize(serializedNode).leftMap { err =>
          ValueConversionError(field, err.errorMessage)
        },
      serializedNode,
    )

  private[store] val deserializeCreateNode: (String, ByteString) => ParsingResult[LfNodeCreate] =
    deserializeNode(DamlLfSerializers.deserializeCreateNode)

  private[store] val deserializeExerciseNode
      : (String, ByteString) => ParsingResult[LfNodeExercises] =
    deserializeNode(DamlLfSerializers.deserializeExerciseNode)

  def fromProtoV0(
      ledgerSyncEventP: v30.LedgerSyncEvent
  ): ParsingResult[SerializableLedgerSyncEvent] = {
    val SyncEventP = v30.LedgerSyncEvent.Value
    val ledgerSyncEvent = ledgerSyncEventP.value match {
      case SyncEventP.Empty =>
        Left(ProtoDeserializationError.FieldNotSet("LedgerSyncEvent.value"))
      case SyncEventP.ConfigurationChanged(configurationChanged) =>
        SerializableConfigurationChanged.fromProtoV30(configurationChanged)
      // Canton was never able to produce ConfigurationChangeRejected message
      case SyncEventP.ConfigurationChangeRejected(_) =>
        Left(
          ProtoDeserializationError.OtherError(
            "Unexpected LedgerSyncEvent.ConfigurationChangeRejected"
          )
        )
      case SyncEventP.PartyAddedToParticipant(partyAddedToParticipant) =>
        SerializablePartyAddedToParticipant.fromProtoV0(partyAddedToParticipant)
      case SyncEventP.PartyAllocationRejected(partyAllocationRejected) =>
        SerializablePartyAllocationRejected.fromProtoV30(partyAllocationRejected)
      case SyncEventP.PublicPackageUpload(publicPackageUpload) =>
        SerializablePublicPackageUpload.fromProtoV30(publicPackageUpload)
      case SyncEventP.PublicPackageUploadRejected(publicPackageUploadRejected) =>
        SerializablePublicPackageUploadRejected.fromProtoV30(publicPackageUploadRejected)
      case SyncEventP.TransactionAccepted(transactionAccepted) =>
        SerializableTransactionAccepted.fromProtoV30(transactionAccepted)
      case SyncEventP.CommandRejected(commandRejected) =>
        SerializableCommandRejected.fromProtoV30(commandRejected)
      case SyncEventP.TransferredOut(transferOut) =>
        SerializableTransferredOut.fromProtoV30(transferOut)
      case SyncEventP.TransferredIn(transferIn) =>
        SerializableTransferredIn.fromProtoV30(transferIn)
      case SyncEventP.ContractsAdded(contractsAdded) =>
        SerializableContractsAdded.fromProtoV30(contractsAdded)
      case SyncEventP.ContractsPurged(contractsPurged) =>
        SerializableContractsPurged.fromProtoV30(contractsPurged)
      case SyncEventP.PartiesAdded(partiesAddedToParticipant) =>
        SerializablePartiesAddedToParticipant.fromProtoV30(partiesAddedToParticipant)
      case SyncEventP.PartiesRemoved(partiesRemovedFromParticipant) =>
        SerializablePartiesRemovedFromParticipant.fromProtoV30(partiesRemovedFromParticipant)
    }

    ledgerSyncEvent.map(
      SerializableLedgerSyncEvent(_)(
        protocolVersionRepresentativeFor(ProtoVersion(0))
      )
    )
  }
}

trait ConfigurationParamsDeserializer {
  def fromProtoV0(
      recordTimeP: Option[com.google.protobuf.timestamp.Timestamp],
      submissionIdP: String,
      participantIdP: String,
      configurationP: (String, Option[v30.Configuration]),
  ): Either[
    ProtoDeserializationError,
    (Timestamp, LedgerSubmissionId, LedgerParticipantId, Configuration),
  ] =
    configurationP match {
      case (field, configP) =>
        for {
          recordTime <- required("recordTime", recordTimeP).flatMap(
            SerializableLfTimestamp.fromProtoPrimitive
          )
          submissionId <- ProtoConverter.parseLFSubmissionId(submissionIdP)
          participantId <- ProtoConverter.parseLfParticipantId(participantIdP)
          configuration <- required(field, configP).flatMap(SerializableConfiguration.fromProtoV30)
        } yield (recordTime, submissionId, participantId, configuration)
    }
}

private[store] final case class SerializableConfigurationChanged(
    configurationChanged: LedgerSyncEvent.ConfigurationChanged
) {
  def toProtoV30: v30.ConfigurationChanged = {
    val LedgerSyncEvent.ConfigurationChanged(
      recordTime,
      submissionId,
      participantId,
      newConfiguration,
    ) =
      configurationChanged
    v30.ConfigurationChanged(
      submissionId,
      Some(SerializableConfiguration(newConfiguration).toProtoV30),
      participantId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
    )
  }
}

private[store] object SerializableConfigurationChanged extends ConfigurationParamsDeserializer {
  def fromProtoV30(
      configurationChangedP: v30.ConfigurationChanged
  ): ParsingResult[LedgerSyncEvent.ConfigurationChanged] = {
    val v30.ConfigurationChanged(submissionIdP, configurationP, participantIdP, recordTimeP) =
      configurationChangedP
    for {
      cfg <- fromProtoV0(
        recordTimeP,
        submissionIdP,
        participantIdP,
        ("configuration", configurationP),
      )
      (recordTime, submissionId, participantId, configuration) = cfg
    } yield LedgerSyncEvent.ConfigurationChanged(
      recordTime,
      submissionId,
      participantId,
      configuration,
    )
  }
}

private[store] final case class SerializablePartyAddedToParticipant(
    partyAddedToParticipant: LedgerSyncEvent.PartyAddedToParticipant
) {
  def toProtoV30: v30.PartyAddedToParticipant = {
    val LedgerSyncEvent.PartyAddedToParticipant(
      party,
      displayName,
      participantId,
      recordTime,
      submissionId,
    ) =
      partyAddedToParticipant
    v30.PartyAddedToParticipant(
      party,
      displayName,
      participantId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      submissionId.fold("")(_.toString),
    )
  }
}

private[store] object SerializablePartyAddedToParticipant {
  def fromProtoV0(
      partyAddedToParticipant: v30.PartyAddedToParticipant
  ): ParsingResult[LedgerSyncEvent.PartyAddedToParticipant] = {
    val v30.PartyAddedToParticipant(
      partyP,
      displayName,
      participantIdP,
      recordTime,
      submissionIdP,
    ) =
      partyAddedToParticipant
    for {
      party <- ProtoConverter.parseLfPartyId(partyP)
      participantId <- ProtoConverter.parseLfParticipantId(participantIdP)
      recordTime <- required("recordTime", recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      // submission id can be empty when the PartyAdded event is sent to non-submitting participants
      submissionId <- ProtoConverter.parseLFSubmissionIdO(submissionIdP)
    } yield LedgerSyncEvent.PartyAddedToParticipant(
      party,
      displayName,
      participantId,
      recordTime,
      submissionId,
    )
  }
}

private[store] final case class SerializablePartyAllocationRejected(
    partyAllocationRejected: LedgerSyncEvent.PartyAllocationRejected
) {
  def toProtoV30: v30.PartyAllocationRejected = {
    val LedgerSyncEvent.PartyAllocationRejected(
      submissionId,
      participantId,
      recordTime,
      rejectionReason,
    ) =
      partyAllocationRejected
    v30.PartyAllocationRejected(
      submissionId,
      participantId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      rejectionReason,
    )
  }
}

private[store] object SerializablePartyAllocationRejected {
  def fromProtoV30(
      partyAllocationRejected: v30.PartyAllocationRejected
  ): ParsingResult[LedgerSyncEvent.PartyAllocationRejected] = {
    val v30.PartyAllocationRejected(submissionIdP, participantIdP, recordTime, rejectionReason) =
      partyAllocationRejected
    for {
      submissionId <- ProtoConverter.parseLFSubmissionId(submissionIdP)
      participantId <- ProtoConverter.parseLfParticipantId(participantIdP)
      recordTime <- required("recordTime", recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
    } yield LedgerSyncEvent.PartyAllocationRejected(
      submissionId,
      participantId,
      recordTime,
      rejectionReason,
    )
  }
}

private[store] final case class SerializablePublicPackageUpload(
    publicPackageUpload: LedgerSyncEvent.PublicPackageUpload
) {
  def toProtoV30: v30.PublicPackageUpload = {
    val LedgerSyncEvent.PublicPackageUpload(archives, sourceDescription, recordTime, submissionId) =
      publicPackageUpload
    v30.PublicPackageUpload(
      archives.map(_.toByteString),
      sourceDescription,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      submissionId.getOrElse(""),
    )
  }
}

private[store] object SerializablePublicPackageUpload {
  import cats.syntax.traverse.*

  def fromProtoV30(
      publicPackageUploadP: v30.PublicPackageUpload
  ): ParsingResult[LedgerSyncEvent.PublicPackageUpload] = {
    val v30.PublicPackageUpload(archivesP, sourceDescription, recordTime, submissionIdP) =
      publicPackageUploadP
    for {
      archives <- archivesP.toList.traverse(protoParser(Archive.parseFrom))
      recordTime <- required("recordTime", recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      // submission id can be empty when the PublicPackageUpload event is sent to non-submitting participants
      submissionId <- ProtoConverter.parseLFSubmissionIdO(submissionIdP)
    } yield LedgerSyncEvent.PublicPackageUpload(
      archives,
      sourceDescription,
      recordTime,
      submissionId,
    )
  }
}

private[store] final case class SerializablePublicPackageUploadRejected(
    publicPackageUploadRejected: LedgerSyncEvent.PublicPackageUploadRejected
) {
  def toProtoV30: v30.PublicPackageUploadRejected = {
    val LedgerSyncEvent.PublicPackageUploadRejected(submissionId, recordTime, rejectionReason) =
      publicPackageUploadRejected
    v30.PublicPackageUploadRejected(
      submissionId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      rejectionReason,
    )
  }
}

private[store] object SerializablePublicPackageUploadRejected {
  def fromProtoV30(
      publicPackageUploadRejectedP: v30.PublicPackageUploadRejected
  ): ParsingResult[LedgerSyncEvent.PublicPackageUploadRejected] = {
    val v30.PublicPackageUploadRejected(submissionIdP, recordTime, rejectionReason) =
      publicPackageUploadRejectedP
    for {
      submissionId <- ProtoConverter.parseLFSubmissionId(submissionIdP)
      recordTime <- required("recordTime", recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
    } yield LedgerSyncEvent.PublicPackageUploadRejected(submissionId, recordTime, rejectionReason)
  }
}

private[store] final case class SerializableTransactionAccepted(
    transactionAccepted: LedgerSyncEvent.TransactionAccepted
) {
  def toProtoV30: v30.TransactionAccepted = {
    val LedgerSyncEvent.TransactionAccepted(
      optCompletionInfo,
      transactionMeta,
      committedTransaction,
      transactionId,
      recordTime,
      divulgedContracts,
      blindingInfo,
      hostedWitnesses,
      contractMetadata,
      domainId,
    ) = transactionAccepted
    val contractMetadataP = contractMetadata.view.map { case (contractId, bytes) =>
      contractId.toProtoPrimitive -> bytes.toByteString
    }.toMap
    v30.TransactionAccepted(
      optCompletionInfo.map(SerializableCompletionInfo(_).toProtoV30),
      Some(SerializableTransactionMeta(transactionMeta).toProtoV30),
      serializeTransaction(
        committedTransaction
      ) // LfCommittedTransaction implicitly turned into LfVersionedTransaction by LF
        .valueOr(err =>
          throw new DbSerializationException(
            s"Failed to serialize versioned transaction: ${err.errorMessage}"
          )
        ),
      transactionId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      divulgedContracts.map(SerializableDivulgedContract(_).toProtoV30),
      blindingInfo.map(SerializableBlindingInfo(_).toProtoV30),
      contractMetadata = contractMetadataP,
      hostedWitnesses = hostedWitnesses,
      domainId = domainId.toProtoPrimitive,
    )
  }
}

private[store] object SerializableTransactionAccepted {
  def fromProtoV30(
      transactionAcceptedP: v30.TransactionAccepted
  ): ParsingResult[LedgerSyncEvent.TransactionAccepted] = {
    val v30.TransactionAccepted(
      completionInfoP,
      transactionMetaP,
      transactionP,
      transactionIdP,
      recordTimeP,
      divulgedContractsP,
      blindingInfoP,
      contractMetadataP,
      hostedWitnessesP,
      domainIdP,
    ) = transactionAcceptedP
    for {
      optCompletionInfo <- completionInfoP.traverse(SerializableCompletionInfo.fromProtoV30)
      transactionMeta <- required("transactionMeta", transactionMetaP)
        .flatMap(SerializableTransactionMeta.fromProtoV30)
      committedTransaction = deserializeTransaction(transactionP)
        .leftMap(err =>
          new DbDeserializationException(
            s"Failed to deserialize versioned transaction: ${err.errorMessage}"
          )
        )
        .fold(throw _, LfCommittedTransaction(_))
      transactionId <- ProtoConverter.parseLedgerTransactionId(transactionIdP)
      recordTime <- required("recordTime", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      divulgedContracts <- divulgedContractsP.toList.traverse(
        SerializableDivulgedContract.fromProtoV30
      )
      blindingInfo <- blindingInfoP.fold(
        Right(None): ParsingResult[Option[BlindingInfo]]
      )(SerializableBlindingInfo.fromProtoV30(_).map(Some(_)))
      contractMetadataSeq <- contractMetadataP.toList.traverse {
        case (contractIdP, driverContractMetadataBytes) =>
          ProtoConverter
            .parseLfContractId(contractIdP)
            .map(_ -> LfBytes.fromByteString(driverContractMetadataBytes))
      }
      contractMetadata = contractMetadataSeq.toMap
      hostedWitnesses <- hostedWitnessesP.traverse(ProtoConverter.parseLfPartyId)
      domainId <- DomainId.fromProtoPrimitive(domainIdP, "domain_id")
    } yield LedgerSyncEvent.TransactionAccepted(
      optCompletionInfo,
      transactionMeta,
      committedTransaction,
      transactionId,
      recordTime,
      divulgedContracts,
      blindingInfo,
      hostedWitnesses.toList,
      contractMetadata = contractMetadata,
      domainId = domainId,
    )
  }
}

private[store] final case class SerializableContractsAdded(
    e: LedgerSyncEvent.ContractsAdded
) {
  def toProtoV30: v30.ContractsAdded = {
    val contractMetadataP = e.contractMetadata.view.map { case (contractId, bytes) =>
      contractId.toProtoPrimitive -> bytes.toByteString
    }.toMap
    v30.ContractsAdded(
      transactionId = e.transactionId,
      contracts = e.contracts.map(SerializableLedgerSyncEvent.trySerializeNode),
      domainId = e.domainId.toProtoPrimitive,
      ledgerTime = Option(SerializableLfTimestamp(e.ledgerTime).toProtoV30),
      recordTime = Option(SerializableLfTimestamp(e.recordTime).toProtoV30),
      hostedWitnesses = e.hostedWitnesses,
      contractMetadata = contractMetadataP,
      workflowId = e.workflowId.getOrElse(""),
    )
  }
}

private[store] object SerializableContractsAdded {
  def fromProtoV30(
      e: v30.ContractsAdded
  ): ParsingResult[LedgerSyncEvent.ContractsAdded] =
    for {
      transactionId <- parseLedgerTransactionId(e.transactionId)
      contracts <- e.contracts.traverse(
        SerializableLedgerSyncEvent.deserializeCreateNode("contracts", _)
      )
      domainId <- DomainId.fromProtoPrimitive(e.domainId, "domain_id")
      recordTime <- required("record_time", e.recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      ledgerTime <- required("ledger_time", e.ledgerTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      hostedWitnesses <- e.hostedWitnesses.traverse(parseLfPartyId)
      contractMetadata <- e.contractMetadata.toList.traverse {
        case (contractIdP, driverContractMetadataBytes) =>
          ProtoConverter
            .parseLfContractId(contractIdP)
            .map(_ -> LfBytes.fromByteString(driverContractMetadataBytes))
      }
      workflowId <- parseLFWorkflowIdO(e.workflowId)
    } yield LedgerSyncEvent.ContractsAdded(
      transactionId = transactionId,
      contracts = contracts,
      domainId = domainId,
      recordTime = recordTime,
      ledgerTime = ledgerTime,
      hostedWitnesses = hostedWitnesses,
      contractMetadata = contractMetadata.toMap,
      workflowId = workflowId,
    )
}

private[store] final case class SerializableContractsPurged(
    c: LedgerSyncEvent.ContractsPurged
) {
  def toProtoV30: v30.ContractsPurged =
    v30.ContractsPurged(
      transactionId = c.transactionId,
      contracts = c.contracts.map(SerializableLedgerSyncEvent.trySerializeNode),
      domainId = c.domainId.toProtoPrimitive,
      recordTime = Option(SerializableLfTimestamp(c.recordTime).toProtoV30),
      hostedWitnesses = c.hostedWitnesses,
    )
}

private[store] object SerializableContractsPurged {
  def fromProtoV30(
      c: v30.ContractsPurged
  ): ParsingResult[LedgerSyncEvent.ContractsPurged] =
    for {
      transactionId <- parseLedgerTransactionId(c.transactionId)
      contracts <- c.contracts.traverse(
        SerializableLedgerSyncEvent.deserializeExerciseNode("contracts", _)
      )
      domainId <- DomainId.fromProtoPrimitive(c.domainId, "domain_id")
      recordTime <- required("record_time", c.recordTime).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      hostedWitnesses <- c.hostedWitnesses.traverse(parseLfPartyId)
    } yield LedgerSyncEvent.ContractsPurged(
      transactionId = transactionId,
      contracts = contracts,
      domainId = domainId,
      recordTime = recordTime,
      hostedWitnesses = hostedWitnesses,
    )
}

private[store] final case class SerializableDivulgedContract(divulgedContract: DivulgedContract) {
  def toProtoV30: v30.DivulgedContract = {
    val DivulgedContract(contractId, contractInst) = divulgedContract
    v30.DivulgedContract(
      contractId = contractId.toProtoPrimitive,
      contractInst = serializeContract(contractInst)
        .valueOr(err =>
          throw new DbSerializationException(
            s"Failed to serialize contract: ${err.errorMessage}"
          )
        ),
    )
  }
}

private[store] object SerializableDivulgedContract {
  def fromProtoV30(
      divulgedContract: v30.DivulgedContract
  ): ParsingResult[DivulgedContract] = {
    val v30.DivulgedContract(contractIdP, contractInstP) = divulgedContract
    for {
      contractId <- ProtoConverter.parseLfContractId(contractIdP)
      contractInstance <- deserializeContract(contractInstP).leftMap(err =>
        ValueConversionError("contractInst", err.errorMessage)
      )
    } yield DivulgedContract(contractId, contractInstance)
  }
}

private[store] final case class SerializableCommandRejected(
    commandRejected: LedgerSyncEvent.CommandRejected
) {
  def toProtoV30: v30.CommandRejected = {
    val LedgerSyncEvent.CommandRejected(recordTime, completionInfo, reason, commandKind, domainId) =
      commandRejected

    val commandKindP = commandKind match {
      case ProcessingSteps.RequestType.Transaction => v30.CommandKind.Transaction
      case ProcessingSteps.RequestType.TransferOut => v30.CommandKind.TransferOut
      case ProcessingSteps.RequestType.TransferIn => v30.CommandKind.TransferIn
    }

    v30.CommandRejected(
      Some(SerializableCompletionInfo(completionInfo).toProtoV30),
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      Some(SerializableRejectionReasonTemplate(reason).toProtoV30),
      commandKindP,
      domainId.toProtoPrimitive,
    )
  }
}

private[store] object SerializableCommandRejected {
  def fromProtoV30(
      commandRejectedP: v30.CommandRejected
  ): ParsingResult[LedgerSyncEvent.CommandRejected] = {
    val v30.CommandRejected(
      completionInfoP,
      recordTimeP,
      rejectionReasonP,
      commandTypeP,
      domainIdP,
    ) =
      commandRejectedP

    val commandTypeE: ParsingResult[ProcessingSteps.RequestType.Values] = commandTypeP match {
      case v30.CommandKind.Transaction => Right(ProcessingSteps.RequestType.Transaction)
      case v30.CommandKind.TransferOut => Right(ProcessingSteps.RequestType.TransferOut)
      case v30.CommandKind.TransferIn => Right(ProcessingSteps.RequestType.TransferIn)
      case v30.CommandKind.Unrecognized(unrecognizedValue) =>
        Left(ProtoDeserializationError.UnrecognizedEnum("command kind", unrecognizedValue))
    }

    for {
      recordTime <- required("recordTime", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      completionInfo <- required("completionInfo", completionInfoP).flatMap(
        SerializableCompletionInfo.fromProtoV30
      )
      rejectionReason <- required("rejectionReason", rejectionReasonP).flatMap(
        SerializableRejectionReasonTemplate.fromProtoV30
      )
      commandType <- commandTypeE
      domainId <- DomainId.fromProtoPrimitive(domainIdP, "domain_id")
    } yield LedgerSyncEvent.CommandRejected(
      recordTime,
      completionInfo,
      rejectionReason,
      commandType,
      domainId,
    )
  }
}

private[store] final case class SerializableLfTimestamp(timestamp: Timestamp) {
  def toProtoV30: com.google.protobuf.timestamp.Timestamp =
    InstantConverter.toProtoPrimitive(timestamp.toInstant)
}

private[store] object SerializableLfTimestamp {
  def fromProtoPrimitive(
      timestampP: com.google.protobuf.timestamp.Timestamp
  ): ParsingResult[Timestamp] =
    for {
      instant <- InstantConverter.fromProtoPrimitive(timestampP)
      // we prefer sticking to our fromProto convention which prevents passing the field name
      // hence the fieldName is unknown at this point. We may decide to invest in richer
      // error context information passing in the future if deemed valuable.
      timestamp <- Timestamp.fromInstant(instant).left.map(ValueConversionError("<unknown>", _))
    } yield timestamp
}

private[store] final case class SerializableConfiguration(configuration: Configuration) {
  def toProtoV30: v30.Configuration = configuration match {
    case Configuration(generation, timeModel, maxDeduplicationDuration) =>
      v30.Configuration(
        generation,
        Some(SerializableTimeModel(timeModel).toProtoV30),
        Some(DurationConverter.toProtoPrimitive(maxDeduplicationDuration)),
      )
  }
}

private[store] object SerializableConfiguration {
  def fromProtoV30(
      configuration: v30.Configuration
  ): ParsingResult[Configuration] = {
    val v30.Configuration(generationP, timeModelP, maxDeduplicationDurationP) = configuration
    for {
      timeModel <- required("timeModel", timeModelP).flatMap(SerializableTimeModel.fromProtoV30)
      maxDeduplicationDuration <- required("maxDeduplicationDuration", maxDeduplicationDurationP)
        .flatMap(
          DurationConverter.fromProtoPrimitive
        )
    } yield Configuration(generationP, timeModel, maxDeduplicationDuration)
  }
}

private[store] final case class SerializableTimeModel(timeModel: LedgerTimeModel) {
  def toProtoV30: v30.TimeModel =
    // uses direct field access as TimeModel is a trait rather than interface
    v30.TimeModel(
      Some(DurationConverter.toProtoPrimitive(timeModel.avgTransactionLatency)),
      Some(DurationConverter.toProtoPrimitive(timeModel.minSkew)),
      Some(DurationConverter.toProtoPrimitive(timeModel.maxSkew)),
    )
}

private[store] object SerializableTimeModel {
  def fromProtoV30(timeModelP: v30.TimeModel): ParsingResult[LedgerTimeModel] = {
    val v30.TimeModel(avgTransactionLatencyP, minSkewP, maxSkewP) =
      timeModelP
    for {
      // abbreviations are due to not being able to use full names as they'd be considered accessors in the time model definition below
      atl <- deserializeDuration("avgTransactionLatencyP", avgTransactionLatencyP)
      mis <- deserializeDuration("minSkewP", minSkewP)
      mas <- deserializeDuration("maxSkewP", maxSkewP)
      // this is quite sketchy however there is no current way to use the values persisted for all fields rather than potentially different new defaults
      // (without adjusting upstream)
      timeModel <- LedgerTimeModel(atl, mis, mas)
        .fold(t => Left(TimeModelConversionError(t.getMessage)), Right(_))
    } yield timeModel
  }

  private def deserializeDuration(
      field: String,
      optDurationP: Option[com.google.protobuf.duration.Duration],
  ): ParsingResult[java.time.Duration] =
    required(field, optDurationP).flatMap(DurationConverter.fromProtoPrimitive)
}

final case class SerializableCompletionInfo(completionInfo: CompletionInfo) {
  def toProtoV30: v30.CompletionInfo = {
    val CompletionInfo(
      actAs,
      applicationId,
      commandId,
      deduplicateUntil,
      submissionId,
      statistics,
    ) =
      completionInfo
    require(
      statistics.isEmpty,
      "Statistics are only set before emitting CompletionInfo in CantonSyncService",
    )
    v30.CompletionInfo(
      actAs,
      applicationId,
      commandId,
      deduplicateUntil.map(SerializableDeduplicationPeriod(_).toProtoV30),
      submissionId.getOrElse(""),
    )
  }
}

object SerializableCompletionInfo {
  def fromProtoV30(
      completionInfoP: v30.CompletionInfo
  ): ParsingResult[CompletionInfo] = {
    val v30.CompletionInfo(actAsP, applicationIdP, commandIdP, deduplicateUntilP, submissionIdP) =
      completionInfoP
    for {
      actAs <- actAsP.toList.traverse(ProtoConverter.parseLfPartyId)
      applicationId <- ProtoConverter.parseLFApplicationId(applicationIdP)
      commandId <- ProtoConverter.parseCommandId(commandIdP)
      deduplicateUntil <- deduplicateUntilP.traverse(SerializableDeduplicationPeriod.fromProtoV30)
      submissionId <- ProtoConverter.parseLFSubmissionIdO(submissionIdP)
    } yield CompletionInfo(
      actAs,
      applicationId,
      commandId,
      deduplicateUntil,
      submissionId,
      statistics = None,
    )
  }
}

private[store] final case class SerializableNodeSeed(nodeId: LfNodeId, seedHash: LfHash) {
  def toProtoV30: v30.NodeSeed =
    v30.NodeSeed(nodeId.index, ByteString.copyFrom(seedHash.bytes.toByteArray))
}

private[store] object SerializableNodeSeed {
  def fromProtoV30(nodeSeed: v30.NodeSeed): ParsingResult[(LfNodeId, LfHash)] = {
    val v30.NodeSeed(nodeIndex, seedHashP) = nodeSeed
    for {
      nodeId <- Right(LfNodeId(nodeIndex))
      nodeSeedHash <- LfHash
        .fromBytes(LfBytes.fromByteString(seedHashP))
        .leftMap(ValueConversionError("nodeSeed", _))
    } yield (nodeId, nodeSeedHash)
  }
}

private[store] final case class SerializableTransactionMeta(transactionMeta: TransactionMeta) {
  def toProtoV30: v30.TransactionMeta = {
    val TransactionMeta(
      ledgerTime,
      workflowId,
      submissionTime,
      submissionSeed,
      optUsedPackages,
      optNodeSeeds,
      optByKeyNodes,
    ) = transactionMeta
    v30.TransactionMeta(
      ledgerTime = Some(InstantConverter.toProtoPrimitive(ledgerTime.toInstant)),
      workflowId = workflowId,
      submissionTime = Some(InstantConverter.toProtoPrimitive(submissionTime.toInstant)),
      submissionSeed = ByteString.copyFrom(submissionSeed.bytes.toByteArray),
      usedPackages = optUsedPackages.fold(Seq.empty[String])(_.map(_.toString).toSeq),
      nodeSeeds = optNodeSeeds.fold(Seq.empty[v30.NodeSeed])(_.map { case (nodeId, seedHash) =>
        SerializableNodeSeed(nodeId, seedHash).toProtoV30
      }.toSeq),
      byKeyNodes = optByKeyNodes.map(byKeyNodes =>
        v30.TransactionMeta.ByKeyNodes(byKeyNodes.map(_.index).toSeq)
      ),
    )
  }
}

private[store] object SerializableTransactionMeta {

  def fromProtoV30(
      transactionMetaP: v30.TransactionMeta
  ): ParsingResult[TransactionMeta] = {
    val v30.TransactionMeta(
      ledgerTimeP,
      workflowIdP,
      submissionTimeP,
      submissionSeedP,
      usedPackagesP,
      nodeSeedsP,
      byKeyNodesP,
    ) =
      transactionMetaP
    for {
      ledgerTime <- required("ledger_time", ledgerTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      workflowId <- ProtoConverter.parseLFWorkflowIdO(workflowIdP.getOrElse(""))
      submissionTime <- required("submissionTime", submissionTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      submissionSeed <- LfHash
        .fromBytes(LfBytes.fromByteString(submissionSeedP))
        .leftMap(ValueConversionError("submissionSeed", _))
      optUsedPackages <- {
        if (usedPackagesP.isEmpty) Right(None)
        else
          usedPackagesP.toList
            .traverse(LfPackageId.fromString(_).leftMap(ValueConversionError("usedPackages", _)))
            .map(packageList => Some(packageList.toSet))
      }
      optNodeSeeds <- nodeSeedsP
        .traverse(SerializableNodeSeed.fromProtoV30)
        .map(list => Some(list.to(ImmArray)))
      optByKeyNodes = byKeyNodesP.map(byKeyNodes =>
        byKeyNodes.byKeyNode.map(LfNodeId(_)).to(ImmArray)
      )
    } yield TransactionMeta(
      ledgerTime,
      workflowId,
      submissionTime,
      submissionSeed,
      optUsedPackages,
      optNodeSeeds,
      optByKeyNodes,
    )
  }
}

private[store] final case class SerializableBlindingInfo(blindingInfo: BlindingInfo) {
  def toProtoV30: v30.BlindingInfo = {
    val BlindingInfo(disclosure, divulgence) = blindingInfo

    v30.BlindingInfo(
      disclosure.map { case (LfNodeId(nodeId), parties) => nodeId -> v30.Parties(parties.toSeq) },
      divulgence.map { case (contractId, parties) => contractId.coid -> v30.Parties(parties.toSeq) },
    )
  }
}

private[store] object SerializableBlindingInfo {
  def fromProtoV30(
      blindingInfoP: v30.BlindingInfo
  ): ParsingResult[BlindingInfo] = {
    val v30.BlindingInfo(disclosureP, divulgenceP) = blindingInfoP
    for {
      disclosure <- disclosureP.toList
        .traverse { case (nodeIdAsInt, parties) =>
          parties.parties.toList
            .traverse(ProtoConverter.parseLfPartyId)
            .map(parties => LfNodeId(nodeIdAsInt) -> parties.toSet)
        }
        .map(_.toMap)
      divulgence <- divulgenceP.toList
        .traverse { case (contractIdP, parties) =>
          ProtoConverter
            .parseLfContractId(contractIdP)
            .flatMap(contractId =>
              parties.parties
                .traverse(ProtoConverter.parseLfPartyId)
                .map(parties => contractId -> parties.toSet)
            )
        }
        .map(_.toMap)
    } yield BlindingInfo(disclosure, divulgence)
  }
}

final case class SerializableRejectionReasonTemplate(
    rejectionReason: LedgerSyncEvent.CommandRejected.FinalReason
) {
  def toProtoV30: v30.CommandRejected.GrpcRejectionReasonTemplate =
    v30.CommandRejected.GrpcRejectionReasonTemplate(rejectionReason.status.toByteString)
}

object SerializableRejectionReasonTemplate {
  def fromProtoV30(
      reasonP: v30.CommandRejected.GrpcRejectionReasonTemplate
  ): ParsingResult[LedgerSyncEvent.CommandRejected.FinalReason] = {
    for {
      rpcStatus <- ProtoConverter.protoParser(RpcStatus.parseFrom)(reasonP.status)
    } yield LedgerSyncEvent.CommandRejected.FinalReason(rpcStatus)
  }
}

private[store] final case class SerializableTransferredOut(
    transferOut: LedgerSyncEvent.TransferredOut
) {
  def toProtoV30: v30.TransferredOut = {
    val LedgerSyncEvent.TransferredOut(
      updateId,
      optCompletionInfo,
      submitter,
      contractId,
      templateId,
      contractStakeholders,
      transferId,
      target,
      transferInExclusivity,
      workflowId,
      isTransferringParticipant,
      hostedStakeholders,
      transferCounter,
    ) = transferOut
    v30.TransferredOut(
      updateId = updateId,
      completionInfo = optCompletionInfo.map(SerializableCompletionInfo(_).toProtoV30),
      submitter = submitter.getOrElse(""),
      recordTime =
        Some(SerializableLfTimestamp(transferId.transferOutTimestamp.underlying).toProtoV30),
      contractId = contractId.toProtoPrimitive,
      templateId = templateId.map(_.toString).getOrElse(""),
      contractStakeholders = contractStakeholders.toSeq,
      sourceDomain = transferId.sourceDomain.toProtoPrimitive,
      targetDomain = target.toProtoPrimitive,
      transferInExclusivity = transferInExclusivity.map(SerializableLfTimestamp(_).toProtoV30),
      workflowId = workflowId.getOrElse(""),
      isTransferringParticipant = isTransferringParticipant,
      hostedStakeholders = hostedStakeholders,
      transferCounter = transferCounter.toProtoPrimitive,
    )
  }
}

private[store] object SerializableTransferredOut {
  def fromProtoV30(
      transferOutP: v30.TransferredOut
  ): ParsingResult[LedgerSyncEvent.TransferredOut] = {
    val v30.TransferredOut(
      updateIdP,
      optCompletionInfoP,
      submitterP,
      recordTimeP,
      contractIdP,
      contractStakeholdersP,
      sourceDomainIdP,
      targetDomainIdP,
      transferInExclusivityP,
      workflowIdP,
      templateIdP,
      isTransferringParticipant,
      hostedStakeholdersP,
      transferCounterP,
    ) = transferOutP

    for {
      updateId <- ProtoConverter.parseLedgerTransactionId(updateIdP)
      optCompletionInfo <- optCompletionInfoP.traverse(SerializableCompletionInfo.fromProtoV30)
      submitter <- ProtoConverter.parseLfPartyIdO(submitterP)
      recordTime <- required("record_time", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      contractId <- ProtoConverter.parseLfContractId(contractIdP)
      contractStakeholders <- contractStakeholdersP.traverse(ProtoConverter.parseLfPartyId)
      rawSourceDomainId <- DomainId.fromProtoPrimitive(sourceDomainIdP, "source_domain")
      rawTargetDomainId <- DomainId.fromProtoPrimitive(targetDomainIdP, "target_domain")

      transferInExclusivity <- transferInExclusivityP.traverse(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      workflowId <- ProtoConverter.parseLFWorkflowIdO(workflowIdP)
      templateId <- ProtoConverter.parseTemplateIdO(templateIdP)
      hostedStakeholders <- hostedStakeholdersP.traverse(ProtoConverter.parseLfPartyId)
    } yield LedgerSyncEvent.TransferredOut(
      updateId = updateId,
      optCompletionInfo = optCompletionInfo,
      submitter = submitter,
      contractId = contractId,
      templateId = templateId,
      contractStakeholders = contractStakeholders.toSet,
      transferId = TransferId(SourceDomainId(rawSourceDomainId), CantonTimestamp(recordTime)),
      targetDomain = TargetDomainId(rawTargetDomainId),
      transferInExclusivity = transferInExclusivity,
      workflowId = workflowId,
      isTransferringParticipant = isTransferringParticipant,
      hostedStakeholders = hostedStakeholders.toList,
      transferCounter = TransferCounter(transferCounterP),
    )
  }
}

final case class SerializableTransferredIn(transferIn: LedgerSyncEvent.TransferredIn) {
  def toProtoV30: v30.TransferredIn = {
    val LedgerSyncEvent.TransferredIn(
      updateId,
      optCompletionInfo,
      submitter,
      recordTime,
      ledgerCreateTime,
      createNode,
      creatingTransactionId,
      contractMetadata,
      transferOutId,
      targetDomain,
      createTransactionAccepted,
      workflowId,
      isTransferringParticipant,
      hostedStakeholders,
      transferCounter,
    ) = transferIn
    val contractMetadataP = contractMetadata.toByteString
    val createNodeByteString = SerializableLedgerSyncEvent.trySerializeNode(createNode)
    v30.TransferredIn(
      updateId = updateId,
      completionInfo = optCompletionInfo.map(SerializableCompletionInfo(_).toProtoV30),
      submitter = submitter.getOrElse(""),
      recordTime = Some(SerializableLfTimestamp(recordTime).toProtoV30),
      ledgerCreateTime = Some(SerializableLfTimestamp(ledgerCreateTime).toProtoV30),
      contractMetadata = contractMetadataP,
      createNode = createNodeByteString,
      creatingTransactionId = creatingTransactionId,
      transferOutId = Some(transferOutId.toProtoV30),
      targetDomain = targetDomain.toProtoPrimitive,
      createTransactionAccepted = createTransactionAccepted,
      workflowId = workflowId.getOrElse(""),
      isTransferringParticipant = isTransferringParticipant,
      hostedStakeholders = hostedStakeholders,
      transferCounter = transferCounter.toProtoPrimitive,
    )

  }
}

private[store] object SerializableTransferredIn {
  def fromProtoV30(transferInP: v30.TransferredIn): ParsingResult[LedgerSyncEvent.TransferredIn] = {
    val v30.TransferredIn(
      updateIdP,
      optCompletionInfoP,
      submitterP,
      recordTimeP,
      ledgerCreateTimeP,
      createNodeP,
      creatingTransactionIdP,
      contractMetadataP,
      transferOutIdP,
      targetDomainIdP,
      createTransactionAcceptedP,
      workflowIdP,
      isTransferringParticipant,
      hostedStakeholdersP,
      transferCounterP,
    ) = transferInP

    for {
      updateId <- ProtoConverter.parseLedgerTransactionId(updateIdP)
      optCompletionInfo <- optCompletionInfoP.traverse(SerializableCompletionInfo.fromProtoV30)
      submitter <- ProtoConverter.parseLfPartyIdO(submitterP)
      recordTime <- required("record_time", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      ledgerCreateTime <- required("ledger_create_time", ledgerCreateTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      contractMetadata = LfBytes.fromByteString(contractMetadataP)
      transferId <- ProtoConverter.parseRequired(
        TransferId.fromProtoV30,
        "transfer_id",
        transferOutIdP,
      )
      createNode <- SerializableLedgerSyncEvent.deserializeCreateNode("create_node", createNodeP)
      creatingTransactionId <- ProtoConverter.parseLedgerTransactionId(creatingTransactionIdP)
      rawTargetDomainId <- DomainId.fromProtoPrimitive(targetDomainIdP, "target_domain")
      workflowId <- ProtoConverter.parseLFWorkflowIdO(workflowIdP)
      hostedStakeholders <- hostedStakeholdersP.traverse(ProtoConverter.parseLfPartyId)
    } yield LedgerSyncEvent.TransferredIn(
      updateId = updateId,
      optCompletionInfo = optCompletionInfo,
      submitter = submitter,
      recordTime = recordTime,
      ledgerCreateTime = ledgerCreateTime,
      createNode = createNode,
      creatingTransactionId = creatingTransactionId,
      contractMetadata = contractMetadata,
      transferId = transferId,
      targetDomain = TargetDomainId(rawTargetDomainId),
      createTransactionAccepted = createTransactionAcceptedP,
      workflowId = workflowId,
      isTransferringParticipant = isTransferringParticipant,
      hostedStakeholders = hostedStakeholders.toList,
      transferCounter = TransferCounter(transferCounterP),
    )
  }
}

private[store] final case class SerializablePartiesAddedToParticipant(
    partiesAddedToParticipant: LedgerSyncEvent.PartiesAddedToParticipant
) {
  def toProtoV30: v30.PartiesAddedToParticipant = {
    val LedgerSyncEvent.PartiesAddedToParticipant(
      parties,
      participantId,
      recordTime,
      effectiveTime,
    ) =
      partiesAddedToParticipant
    v30.PartiesAddedToParticipant(
      parties.forgetNE.toSeq,
      participantId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      Some(SerializableLfTimestamp(effectiveTime).toProtoV30),
    )
  }
}

private[store] object SerializablePartiesAddedToParticipant {
  def fromProtoV30(
      partyAddedToParticipant: v30.PartiesAddedToParticipant
  ): ParsingResult[LedgerSyncEvent.PartiesAddedToParticipant] = {
    val v30.PartiesAddedToParticipant(partiesP, participantIdP, recordTimeP, effectiveTimeP) =
      partyAddedToParticipant
    for {
      partiesNE <- ProtoConverter.parseRequiredNonEmpty(
        ProtoConverter.parseLfPartyId,
        "parties",
        partiesP,
      )
      participantId <- ProtoConverter.parseLfParticipantId(participantIdP)
      recordTime <- required("recordTime", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      effectiveTime <- required("effectiveTime", effectiveTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
    } yield LedgerSyncEvent.PartiesAddedToParticipant(
      partiesNE.toSet,
      participantId,
      recordTime,
      effectiveTime,
    )
  }
}

private[store] final case class SerializablePartiesRemovedFromParticipant(
    partiesRemovedFromParticipant: LedgerSyncEvent.PartiesRemovedFromParticipant
) {
  def toProtoV30: v30.PartiesRemovedFromParticipant = {
    val LedgerSyncEvent.PartiesRemovedFromParticipant(
      parties,
      participantId,
      recordTime,
      effectiveTime,
    ) =
      partiesRemovedFromParticipant
    v30.PartiesRemovedFromParticipant(
      parties.forgetNE.toSeq,
      participantId,
      Some(SerializableLfTimestamp(recordTime).toProtoV30),
      Some(SerializableLfTimestamp(effectiveTime).toProtoV30),
    )
  }
}

private[store] object SerializablePartiesRemovedFromParticipant {
  def fromProtoV30(
      partyRemovedFromParticipant: v30.PartiesRemovedFromParticipant
  ): ParsingResult[LedgerSyncEvent.PartiesRemovedFromParticipant] = {
    val v30.PartiesRemovedFromParticipant(partiesP, participantIdP, recordTimeP, effectiveTimeP) =
      partyRemovedFromParticipant
    for {
      partiesNE <- ProtoConverter.parseRequiredNonEmpty(
        ProtoConverter.parseLfPartyId,
        "parties",
        partiesP,
      )
      participantId <- ProtoConverter.parseLfParticipantId(participantIdP)
      recordTime <- required("recordTime", recordTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
      effectiveTime <- required("effectiveTime", effectiveTimeP).flatMap(
        SerializableLfTimestamp.fromProtoPrimitive
      )
    } yield LedgerSyncEvent.PartiesRemovedFromParticipant(
      partiesNE.toSet,
      participantId,
      recordTime,
      effectiveTime,
    )
  }
}
