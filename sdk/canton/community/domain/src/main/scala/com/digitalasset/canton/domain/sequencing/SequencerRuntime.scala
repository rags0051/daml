// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing

import cats.data.EitherT
import cats.syntax.foldable.*
import cats.syntax.parallel.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  DomainTimeTrackerConfig,
  ProcessingTimeout,
  TestingConfigInternal,
}
import com.digitalasset.canton.crypto.DomainSyncCryptoClient
import com.digitalasset.canton.domain.api.v30
import com.digitalasset.canton.domain.config.PublicServerConfig
import com.digitalasset.canton.domain.metrics.SequencerMetrics
import com.digitalasset.canton.domain.sequencing.authentication.grpc.{
  SequencerAuthenticationServerInterceptor,
  SequencerConnectServerInterceptor,
}
import com.digitalasset.canton.domain.sequencing.authentication.{
  MemberAuthenticationService,
  MemberAuthenticationServiceFactory,
  MemberAuthenticationStore,
}
import com.digitalasset.canton.domain.sequencing.config.SequencerNodeParameters
import com.digitalasset.canton.domain.sequencing.sequencer.*
import com.digitalasset.canton.domain.sequencing.sequencer.errors.RegisterMemberError.AlreadyRegisteredError
import com.digitalasset.canton.domain.sequencing.sequencer.errors.{
  OperationError,
  RegisterMemberError,
  SequencerWriteError,
}
import com.digitalasset.canton.domain.sequencing.service.*
import com.digitalasset.canton.health.HealthListener
import com.digitalasset.canton.health.admin.data.{SequencerHealthStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{
  FlagCloseable,
  FutureUnlessShutdown,
  HasCloseContext,
  Lifecycle,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.DomainParametersLookup.SequencerDomainParameters
import com.digitalasset.canton.protocol.messages.DefaultOpenEnvelope
import com.digitalasset.canton.protocol.{
  DomainParametersLookup,
  DynamicDomainParametersLookup,
  StaticDomainParameters,
}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencer.admin.v30.{
  SequencerAdministrationServiceGrpc,
  SequencerVersionServiceGrpc,
}
import com.digitalasset.canton.sequencing.client.SequencerClient.SequencerTransports
import com.digitalasset.canton.sequencing.client.{
  RequestSigner,
  SendTracker,
  SequencedEventValidatorFactory,
  SequencerClient,
  SequencerClientImplPekko,
}
import com.digitalasset.canton.sequencing.handlers.{
  DiscardIgnoredEvents,
  EnvelopeOpener,
  StripSignature,
}
import com.digitalasset.canton.sequencing.protocol.SequencedEvent
import com.digitalasset.canton.sequencing.{
  BoxedEnvelope,
  HandlerResult,
  SubscriptionStart,
  UnsignedEnvelopeBox,
  UnsignedProtocolEventHandler,
}
import com.digitalasset.canton.store.{
  IndexedDomain,
  SendTrackerStore,
  SequencedEventStore,
  SequencerCounterTrackerStore,
}
import com.digitalasset.canton.time.{Clock, DomainTimeTracker}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.client.DomainTopologyClientWithInit
import com.digitalasset.canton.topology.processing.TopologyTransactionProcessor
import com.digitalasset.canton.topology.store.TopologyStoreId.DomainStore
import com.digitalasset.canton.topology.store.{TopologyStateForInitializationService, TopologyStore}
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.traffic.TrafficControlProcessor
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.FutureUtil
import com.digitalasset.canton.{DiscardOps, config}
import io.grpc.{ServerInterceptors, ServerServiceDefinition}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future, Promise}

final case class SequencerAuthenticationConfig(
    nonceExpirationInterval: config.NonNegativeFiniteDuration,
    maxTokenExpirationInterval: config.NonNegativeFiniteDuration,
) {
  // only authentication tokens are supported
  val check: AuthenticationCheck = AuthenticationCheck.AuthenticationToken
}

object SequencerAuthenticationConfig {
  val Disabled: Option[SequencerAuthenticationConfig] = None
}

/** Run a sequencer and its supporting services.
  *
  * @param authenticationConfig   Authentication setup if supported, otherwise none.
  * @param staticDomainParameters The set of members to register on startup statically.
  *
  * Creates a sequencer client and connect it to a topology client
  * to power sequencer authentication.
  */
class SequencerRuntime(
    sequencerId: SequencerId,
    val sequencer: Sequencer,
    staticDomainParameters: StaticDomainParameters,
    localNodeParameters: SequencerNodeParameters,
    publicServerConfig: PublicServerConfig,
    timeTrackerConfig: DomainTimeTrackerConfig,
    testingConfig: TestingConfigInternal,
    val metrics: SequencerMetrics,
    indexedDomain: IndexedDomain,
    syncCrypto: DomainSyncCryptoClient,
    topologyStore: TopologyStore[DomainStore],
    topologyClient: DomainTopologyClientWithInit,
    topologyProcessor: TopologyTransactionProcessor,
    topologyManagerStatusO: Option[TopologyManagerStatus],
    initializationEffective: Future[Unit],
    initializedAtHead: => Future[Boolean],
    storage: Storage,
    clock: Clock,
    authenticationConfig: SequencerAuthenticationConfig,
    additionalAdminServiceFactory: Sequencer => Option[ServerServiceDefinition],
    staticMembersToRegister: Seq[Member],
    futureSupervisor: FutureSupervisor,
    memberAuthenticationServiceFactory: MemberAuthenticationServiceFactory,
    topologyStateForInitializationService: TopologyStateForInitializationService,
    maybeDomainOutboxFactory: Option[DomainOutboxFactorySingleCreate],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    executionContext: ExecutionContext,
    tracer: Tracer,
    actorSystem: ActorSystem,
    traceContext: TraceContext,
) extends FlagCloseable
    with HasCloseContext
    with NamedLogging {

  override protected def timeouts: ProcessingTimeout = localNodeParameters.processingTimeouts

  private val isTopologyInitializedPromise = Promise[Unit]()

  def domainId: DomainId = indexedDomain.domainId

  def initialize(
      topologyInitIsCompleted: Boolean = true
  )(implicit traceContext: TraceContext): EitherT[Future, String, Unit] = {
    def keyCheckET =
      EitherT {
        val snapshot = syncCrypto
          .currentSnapshotApproximation(TraceContext.empty)
          .ipsSnapshot
        snapshot
          .signingKey(sequencerId)
          .map { keyO =>
            Either.cond(keyO.nonEmpty, (), s"Missing sequencer keys at ${snapshot.referenceTime}.")
          }
      }

    def registerInitialMembers = {
      logger.debug(s"Registering initial sequencer members: $staticMembersToRegister")
      // only register the sequencer itself if we have remote sequencers that will necessitate topology transactions
      // being sent to them
      staticMembersToRegister
        .parTraverse(
          sequencer
            .ensureRegistered(_)
            .leftFlatMap[Unit, SequencerWriteError[RegisterMemberError]] {
              // if other sibling sequencers are initializing at the same time and try to run this step,
              // or if a sequencer automatically registers the idm (eg the Ethereum sequencer)
              // we might get AlreadyRegisteredError which we can safely ignore
              case OperationError(_: AlreadyRegisteredError) => EitherT.pure(())
              case otherError => EitherT.leftT(otherError)
            }
        )
    }

    for {
      _ <- keyCheckET
      _ <- registerInitialMembers.leftMap(_.toString)
    } yield {
      // if we run embedded, we complete the future here
      if (topologyInitIsCompleted) {
        isTopologyInitializedPromise.success(())
      }
    }
  }

  protected val sequencerDomainParamsLookup
      : DynamicDomainParametersLookup[SequencerDomainParameters] =
    DomainParametersLookup.forSequencerDomainParameters(
      staticDomainParameters,
      publicServerConfig.overrideMaxRequestSize,
      topologyClient,
      futureSupervisor,
      loggerFactory,
    )

  private val sequencerService = GrpcSequencerService(
    sequencer,
    metrics,
    authenticationConfig.check,
    clock,
    sequencerDomainParamsLookup,
    localNodeParameters,
    staticDomainParameters.protocolVersion,
    topologyStateForInitializationService,
    loggerFactory,
  )

  sequencer
    .registerOnHealthChange(new HealthListener {
      override def name: String = "SequencerRuntime"

      override def poke()(implicit traceContext: TraceContext): Unit = {
        val status = sequencer.getState
        if (!status.isActive && !isClosing) {
          logger.warn(
            s"Sequencer is unhealthy, so disconnecting all members. ${status.details.getOrElse("")}"
          )
          // Run into a Future because closing subscriptions can take time, especially when having DB connection issues
          FutureUtil.doNotAwait(
            Future(sequencerService.disconnectAllMembers()),
            "Failed to disconnect members",
          )
        } else {
          logger.info(s"Sequencer is healthy")
        }
      }
    })
    .discard[Boolean]

  private case class AuthenticationServices(
      memberAuthenticationService: MemberAuthenticationService,
      sequencerAuthenticationService: GrpcSequencerAuthenticationService,
      authenticationInterceptor: SequencerAuthenticationServerInterceptor,
  )

  private val authenticationServices = {
    val authenticationService = memberAuthenticationServiceFactory.createAndSubscribe(
      syncCrypto,
      MemberAuthenticationStore(storage, timeouts, loggerFactory, closeContext),
      // closing the subscription when the token expires will force the client to try to reconnect
      // immediately and notice it is unauthenticated, which will cause it to also start reauthenticating
      // it's important to disconnect the member AFTER we expired the token, as otherwise, the member
      // can still re-subscribe with the token just before we removed it
      Traced.lift(sequencerService.disconnectMember(_)(_)),
      isTopologyInitializedPromise.future,
    )

    val sequencerAuthenticationService =
      new GrpcSequencerAuthenticationService(
        authenticationService,
        staticDomainParameters.protocolVersion,
        loggerFactory,
      )

    val sequencerAuthInterceptor =
      new SequencerAuthenticationServerInterceptor(authenticationService, loggerFactory)

    AuthenticationServices(
      authenticationService,
      sequencerAuthenticationService,
      sequencerAuthInterceptor,
    )
  }

  def health: Future[SequencerHealthStatus] =
    Future.successful(sequencer.getState)

  def topologyQueue: TopologyQueueStatus = TopologyQueueStatus(
    manager = topologyManagerStatusO.map(_.queueSize).getOrElse(0),
    dispatcher = domainOutboxO.map(_.queueSize).getOrElse(0),
    clients = topologyClient.numPendingChanges,
  )

  def fetchActiveMembers(): Future[Seq[Member]] =
    Future.successful(sequencerService.membersWithActiveSubscriptions)

  def registerAdminGrpcServices(
      register: ServerServiceDefinition => Unit
  ): Unit = {
    register(
      SequencerVersionServiceGrpc
        .bindService(
          new GrpcSequencerVersionService(staticDomainParameters.protocolVersion, loggerFactory),
          executionContext,
        )
    )
    // hook for registering enterprise administration service if in an appropriate environment
    additionalAdminServiceFactory(sequencer).foreach(register)
    sequencer.adminServices.foreach(register)
    register(
      SequencerAdministrationServiceGrpc.bindService(
        sequencerAdministrationService,
        executionContext,
      )
    )
  }

  def domainServices(implicit ec: ExecutionContext): Seq[ServerServiceDefinition] = Seq(
    {
      ServerInterceptors.intercept(
        v30.SequencerConnectServiceGrpc.bindService(
          new GrpcSequencerConnectService(
            domainId,
            sequencerId,
            staticDomainParameters,
            syncCrypto,
            loggerFactory,
          )(
            ec
          ),
          executionContext,
        ),
        new SequencerConnectServerInterceptor(loggerFactory),
      )
    }, {
      SequencerVersionServiceGrpc.bindService(
        new GrpcSequencerVersionService(staticDomainParameters.protocolVersion, loggerFactory),
        ec,
      )
    }, {
      v30.SequencerAuthenticationServiceGrpc
        .bindService(authenticationServices.sequencerAuthenticationService, ec)
    }, {
      import scala.jdk.CollectionConverters.*

      // use the auth service interceptor if available
      val interceptors = List(authenticationServices.authenticationInterceptor).asJava

      ServerInterceptors.intercept(
        v30.SequencerServiceGrpc.bindService(sequencerService, ec),
        interceptors,
      )
    },
  )

  private val sequencedEventStore =
    SequencedEventStore(
      storage,
      indexedDomain,
      staticDomainParameters.protocolVersion,
      timeouts,
      loggerFactory,
    )

  private val client: SequencerClient =
    new SequencerClientImplPekko[DirectSequencerClientTransport.SubscriptionError](
      domainId,
      sequencerId,
      SequencerTransports.default(
        sequencerId,
        new DirectSequencerClientTransport(
          sequencer,
          localNodeParameters.processingTimeouts,
          loggerFactory,
        ),
      ),
      localNodeParameters.sequencerClient,
      testingConfig,
      staticDomainParameters.protocolVersion,
      sequencerDomainParamsLookup,
      localNodeParameters.processingTimeouts,
      // Since the sequencer runtime trusts itself, there is no point in validating the events.
      SequencedEventValidatorFactory.noValidation(domainId, warn = false),
      clock,
      RequestSigner(syncCrypto, staticDomainParameters.protocolVersion),
      sequencedEventStore,
      new SendTracker(
        Map(),
        SendTrackerStore(storage),
        metrics.sequencerClient,
        loggerFactory,
        timeouts,
      ),
      metrics.sequencerClient,
      None,
      replayEnabled = false,
      syncCrypto.pureCrypto,
      localNodeParameters.loggingConfig,
      loggerFactory,
      futureSupervisor,
      sequencer.firstSequencerCounterServeableForSequencer,
    )
  private val timeTracker = DomainTimeTracker(
    timeTrackerConfig,
    clock,
    client,
    staticDomainParameters.protocolVersion,
    timeouts,
    loggerFactory,
  )

  private val topologyManagerSequencerCounterTrackerStore =
    SequencerCounterTrackerStore(
      storage,
      indexedDomain,
      timeouts,
      loggerFactory,
    )

  private lazy val domainOutboxO: Option[DomainOutboxHandle] =
    maybeDomainOutboxFactory
      .map(
        _.createOnlyOnce(
          staticDomainParameters.protocolVersion,
          topologyClient,
          client,
          clock,
          loggerFactory,
        )
      )

  private val topologyHandler = topologyProcessor.createHandler(domainId)
  private val trafficProcessor =
    new TrafficControlProcessor(
      syncCrypto,
      domainId,
      sequencer.rateLimitManager.flatMap(_.balanceKnownUntil),
      loggerFactory,
    )

  sequencer.rateLimitManager.foreach(_.balanceUpdateSubscriber.foreach(trafficProcessor.subscribe))

  // TODO(i17434): Use topologyHandler.combineWith(trafficProcessorHandler)
  private def handler(domainId: DomainId): UnsignedProtocolEventHandler =
    new UnsignedProtocolEventHandler {
      override def name: String = s"sequencer-runtime-$domainId"

      override def subscriptionStartsAt(
          start: SubscriptionStart,
          domainTimeTracker: DomainTimeTracker,
      )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] =
        Seq(
          topologyProcessor.subscriptionStartsAt(start, domainTimeTracker),
          trafficProcessor.subscriptionStartsAt(start, domainTimeTracker),
        ).sequence_

      override def apply(
          tracedEvents: BoxedEnvelope[UnsignedEnvelopeBox, DefaultOpenEnvelope]
      ): HandlerResult =
        tracedEvents.withTraceContext { implicit traceContext => events =>
          NonEmpty.from(events).fold(HandlerResult.done)(handle)
        }

      private def handle(tracedEvents: NonEmpty[Seq[Traced[SequencedEvent[DefaultOpenEnvelope]]]])(
          implicit traceContext: TraceContext
      ): HandlerResult = {
        for {
          topology <- topologyHandler(Traced(tracedEvents))
          _ <- trafficProcessor.handle(tracedEvents)
        } yield topology
      }
    }

  private val eventHandler = StripSignature(handler(domainId))

  private val sequencerAdministrationService =
    new GrpcSequencerAdministrationService(
      sequencer,
      client,
      topologyStore,
      topologyClient,
      timeTracker,
      staticDomainParameters,
      loggerFactory,
    )

  def initializeAll()(implicit traceContext: TraceContext): EitherT[Future, String, Unit] = {
    for {
      _ <- initialize(topologyInitIsCompleted = false)
      _ = logger.debug("Subscribing topology client within sequencer runtime")
      _ <- EitherT.right(
        client.subscribeTracking(
          topologyManagerSequencerCounterTrackerStore,
          DiscardIgnoredEvents(loggerFactory) {
            EnvelopeOpener(staticDomainParameters.protocolVersion, syncCrypto.pureCrypto)(
              eventHandler
            )
          },
          timeTracker,
        )
      )
      _ <- domainOutboxO
        .map(_.startup().onShutdown(Right(())))
        .getOrElse(EitherT.rightT[Future, String](()))
    } yield {
      import scala.util.chaining.*
      // if we're a separate sequencer node assume we should wait for our local topology client to observe
      // the required topology transactions to at least authorize the domain members
      initializationEffective
        .tap(_ => isTopologyInitializedPromise.success(()))
        .discard // we unlock the waiting future in any case
    }
  }

  override def onClosed(): Unit = {
    Lifecycle.close(
      Lifecycle.toCloseableOption(sequencer.rateLimitManager),
      timeTracker,
      syncCrypto,
      topologyClient,
      client,
      topologyProcessor,
      topologyManagerSequencerCounterTrackerStore,
      sequencedEventStore,
      syncCrypto,
      topologyClient,
      sequencerService,
      authenticationServices.memberAuthenticationService,
      sequencer,
    )(logger)
  }
}
