package io.github.jchapuis.leases4s.impl

import com.goyeau.kubernetes.client.{EventType, WatchEvent}
import io.github.jchapuis.leases4s.impl.model.{LeaseData, LeaseDataEvent, Version}
import io.github.jchapuis.leases4s.model.*
import io.k8s.api.coordination.v1
import io.k8s.apimachinery.pkg.apis.meta.v1.{MicroTime, ObjectMeta}
import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object K8sHelpers {
  def leaseEventFromV1(event: WatchEvent[v1.Lease]): Option[LeaseDataEvent] = event.`type` match {
    case EventType.ADDED =>
      leaseIDFromK8s(event.`object`).zip(leaseDataFromK8s(event.`object`)).map(LeaseDataEvent.Added.apply.tupled)
    case EventType.DELETED => leaseIDFromK8s(event.`object`).map(LeaseDataEvent.Deleted.apply)
    case EventType.MODIFIED =>
      leaseIDFromK8s(event.`object`).zip(leaseDataFromK8s(event.`object`)).map(LeaseDataEvent.Modified.apply.tupled)
    case EventType.ERROR => None
  }

  def k8sLeaseFromData(id: LeaseID, data: LeaseData): v1.Lease = v1LeaseFor(
    id,
    data.holder,
    data.labels,
    data.annotations,
    Some(data.version),
    data.acquireTime,
    data.lastRenewTime,
    data.duration
  )

  def k8sLease(
      id: LeaseID,
      holderID: HolderID,
      labels: List[Label],
      annotations: List[Annotation],
      acquireTime: Instant,
      leaseDuration: FiniteDuration
  ): v1.Lease = v1LeaseFor(id, holderID, labels, annotations, version = None, acquireTime, None, leaseDuration)

  def leaseIDFromK8s(lease: v1.Lease): Option[LeaseID] = lease.metadata.flatMap(_.name).flatMap(LeaseID(_))

  def leaseDataFromK8s(lease: v1.Lease): Option[LeaseData] = for {
    holder <- lease.spec.flatMap(_.holderIdentity).flatMap(HolderID(_))
    labels <- lease.metadata
      .flatMap(_.labels)
      .map(_.toList.flatMap { case (key, value) => io.github.jchapuis.leases4s.model.Label(key, value) })
    version     <- lease.metadata.flatMap(_.resourceVersion).map(Version(_))
    duration    <- lease.spec.flatMap(_.leaseDurationSeconds).map(_.seconds)
    acquireTime <- lease.spec.flatMap(_.acquireTime).map(_.value).map(Instant.parse)
    renewTime = lease.spec.flatMap(_.renewTime).map(_.value).map(Instant.parse)
    annotations = lease.metadata
      .flatMap(_.annotations)
      .map(_.toList.flatMap { case (key, value) => Annotation(key, value) })
      .toList
      .flatten
  } yield LeaseData(holder, labels, annotations, version, duration, acquireTime, renewTime)

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def v1LeaseFor(
      id: LeaseID,
      holderID: HolderID,
      labels: List[Label],
      annotations: List[Annotation],
      version: Option[Version],
      acquireTime: Instant,
      renewTime: Option[Instant],
      leaseDuration: FiniteDuration
  ): v1.Lease =
    v1.Lease(
      metadata = Option(
        ObjectMeta(
          name = Option(id.value),
          labels = Option.when(labels.nonEmpty)(labels.map(l => l.key.value -> l.value.value).toMap),
          annotations = Option.when(annotations.nonEmpty)(annotations.map(a => a.key.value -> a.value.value).toMap),
          resourceVersion = version.map(_.value)
        )
      ),
      spec = Option(
        v1.LeaseSpec(
          holderIdentity = Option(holderID.value),
          leaseDurationSeconds = Option(leaseDuration.toSeconds.toInt),
          renewTime = renewTime.map(instant => MicroTime(instant.toString)),
          acquireTime = Option(MicroTime(acquireTime.toString))
        )
      )
    )

}
