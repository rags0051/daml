// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.crypto.Signature
import com.digitalasset.canton.domain.metrics.BftOrderingMetrics
import com.digitalasset.canton.domain.sequencing.sequencer.bftordering.v1
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.EpochState.Epoch
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.IssConsensusModule.{
  DefaultLeaderSelectionPolicy,
  InitialState,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.IssConsensusModuleMetrics.{
  emitConsensusLatencyStats,
  emitNonCompliance,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.EpochStore
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.data.Genesis.GenesisEpoch
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.leaders.{
  LeaderSelectionPolicy,
  SimpleLeaderSelectionPolicy,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.statetransfer.StateTransferMessageResult.{
  BlockTransferCompleted,
  NothingToStateTransfer,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.statetransfer.{
  CatchupBehavior,
  CatchupDetector,
  StateTransferManager,
  StateTransferMessageResult,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.validation.IssConsensusValidator
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.modules.{
  HasDelayedInit,
  shortType,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.core.topology.CryptoProvider
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.NumberIdentifiers.{
  EpochLength,
  EpochNumber,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.SignedMessage
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.ordering.{
  OrderedBlock,
  OrderedBlockForOutput,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.snapshot.SequencerSnapshotAdditionalInfo
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.data.topology.{
  Membership,
  OrderingTopology,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.Consensus.ConsensusMessage.{
  PbftUnverifiedNetworkMessage,
  PbftVerifiedNetworkMessage,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.Consensus.NewEpochTopology
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.PbftNetworkMessage.headerFromProto
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.{
  PbftNetworkMessage,
  PbftSignedNetworkMessage,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.dependencies.ConsensusModuleDependencies
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.modules.{
  Consensus,
  ConsensusSegment,
  Output,
}
import com.digitalasset.canton.domain.sequencing.sequencer.block.bftordering.framework.{
  Env,
  ModuleRef,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

@SuppressWarnings(Array("org.wartremover.warts.Var"))
final class IssConsensusModule[E <: Env[E]](
    epochLength: EpochLength, // Currently fixed for all epochs
    initialState: InitialState[E],
    epochStore: EpochStore[E],
    clock: Clock,
    metrics: BftOrderingMetrics,
    segmentModuleRefFactory: SegmentModuleRefFactory[E],
    thisPeer: SequencerId,
    override val dependencies: ConsensusModuleDependencies[E],
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
    futurePbftMessageQueue: mutable.Queue[SignedMessage[PbftNetworkMessage]] = new mutable.Queue(),
    queuedConsensusMessages: Seq[Consensus.Message[E]] = Seq.empty,
)(
    // Only tests pass the state manager as parameter, and it's convenient to have it as an option
    //  to avoid two different constructor calls depending on whether the test want to customize it or not.
    customOnboardingAndServerStateTransferManager: Option[StateTransferManager[E]] = None,
    private var activeMembership: Membership = initialState.membership,
)(
    private var catchupDetector: CatchupDetector = new CatchupDetector(activeMembership)
)(implicit mc: MetricsContext)
    extends Consensus[E]
    with HasDelayedInit[Consensus.ProtocolMessage] {

  // An instance of state transfer manager to be used only once, if onboarding, in a client role and to
  //  be reused as many times as needed in a server role.
  private val onboardingAndServerStateTransferManager =
    customOnboardingAndServerStateTransferManager.getOrElse(
      new StateTransferManager(
        dependencies,
        epochLength,
        epochStore,
        thisPeer,
        loggerFactory,
      )
    )

  private val validator = new IssConsensusValidator[E]

  logger.debug(
    "Starting with " +
      s"membership = ${initialState.membership}, " +
      s"latest completed epoch = ${initialState.latestCompletedEpoch.info}, " +
      s"current epoch = ${initialState.epochState.epoch.info} (completed: ${initialState.epochState.isEpochComplete})"
  )(TraceContext.empty)

  private var latestCompletedEpoch: EpochStore.Epoch = initialState.latestCompletedEpoch

  private var activeCryptoProvider = initialState.cryptoProvider
  private var epochState = initialState.epochState
  @VisibleForTesting
  private[bftordering] def getEpochState: EpochState[E] = epochState

  private var newEpochTopology: Option[(OrderingTopology, CryptoProvider[E])] = None

  override def ready(self: ModuleRef[Consensus.Message[E]]): Unit =
    // TODO(#16761) also resend locally-led ordered blocks (PrePrepare) in activeEpoch in case my node crashed
    queuedConsensusMessages.foreach(self.asyncSend)

  override protected def receiveInternal(message: Consensus.Message[E])(implicit
      context: E#ActorContextT[Consensus.Message[E]],
      traceContext: TraceContext,
  ): Unit =
    message match {

      case Consensus.Init =>
        abortInit(
          s"${PreIssConsensusModule.getClass.getSimpleName} should be the only one receiving ${Consensus.Init.getClass.getSimpleName}"
        )

      case _: Consensus.CatchUpMessage =>
        abortInit(
          s"${CatchupBehavior.getClass.getSimpleName} should be the only one receiving ${Consensus.CatchUpMessage.getClass.getSimpleName}"
        )

      case Consensus.Start =>
        initialState.sequencerSnapshotAdditionalInfo match {
          case Some(snapshotAdditionalInfo)
              if latestCompletedEpoch == GenesisEpoch && activeMembership.otherPeers.sizeIs > 0 =>
            val startEpoch = snapshotAdditionalInfo.peerActiveAt
              .get(activeMembership.myId)
              .flatMap(_.epochNumber)
              .getOrElse(
                abort("No starting epoch found for new node onboarding")
              )
            onboardingAndServerStateTransferManager.startStateTransfer(
              activeMembership,
              latestCompletedEpoch,
              startEpoch,
            )(abort)
          case _ =>
            startSegmentModulesAndCompleteInit()
        }

      case Consensus.Admin.GetOrderingTopology(callback) =>
        callback(epochState.epoch.info.number, activeMembership.orderingTopology.peers)

      case message: Consensus.ProtocolMessage => handleProtocolMessage(message)

      // Not received during state transfer, when consensus is inactive
      case Consensus.NewEpochTopology(
            newEpochNumber,
            newOrderingTopology,
            cryptoProvider: CryptoProvider[E],
          ) =>
        val latestCompletedEpochNumber = latestCompletedEpoch.info.number
        if (latestCompletedEpochNumber == newEpochNumber - 1) {
          val currentEpochNumber = epochState.epoch.info.number
          if (currentEpochNumber == newEpochNumber) {
            // The output module may re-send the topology for the current epoch upon restart if it didn't store
            //  the first block metadata or if the subscribing sequencer runtime hasn't processed it yet.
            logger.debug(
              s"Received NewEpochTopology event for epoch $newEpochNumber, but the epoch has already started; ignoring it"
            )
          } else if (currentEpochNumber == newEpochNumber - 1) {
            // The epoch has been completed and the new one hasn't started yet: start it
            logger.debug(s"Starting new epoch $newEpochNumber from NewEpochTopology event")
            startNewEpoch(newOrderingTopology, cryptoProvider)
          } else {
            abort(
              s"Received NewEpochTopology event for epoch $newEpochNumber, " +
                s"but the current epoch number $currentEpochNumber is neither $newEpochNumber nor ${newEpochNumber - 1}"
            )
          }
        } else if (latestCompletedEpochNumber < newEpochNumber - 1) {
          logger.debug(
            s"Epoch (${newEpochNumber - 1}) has not yet been completed: remembering the topology and " +
              s"waiting for the completed epoch to be stored"
          )
          // Upon epoch completion, a new epoch with this topology will be started.
          newEpochTopology = Some(newOrderingTopology -> cryptoProvider)
        } else { // latestCompletedEpochNumber >= epochNumber
          // The output module re-sent a topology for an already completed epoch; this can happen upon restart if
          //  either the output module, or the subscribing sequencer runtime, or both are more than one epoch behind
          //  consensus, because the output module will just reprocess the blocks to be recovered.
          logger.info(
            s"Received NewEpochTopology for epoch $newEpochNumber, but the latest completed epoch is already $latestCompletedEpochNumber; ignoring"
          )
        }

      case Consensus.NewEpochStored(newEpochInfo, membership, cryptoProvider) =>
        logger.debug(s"Stored new epoch ${newEpochInfo.number}")

        // Reset any topology remembered while waiting for the previous (completed) epoch to be stored.
        newEpochTopology = None

        // Update the topology and start a new epoch.
        activeMembership = membership
        catchupDetector.updateMembership(membership)
        activeCryptoProvider = cryptoProvider

        val newEpoch =
          Epoch(
            newEpochInfo,
            activeMembership,
            DefaultLeaderSelectionPolicy,
          )

        epochState = new EpochState(
          newEpoch,
          clock,
          abort,
          metrics,
          segmentModuleRefFactory = segmentModuleRefFactory(
            context,
            newEpoch,
            activeCryptoProvider,
            latestCompletedEpoch.lastBlockCommitMessages,
            epochInProgress = EpochStore.EpochInProgress(
              completedBlocks = Seq.empty,
              pbftMessagesForIncompleteBlocks = Seq.empty,
            ),
          ),
          completedBlocks = Seq.empty,
          loggerFactory = loggerFactory,
          timeouts = timeouts,
        )

        startSegmentModulesAndCompleteInit()
        logger.debug(
          s"New epoch: ${epochState.epoch.info.number} has started with ordering topology ${activeMembership.orderingTopology}"
        )

        // Process messages for this epoch that may have arrived when processing the previous one.
        //  PBFT messages for a future epoch may become stale after a catch-up, so we need to extract and discard them.
        val queuedPbftMessages =
          futurePbftMessageQueue.dequeueAll(
            _.message.blockMetadata.epochNumber <= epochState.epoch.info.number
          )

        queuedPbftMessages.foreach { pbftMessage =>
          if (pbftMessage.message.blockMetadata.epochNumber == epochState.epoch.info.number)
            processPbftMessage(pbftMessage)
        }
    }

  private def handleProtocolMessage(
      message: Consensus.ProtocolMessage
  )(implicit
      context: E#ActorContextT[Consensus.Message[E]],
      traceContext: TraceContext,
  ): Unit =
    message match {
      case stateTransferMessage: Consensus.StateTransferMessage =>
        val maybeNewEpochState =
          onboardingAndServerStateTransferManager.handleStateTransferMessage(
            stateTransferMessage,
            activeMembership,
            latestCompletedEpoch,
          )(abort)

        maybeNewEpochState match {
          case NothingToStateTransfer =>
            startSegmentModulesAndCompleteInit()
          case BlockTransferCompleted(lastCompletedEpoch, lastCompletedEpochStored) =>
            logger.info(
              s"State transfer: completed last epoch $lastCompletedEpoch, stored epoch info = ${lastCompletedEpochStored.info}, updating"
            )
            this.latestCompletedEpoch = lastCompletedEpochStored
            val currentEpochNumber = epochState.epoch.info.number
            val newEpochNumber = lastCompletedEpoch.info.number
            if (newEpochNumber < currentEpochNumber)
              abort("Should not state transfer to previously completed epoch")
            else if (newEpochNumber > currentEpochNumber) {
              epochState.completeEpoch(epochState.epoch.info.number)
              epochState.close()
              epochState = new EpochState(
                lastCompletedEpoch,
                clock,
                abort,
                metrics,
                segmentModuleRefFactory(
                  context,
                  lastCompletedEpoch,
                  activeCryptoProvider,
                  latestCompletedEpochLastCommits =
                    lastCompletedEpochStored.lastBlockCommitMessages,
                  epochInProgress = EpochStore.EpochInProgress(
                    completedBlocks = Seq.empty,
                    pbftMessagesForIncompleteBlocks = Seq.empty,
                  ),
                ),
                loggerFactory = loggerFactory,
                timeouts = timeouts,
              )
            } // else it is equal, so we don't need to update the state
          case StateTransferMessageResult.Continue =>
        }
      case _ =>
        ifInitCompleted(message) {
          case localAvailabilityMessage: Consensus.LocalAvailability =>
            handleLocalAvailabilityMessage(localAvailabilityMessage)

          case consensusMessage: Consensus.ConsensusMessage =>
            handleConsensusMessage(consensusMessage)

          case _: Consensus.StateTransferMessage => // handled at the top regardless of the init, just to make the match exhaustive
        }
    }

  private def handleLocalAvailabilityMessage(
      localAvailabilityMessage: Consensus.LocalAvailability
  )(implicit traceContext: TraceContext): Unit =
    localAvailabilityMessage match {
      case Consensus.LocalAvailability.ProposalCreated(orderingBlock, epochNumber) =>
        epochState.proposalCreated(orderingBlock, epochNumber)
    }

  private def handleConsensusMessage(
      consensusMessage: Consensus.ConsensusMessage
  )(implicit
      context: E#ActorContextT[Consensus.Message[E]],
      traceContext: TraceContext,
  ): Unit = {
    lazy val messageType = shortType(consensusMessage)

    consensusMessage match {
      case Consensus.ConsensusMessage.PbftVerifiedNetworkMessage(pbftEvent) =>
        processPbftMessage(pbftEvent)

      case Consensus.ConsensusMessage.PbftUnverifiedNetworkMessage(underlyingNetworkMessage) =>
        context.pipeToSelf(
          validator.validate(underlyingNetworkMessage.message, context, activeCryptoProvider)
        ) {
          case Failure(error) =>
            logger.warn(s"Could not verify message $underlyingNetworkMessage, dropping", error)
            emitNonCompliance(metrics)(
              underlyingNetworkMessage.from,
              underlyingNetworkMessage.message.blockMetadata.epochNumber,
              underlyingNetworkMessage.message.viewNumber,
              underlyingNetworkMessage.message.blockMetadata.blockNumber,
              metrics.security.noncompliant.labels.violationType.values.ConsensusInvalidMessage,
            )
            None
          case Success(_) =>
            logger.debug(
              s"Message ${shortType(underlyingNetworkMessage.message)} from ${underlyingNetworkMessage.from} is valid"
            )
            Some(PbftVerifiedNetworkMessage(underlyingNetworkMessage))
        }

      case Consensus.ConsensusMessage.BlockOrdered(
            orderedBlock: OrderedBlock,
            commits,
          ) =>
        emitConsensusLatencyStats(metrics)

        epochState.confirmBlockCompleted(orderedBlock.metadata, commits)

        if (epochState.isEpochComplete)
          completeEpoch()

        // TODO(#16761) - ensure the output module gets and processes the ordered block
        val epochNumber = epochState.epoch.info.number
        val blockNumber = orderedBlock.metadata.blockNumber
        val blockSegment = epochState.epoch.segments
          .find(_.slotNumbers.contains(blockNumber))
          .getOrElse(abort(s"block $blockNumber not part of any segment in epoch $epochNumber"))
        dependencies.output.asyncSend(
          Output.BlockOrdered(
            OrderedBlockForOutput(
              orderedBlock,
              blockSegment.originalLeader,
              blockNumber == epochState.epoch.info.lastBlockNumber,
              OrderedBlockForOutput.Mode.FromConsensus,
            )
          )
        )

      case Consensus.ConsensusMessage.CompleteEpochStored(epoch) =>
        logger.debug(s"$messageType: stored w/ epoch = ${epoch.info.number}")
        val currentEpochNumber = epochState.epoch.info.number

        if (epoch.info.number < currentEpochNumber)
          logger.info(
            s"Epoch ${epoch.info.number} already advanced; current epoch = $currentEpochNumber; ignoring"
          )
        else if (epoch.info.number > currentEpochNumber)
          abort(
            s"Trying to complete future epoch ${epoch.info.number} before local epoch $currentEpochNumber has caught up!"
          )
        else {
          epochState.completeEpoch(epoch.info.number)
          epochState.close()

          latestCompletedEpoch = epoch

          newEpochTopology match {
            case Some((orderingTopology, cryptoProvider)) =>
              startNewEpoch(orderingTopology, cryptoProvider)
            case None =>
              // We don't have the new topology for the new epoch yet: wait for it to arrive from the output module.
              ()
          }
        }

      case Consensus.ConsensusMessage.SegmentCompletedEpoch(segmentFirstBlockNumber, epochNumber) =>
        logger.debug(s"Segment module $segmentFirstBlockNumber completed epoch $epochNumber")

      case Consensus.ConsensusMessage.AsyncException(e: Throwable) =>
        logger.error(s"$messageType: exception raised from async consensus message: ${e.toString}")
    }
  }

  private def startSegmentModulesAndCompleteInit()(implicit
      context: E#ActorContextT[Consensus.Message[E]],
      traceContext: TraceContext,
  ): Unit = {
    if (epochState.epoch.info == GenesisEpoch.info) {
      logger.debug("Started at genesis, self-sending its topology to start epoch 0")
      context.self.asyncSend(
        NewEpochTopology(EpochNumber.First, activeMembership.orderingTopology, activeCryptoProvider)
      )
    } else if (!epochState.isEpochComplete) {
      logger.debug("Started during an in-progress epoch, starting segment modules")
      epochState.startSegmentModules()
    } else {
      logger.debug(
        "Started after a completed epoch but before starting a new one, waiting for topology from the output module"
      )
    }
    initCompleted(handleProtocolMessage(_)) // idempotent
  }

  private def startNewEpoch(
      orderingTopology: OrderingTopology,
      cryptoProvider: CryptoProvider[E],
  )(implicit context: E#ActorContextT[Consensus.Message[E]], traceContext: TraceContext): Unit = {
    metrics.consensus.votes.cleanupVoteGauges(keepOnly = orderingTopology.peers)
    val epochInfo = epochState.epoch.info
    epochState.emitEpochStats(metrics, epochInfo)

    val newEpochInfo = epochInfo.next(epochLength, orderingTopology.activationTime)

    logger.debug(s"Storing new epoch ${newEpochInfo.number}")
    pipeToSelf(epochStore.startEpoch(newEpochInfo)) {
      case Failure(exception) => Consensus.ConsensusMessage.AsyncException(exception)
      case Success(_) =>
        Consensus.NewEpochStored(
          newEpochInfo,
          activeMembership.copy(orderingTopology = orderingTopology),
          cryptoProvider,
        )
    }
  }

  private def processPbftMessage(
      pbftMessage: SignedMessage[ConsensusSegment.ConsensusMessage.PbftNetworkMessage]
  )(implicit context: E#ActorContextT[Consensus.Message[E]], traceContext: TraceContext): Unit = {
    val pbftMessagePayload = pbftMessage.message
    val pbftMessageBlockMetadata = pbftMessagePayload.blockMetadata

    def emitNonComplianceMetric(): Unit =
      emitNonCompliance(metrics)(
        pbftMessagePayload.from,
        pbftMessageBlockMetadata.epochNumber,
        pbftMessagePayload.viewNumber,
        pbftMessageBlockMetadata.blockNumber,
        metrics.security.noncompliant.labels.violationType.values.ConsensusInvalidMessage,
      )

    lazy val messageType = shortType(pbftMessage)
    logger.debug(
      s"$messageType: received from ${pbftMessage.from} w/ metadata $pbftMessageBlockMetadata"
    )

    val pbftMessageEpochNumber = pbftMessageBlockMetadata.epochNumber
    val thisNodeEpochNumber = epochState.epoch.info.number
    val updatedPeerEpoch =
      catchupDetector.updateLatestKnownPeerEpoch(
        pbftMessage.from,
        pbftMessageEpochNumber,
      )

    // Messages from stale epochs are discarded.
    if (pbftMessageEpochNumber < thisNodeEpochNumber) {
      logger.info(
        s"Discarded PBFT message $messageType about block ${pbftMessageBlockMetadata.blockNumber} " +
          s"at epoch $pbftMessageEpochNumber because we're at a later epoch ($thisNodeEpochNumber)"
      )
    } else if (pbftMessageEpochNumber > thisNodeEpochNumber) {
      // Messages from future epoch are queued to be processed when we move to that epoch.
      futurePbftMessageQueue.enqueue(pbftMessage)
      logger.debug(
        s"Queued PBFT message $messageType from future epoch $pbftMessageEpochNumber " +
          s"as we're still in epoch $thisNodeEpochNumber"
      )

      if (
        updatedPeerEpoch &&
        catchupDetector.shouldCatchUp(thisNodeEpochNumber)
      ) {
        logger.debug(
          s"Switching to catch-up state transfer while in epoch $thisNodeEpochNumber; latestCompletedEpoch is "
            + s"${latestCompletedEpoch.info.number} and message epoch is $pbftMessageEpochNumber"
        )
        startCatchUp()
      }
    } else if (
      pbftMessageBlockMetadata.blockNumber < epochState.epoch.info.startBlockNumber || pbftMessageBlockMetadata.blockNumber > epochState.epoch.info.lastBlockNumber
    ) {
      // Messages with blocks numbers out of bounds of the epoch are discarded.
      val epochInfo = epochState.epoch.info
      logger.warn(
        s"Discarded PBFT message $messageType about block ${pbftMessageBlockMetadata.blockNumber}" +
          s"from epoch $pbftMessageEpochNumber (current epoch number = ${epochInfo.number}, " +
          s"first block = ${epochInfo.startBlockNumber}, epoch length = ${epochInfo.length}) " +
          "because the block number is out of bounds of the current epoch"
      )
      emitNonComplianceMetric()
    } else if (!activeMembership.orderingTopology.contains(pbftMessage.from)) {
      // Message is for current epoch but is not from a peer in this epoch's topology
      // TODO(i18194) Check signature that message is from this peer
      logger.warn(
        s"Discarded PBFT message $messageType message from peer ${pbftMessage.from} not in the current epoch's topology"
      )
      emitNonComplianceMetric()
    } else {
      epochState.processPbftMessage(PbftSignedNetworkMessage(pbftMessage))
    }
  }

  private def startCatchUp()(implicit context: E#ActorContextT[Consensus.Message[E]]): Unit =
    context.become(
      new CatchupBehavior(
        epochLength,
        CatchupBehavior
          .InitialState[E](
            activeMembership,
            activeCryptoProvider,
            epochState,
            latestCompletedEpoch,
            futurePbftMessageQueue,
            catchupDetector,
          ),
        epochStore,
        clock,
        metrics,
        segmentModuleRefFactory,
        dependencies,
        loggerFactory,
        timeouts,
      )
    )

  private def completeEpoch()(implicit
      context: E#ActorContextT[Consensus.Message[E]],
      traceContext: TraceContext,
  ): Unit = {
    val epochInfo = epochState.epoch.info
    logger.debug(
      s"Storing completed epoch: ${epochInfo.number}, start block number = ${epochInfo.startBlockNumber}, length = ${epochInfo.length}"
    )
    val epochSnapshot = EpochStore.Epoch(epochInfo, epochState.lastBlockCommitMessages)

    pipeToSelf(epochStore.completeEpoch(epochInfo.number)) {
      case Failure(exception) => Consensus.ConsensusMessage.AsyncException(exception)
      case Success(_) => Consensus.ConsensusMessage.CompleteEpochStored(epochSnapshot)
    }
  }
}

object IssConsensusModule {

  final case class InitialState[E <: Env[E]](
      sequencerSnapshotAdditionalInfo: Option[SequencerSnapshotAdditionalInfo],
      membership: Membership,
      cryptoProvider: CryptoProvider[E],
      epochState: EpochState[E],
      latestCompletedEpoch: EpochStore.Epoch,
  )

  val DefaultEpochLength: EpochLength = EpochLength(10)

  val DefaultDatabaseReadTimeout: FiniteDuration = 10.seconds

  val DefaultLeaderSelectionPolicy: LeaderSelectionPolicy = SimpleLeaderSelectionPolicy

  def parseNetworkMessage(
      from: SequencerId,
      message: v1.ConsensusMessage,
  )(
      originalByteString: ByteString
  ): ParsingResult[ConsensusSegment.ConsensusMessage.PbftNetworkMessage] =
    for {
      header <- headerFromProto(message)
      result <- (message.message match {
        case v1.ConsensusMessage.Message.PrePrepare(value) =>
          ConsensusSegment.ConsensusMessage.PrePrepare.fromProto(
            header.blockMetadata,
            header.viewNumber,
            header.timestamp,
            value,
            from,
          )(originalByteString)
        case v1.ConsensusMessage.Message.Prepare(value) =>
          ConsensusSegment.ConsensusMessage.Prepare.fromProto(
            header.blockMetadata,
            header.viewNumber,
            header.timestamp,
            value,
            from,
          )(originalByteString)
        case v1.ConsensusMessage.Message.Commit(value) =>
          ConsensusSegment.ConsensusMessage.Commit.fromProto(
            header.blockMetadata,
            header.viewNumber,
            header.timestamp,
            value,
            from,
          )(originalByteString)
        case v1.ConsensusMessage.Message.ViewChange(value) =>
          ConsensusSegment.ConsensusMessage.ViewChange.fromProto(
            header.blockMetadata,
            header.viewNumber,
            header.timestamp,
            value,
            from,
          )(originalByteString)
        case v1.ConsensusMessage.Message.NewView(value) =>
          ConsensusSegment.ConsensusMessage.NewView.fromProto(
            header.blockMetadata,
            header.viewNumber,
            header.timestamp,
            value,
            from,
          )(originalByteString)
        case v1.ConsensusMessage.Message.Empty =>
          Left(ProtoDeserializationError.OtherError("Empty Received"))
      }): ParsingResult[ConsensusSegment.ConsensusMessage.PbftNetworkMessage]
    } yield result

  def parseUnverifiedNetworkMessage(
      from: SequencerId,
      message: v1.ConsensusMessage,
  )(
      originalByteString: ByteString
  ): ParsingResult[Consensus.ConsensusMessage.PbftUnverifiedNetworkMessage] =
    parseNetworkMessage(from, message)(originalByteString).map(msg =>
      PbftUnverifiedNetworkMessage(SignedMessage(msg, Signature.noSignature))
    ) // TODO(#20458) Check that all consensus messages are valid

  def parseStateTransferMessage(
      from: SequencerId,
      message: v1.StateTransferMessage,
  )(
      originalByteString: ByteString
  ): ParsingResult[Consensus.StateTransferMessage.StateTransferNetworkMessage] =
    message.message match {
      case v1.StateTransferMessage.Message.BlockRequest(value) =>
        Right(
          Consensus.StateTransferMessage.BlockTransferRequest.fromProto(from, value)(
            originalByteString
          )
        )
      case v1.StateTransferMessage.Message.BlockResponse(value) =>
        Consensus.StateTransferMessage.BlockTransferResponse.fromProto(from, value)(
          originalByteString
        )
      case v1.StateTransferMessage.Message.Empty =>
        Left(ProtoDeserializationError.OtherError("Empty Received"))
    }
}
