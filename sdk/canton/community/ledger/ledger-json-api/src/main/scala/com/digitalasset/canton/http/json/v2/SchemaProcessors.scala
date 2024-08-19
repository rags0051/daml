// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.http.json.v2

import com.daml.ledger.api.v2.value
import com.daml.ledger.api.v2.value.Value
import com.digitalasset.canton.caching.CaffeineCache
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.IdString
import com.digitalasset.daml.lf.language.Ast
import com.digitalasset.transcode.codec.json.JsonCodec
import com.digitalasset.transcode.codec.proto.GrpcValueCodec
import com.digitalasset.transcode.daml_lf.{Dictionary, SchemaEntity, SchemaProcessor}
import com.digitalasset.transcode.schema.SchemaVisitor
import com.digitalasset.transcode.{Codec, Converter}
import com.github.benmanes.caffeine.cache.Caffeine

import scala.concurrent.{ExecutionContext, Future}

class SchemaProcessors(
    val fetchSignatures: Option[String] => Future[Map[Ref.PackageId, Ast.PackageSignature]]
)(implicit
    val executionContext: ExecutionContext
) {

  private val cache = CaffeineCache[String, Map[Ref.PackageId, Ast.PackageSignature]](
    Caffeine
      .newBuilder()
      .maximumSize(SchemaProcessorsCache.MaxCacheSize),
    None,
  )

  private def ensurePackage(
      packageId: Ref.PackageId,
      token: Option[String],
  ): Future[Map[Ref.PackageId, Ast.PackageSignature]] = {
    val tokenKey = token.getOrElse("")
    val signatures = cache.getIfPresent(tokenKey)
    signatures.fold {
      // TODO(i20707) use the new Ledger API's package metadata view
      fetchSignatures(token).map { fetched =>
        cache.put(tokenKey, fetched)
        fetched
      }
    } { signatures =>
      if (signatures.contains(packageId)) {
        Future(signatures)
      } else {
        fetchSignatures(token).map { newSignatures =>
          cache.put(tokenKey, newSignatures)
          newSignatures
        }
      }
    }
  }

  private def prepareToProto(
      packageId: Ref.PackageId,
      token: Option[String],
  ): Future[Dictionary[Converter[ujson.Value, Value]]] =
    ensurePackage(packageId, token).map { signatures =>
      val visitor: SchemaVisitor { type Type = (Codec[ujson.Value], Codec[value.Value]) } =
        SchemaVisitor.compose(new JsonCodec(), GrpcValueCodec)
      val collector =
        Dictionary.collect[Converter[ujson.Value, value.Value]] compose SchemaEntity
          .map((v: visitor.Type) => Converter(v._1, v._2))

      SchemaProcessor
        .process(packages = signatures)(visitor)(
          collector
        )
        .fold(error => throw new IllegalStateException(error), identity)
    }

  private def prepareToJson(
      packageId: Ref.PackageId,
      token: Option[String],
  ): Future[Dictionary[Converter[Value, ujson.Value]]] =
    ensurePackage(packageId, token).map { signatures =>
      val visitor: SchemaVisitor { type Type = (Codec[value.Value], Codec[ujson.Value]) } =
        SchemaVisitor.compose(GrpcValueCodec, new JsonCodec())
      val collector =
        Dictionary.collect[Converter[value.Value, ujson.Value]] compose SchemaEntity
          .map((v: visitor.Type) => Converter(v._1, v._2))

      SchemaProcessor
        .process(packages = signatures)(visitor)(
          collector
        )
        .fold(error => throw new IllegalStateException(error), identity)
    }

  def convertGrpcToJson(templateId: Ref.Identifier, proto: value.Value)(
      token: Option[String]
  ): Future[ujson.Value] =
    prepareToJson(templateId.packageId, token).map(_.templates(templateId).convert(proto))

  def contractArgFromJsonToProto(
      templateId: Ref.Identifier,
      jsonArgsValue: ujson.Value,
  )(token: Option[String]): Future[value.Value] =
    prepareToProto(templateId.packageId, token).map(_.templates(templateId).convert(jsonArgsValue))

  def choiceArgsFromJsonToProto(
      templateId: Ref.Identifier,
      choiceName: IdString.Name,
      jsonArgsValue: ujson.Value,
  )(token: Option[String]): Future[value.Value] =
    prepareToProto(templateId.packageId, token).map(
      _.choiceArguments((templateId, choiceName)).convert(jsonArgsValue)
    )

  def contractArgFromProtoToJson(
      templateId: Ref.Identifier,
      protoArgs: value.Record,
  )(token: Option[String]): Future[ujson.Value] =
    convertGrpcToJson(templateId, value.Value(value.Value.Sum.Record(protoArgs)))(token)

  def choiceArgsFromProtoToJson(
      templateId: Ref.Identifier,
      choiceName: IdString.Name,
      protoArgs: value.Value,
  )(token: Option[String]) = prepareToJson(templateId.packageId, token).map(
    _.choiceArguments((templateId, choiceName)).convert(protoArgs)
  )

  def keyArgFromProtoToJson(
      templateId: Ref.Identifier,
      protoArgs: value.Value,
  )(token: Option[String]): Future[ujson.Value] =
    prepareToJson(templateId.packageId, token).map(_.templates(templateId).convert(protoArgs))

  def keyArgFromJsonToProto(
      templateId: Ref.Identifier,
      protoArgs: ujson.Value,
  )(token: Option[String]): Future[value.Value] =
    prepareToProto(templateId.packageId, token).map(_.templates(templateId).convert(protoArgs))

  def exerciseResultFromProtoToJson(
      lfIdentifier: Ref.Identifier,
      choiceName: IdString.Name,
      v: value.Value,
  )(token: Option[String]) = prepareToJson(lfIdentifier.packageId, token).map(
    _.choiceArguments((lfIdentifier, choiceName)).convert(v)
  )

  def exerciseResultFromJsonToProto(
      lfIdentifier: Ref.Identifier,
      choiceName: IdString.Name,
      value: ujson.Value,
  )(token: Option[String]): Future[Option[Value]] = value match {
    case ujson.Null => Future(None)
    case _ =>
      prepareToProto(lfIdentifier.packageId, token)
        .map(_.choiceArguments((lfIdentifier, choiceName)).convert(value))
        .map(Some(_))
  }

}

object SchemaProcessorsCache {
  val MaxCacheSize: Long = 100
}
