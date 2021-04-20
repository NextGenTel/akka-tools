package no.nextgentel.oss.akkatools.cluster

import java.time.OffsetDateTime

import scala.concurrent.duration.FiniteDuration

trait ClusterNodeRepo {
  // Writes to db that this clusterNode is alive
  def writeClusterNodeAlive(nodeNameAndPort: String, timestamp: OffsetDateTime, joined:Boolean): Unit

  def removeClusterNodeAlive(nodeNameAndPort: String): Unit

  // Returns list of all nodeNameAndPorts that has written it is alive since aliveAfter
  def findAliveClusterNodes(clusterNodesAliveSinceCheck: FiniteDuration, onlyJoined:Boolean): List[String]
}
