// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.fetchcontracts.util

import com.digitalasset.daml.lf
import com.daml.ledger.api.v2 as lav2
import com.digitalasset.daml.lf.data.Ref.{DottedName, ModuleName, PackageId, QualifiedName}
import com.digitalasset.canton.http.domain.ContractTypeId

object IdentifierConverters {
  def apiIdentifier(a: lf.data.Ref.Identifier): lav2.value.Identifier =
    lav2.value.Identifier(
      packageId = a.packageId,
      moduleName = a.qualifiedName.module.dottedName,
      entityName = a.qualifiedName.name.dottedName,
    )

  def lfIdentifier(a: com.daml.ledger.api.v2.value.Identifier): lf.data.Ref.Identifier =
    lf.data.Ref.Identifier(
      packageId = PackageId.assertFromString(a.packageId),
      qualifiedName = QualifiedName(
        module = ModuleName.assertFromString(a.moduleName),
        name = DottedName.assertFromString(a.entityName),
      ),
    )

  def apiIdentifier(a: ContractTypeId.RequiredPkg): lav2.value.Identifier =
    lav2.value.Identifier(
      packageId = a.packageId,
      moduleName = a.moduleName,
      entityName = a.entityName,
    )
}
