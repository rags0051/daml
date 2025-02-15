// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.syntax.alternative.*
import cats.syntax.parallel.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.protocol.submission.UsableDomain.DomainNotUsedReason
import com.digitalasset.canton.protocol.{LfLanguageVersion, LfVersionedTransaction}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.{HashingSchemeVersion, ProtocolVersion}
import com.digitalasset.daml.lf.data.Ref.{PackageId, Party}
import com.digitalasset.daml.lf.engine.Blinding

import scala.concurrent.ExecutionContext

private[submission] class DomainsFilter(
    requiredPackagesPerParty: Map[Party, Set[PackageId]],
    domains: List[(DomainId, ProtocolVersion, TopologySnapshot)],
    transactionVersion: LfLanguageVersion,
    ledgerTime: CantonTimestamp,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, traceContext: TraceContext)
    extends NamedLogging {
  def split: FutureUnlessShutdown[(List[DomainNotUsedReason], List[DomainId])] = domains
    .parTraverse { case (domainId, protocolVersion, snapshot) =>
      UsableDomain
        .check(
          domainId,
          protocolVersion,
          snapshot,
          requiredPackagesPerParty,
          transactionVersion,
          ledgerTime,
          // TODO(i20688): use ISV to select domain
          Option.empty[HashingSchemeVersion],
        )
        .map(_ => domainId)
        .value
    }
    .map(_.separate)
}

private[submission] object DomainsFilter {
  def apply(
      submittedTransaction: LfVersionedTransaction,
      ledgerTime: CantonTimestamp,
      domains: List[(DomainId, ProtocolVersion, TopologySnapshot)],
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext) = new DomainsFilter(
    Blinding.partyPackages(submittedTransaction),
    domains,
    submittedTransaction.version,
    ledgerTime,
    loggerFactory,
  )
}
