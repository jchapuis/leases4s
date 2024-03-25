package io.github.jchapuis.leases4s.impl

import com.goyeau.kubernetes.client.KubernetesClient
import io.github.jchapuis.leases4s.model.Namespace

private[impl] class KubeApi[F[_]](client: KubernetesClient[F], namespace: Namespace) {
  def leasesApi = client.leases.namespace(namespace)
}
