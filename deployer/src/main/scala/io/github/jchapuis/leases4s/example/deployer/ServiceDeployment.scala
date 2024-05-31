package io.github.jchapuis.leases4s.example.deployer

import besom.*
import besom.api.kubernetes
import besom.api.kubernetes.apps.v1.inputs.*
import besom.api.kubernetes.apps.v1.{Deployment, DeploymentArgs}
import besom.api.kubernetes.core.v1.enums.ServiceSpecType
import besom.api.kubernetes.core.v1.inputs.*
import besom.api.kubernetes.core.v1.{Service, ServiceArgs}
import besom.api.kubernetes.meta.v1.*
import besom.api.kubernetes.meta.v1.inputs.*
import besom.api.kubernetes.networking.v1.inputs.*
import besom.api.kubernetes.networking.v1.{Ingress, IngressArgs}

case class ServiceDeploymentArgs(
    image: String,
    replicas: Int = 1,
    ports: List[Int] = List.empty,
    serviceAccount: Option[String] = None,
    env: List[EnvVarArgs] = List.empty
)

case class ServiceDeployment(service: Output[Service], deployment: Output[Deployment])(using ComponentBase)
    extends ComponentResource derives RegistersOutputs

def serviceDeployment(using Context)(
    name: NonEmptyString,
    args: ServiceDeploymentArgs,
    componentResourceOptions: ComponentResourceOptions = ComponentResourceOptions()
): Output[ServiceDeployment] =
  component(name, "k8sx:service:ServiceDeployment", componentResourceOptions) {
    val labels          = Map("app" -> name)
    val deploymentPorts = args.ports.map(port => ContainerPortArgs(containerPort = port))

    val container = ContainerArgs(
      name = name,
      image = args.image,
      resources = ResourceRequirementsArgs(
        requests = Map(
          "cpu"    -> "100m",
          "memory" -> "100Mi"
        )
      ),
      env = args.env,
      ports = deploymentPorts
    )

    val deployment: Output[Deployment] = Deployment(
      name,
      DeploymentArgs(
        spec = DeploymentSpecArgs(
          selector = LabelSelectorArgs(matchLabels = labels),
          replicas = args.replicas,
          template = PodTemplateSpecArgs(
            metadata = ObjectMetaArgs(labels = labels),
            spec = PodSpecArgs(containers = List(container), serviceAccountName = args.serviceAccount)
          )
        )
      )
    )

    val servicePorts = args.ports.map(port => ServicePortArgs(port = port, targetPort = port))

    val service = Service(
      name,
      ServiceArgs(
        metadata = ObjectMetaArgs(labels = labels, name = name),
        spec = ServiceSpecArgs(`type` = ServiceSpecType.ClusterIP, ports = servicePorts, selector = labels)
      )
    )

    val ingresses = args.ports.traverse(port => ingressFor(name, port))

    ServiceDeployment(service, deployment)
  }

private def ingressFor(name: NonEmptyString, port: Int)(using Context): Output[Ingress] = Ingress(
  name,
  IngressArgs(
    metadata = ObjectMetaArgs(annotations = Map("ingress.kubernetes.io/ssl-redirect" -> "false")),
    spec = IngressSpecArgs(rules =
      List(
        IngressRuleArgs(
          http = HttpIngressRuleValueArgs(
            List(
              HttpIngressPathArgs(
                path = s"/$name",
                pathType = "Prefix",
                backend = IngressBackendArgs(service =
                  IngressServiceBackendArgs(name = name, port = ServiceBackendPortArgs(number = port))
                )
              )
            )
          )
        )
      )
    )
  )
)
