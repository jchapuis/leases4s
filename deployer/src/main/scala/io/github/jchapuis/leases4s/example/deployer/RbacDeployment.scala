package io.github.jchapuis.leases4s.example.deployer

import besom.api.kubernetes.rbac.v1.inputs.{PolicyRuleArgs, RoleRefArgs, SubjectArgs}
import besom.api.kubernetes.rbac.v1.{Role, RoleArgs, RoleBinding, RoleBindingArgs}
import besom.{Context, Output}
import besom.*
import besom.api.kubernetes.core.v1.ServiceAccount
import besom.api.kubernetes.meta.v1.inputs.ObjectMetaArgs

def leasesAccessServiceAccount(namespace: String)(using Context): Output[String] =
  for {
    serviceAccountName <- serviceAccount.flatMap(_.metadata).map(_.name.get)
    roleName           <- role.flatMap(_.metadata).map(_.name.get)
    _                  <- roleBinding(roleName, serviceAccountName, namespace)
  } yield serviceAccountName

private val leaseAccess: NonEmptyString   = "lease-access"
private def serviceAccount(using Context) = ServiceAccount(leaseAccess)

private def role(using Context) = Role(
  leaseAccess,
  RoleArgs(rules =
    Some(
      List(
        PolicyRuleArgs(
          apiGroups = Some(List("coordination.k8s.io")),
          resources = Some(List("leases")),
          verbs = List("get", "list", "watch", "create", "update", "patch", "delete")
        )
      )
    )
  )
)

private def roleBinding(roleName: String, serviceAccountName: String, namespace: String)(using Context) = RoleBinding(
  leaseAccess,
  RoleBindingArgs(
    roleRef = RoleRefArgs(apiGroup = "rbac.authorization.k8s.io", kind = "Role", name = roleName),
    subjects = List(SubjectArgs(kind = "ServiceAccount", name = serviceAccountName, namespace))
  )
)
