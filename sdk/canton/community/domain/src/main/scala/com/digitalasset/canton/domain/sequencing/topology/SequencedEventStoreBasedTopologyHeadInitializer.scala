// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.sequencing.topology

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.store.SequencedEventStore
import com.digitalasset.canton.store.SequencedEventStore.SearchCriterion
import com.digitalasset.canton.topology.client.{
  DomainTopologyClientHeadStateInitializer,
  DomainTopologyClientWithInit,
}
import com.digitalasset.canton.topology.processing.{ApproximateTime, EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{TopologyStore, TopologyStoreId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

/** As not all events observable by the topology processor are topology transactions, updates the topology client's head
  * based on either the sequenced event store or the topology store, depending on which one has a higher timestamp.
  * This becomes useful on restarts.
  */
final class SequencedEventStoreBasedTopologyHeadInitializer(
    sequencedEventStore: SequencedEventStore,
    topologyStore: TopologyStore[TopologyStoreId.DomainStore],
) extends DomainTopologyClientHeadStateInitializer {

  override def initialize(client: DomainTopologyClientWithInit)(implicit
      executionContext: ExecutionContext,
      traceContext: TraceContext,
  ): FutureUnlessShutdown[DomainTopologyClientWithInit] =
    for {
      latestSequencedEvent <- sequencedEventStore
        .find(SearchCriterion.Latest)
        .mapK(FutureUnlessShutdown.outcomeK)
        // If events cannot be found, an error is returned. Translate it into an `Option`.
        .map(Some(_))
        .getOrElse(None)
      maxTopologyStoreTimestamp <- topologyStore.maxTimestamp(
        CantonTimestamp.MaxValue,
        includeRejected = true,
      )
    } yield {
      // Defensively, get the latest possible timestamps or don't update the head.
      val sequencedToEffectiveTimes = List(
        latestSequencedEvent.map(event =>
          (SequencedTime(event.timestamp), EffectiveTime(event.timestamp))
        ),
        maxTopologyStoreTimestamp,
      ).flatten
      val maxTimestampsO = sequencedToEffectiveTimes.maxByOption {
        case (_, effectiveTime: EffectiveTime) => effectiveTime
      }

      maxTimestampsO
        .foreach { case (maxSequencedTime, maxEffectiveTime) =>
          client.updateHead(
            maxSequencedTime,
            maxEffectiveTime,
            ApproximateTime(maxEffectiveTime.value),
            potentialTopologyChange = true,
          )
        }
      client
    }
}
