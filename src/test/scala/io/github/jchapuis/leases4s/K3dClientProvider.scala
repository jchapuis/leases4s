package io.github.jchapuis.leases4s

import cats.effect.{IO, Resource}
import com.goyeau.kubernetes.client.{KubeConfig, KubernetesClient}
import fs2.io.file.Path
import io.github.jchapuis.leases4s.model.Namespace
import org.typelevel.log4cats.Logger

trait K3dClientProvider {
  implicit def logger: Logger[IO]
  implicit val namespace: Namespace = Namespace.Default

  lazy val kubeConfig: IO[KubeConfig[IO]] = KubeConfig.fromFile[IO](
    Path(s"${System.getProperty("user.home")}/.kube/config"),
    sys.env.getOrElse("KUBE_CONTEXT_NAME", "k3d-local")
  )

  lazy val client: Resource[IO, KubernetesClient[IO]] = KubernetesClient[IO](kubeConfig)

  def withKubeClient[A](test: KubernetesClient[IO] => IO[A]): IO[A] = client.use(test)
}
