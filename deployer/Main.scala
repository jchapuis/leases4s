import besom.*
import besom.api.kubernetes.core.v1.inputs.EnvVarArgs
import besom.types.Output
import io.github.jchapuis.leases4s.example.deployer.*

import java.io.File

@main def main = Pulumi.run {
  val serviceUser    = awsAccessFor("uploads")
  val bucket         = serviceUser.flatMap(siteBucketFor)
  val siteUpload     = bucket.flatMap(uploadSiteToBucket(_, File("www")))
  val serviceAccount = leasesAccessServiceAccount("default")
  val service = serviceUser.zip(serviceAccount).flatMap { case (user, account) =>
    serviceDeployment(
      name = "uploads",
      args = ServiceDeploymentArgs(
        image = "localhost/io.github.jchapuis/leases4s-example:local",
        replicas = 3,
        ports = List(80),
        serviceAccount = Some(account),
        env = List(
          EnvVarArgs("AWS_ACCESS_KEY_ID", user.accessKeyId),
          EnvVarArgs("AWS_SECRET_ACCESS_KEY", user.secretKey)
        )
      )
    )
  }

  Stack(
    serviceUser,
    bucket,
    siteUpload,
    serviceAccount,
    service
  ).exports(websiteUrl = bucket.map(_.websiteEndpoint))
}
