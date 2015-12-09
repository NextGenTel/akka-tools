package no.nextgentel.oss.akkatools.cluster

import java.time.OffsetDateTime

import org.scalatest.{Matchers, FunSuite}

import scala.concurrent.duration.FiniteDuration

class SeedNodesListOrderingResolverTest extends FunSuite with Matchers {

  test("no live nodes") {
    val repo = new OurClusterNodeRepo(List())
    assert(AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999"))))

    assert(AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host1"), 9999, List("host2:9999", "host1:9999"))))
  }

  test("alive nodes found") {
    val repo = new OurClusterNodeRepo(List("akka.tcp://MobilityService@host1:9999"))
    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999"))))

    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host2:9999", "host1:9999"))))
  }

  test("This node is not a seedNode - with alive Nodes") {
    val repo = new OurClusterNodeRepo(List("akka.tcp://MobilityService@host1:9999", "akka.tcp://MobilityService@host2:9999"))
    assert(AkkaClusterConfig(Some("host3"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host3"), 9999, List("host1:9999", "host2:9999"))))
  }

  test("This node is not a seedNode - with no alive Nodes") {
    val repo = new OurClusterNodeRepo(List())
    assert(AkkaClusterConfig(Some("host3"), 9999, List("host2:9999", "host1:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host3"), 9999, List("host2:9999", "host1:9999"))))
  }

  class OurClusterNodeRepo(aliveClusterNodes:List[String]) extends ClusterNodeRepo {
    // Writes to db that this clusterNode is alive
    override def writeClusterNodeAlive(nodeNameAndPort: String, timestamp: OffsetDateTime): Unit = {}

    override def removeClusterNodeAlive(nodeNameAndPort: String): Unit = {}

    // Returns list of all nodeNameAndPorts that has written it is alive since aliveAfter
    override def findAliveClusterNodes(clusterNodesAliveSinceCheck: FiniteDuration): List[String] = aliveClusterNodes
  }
}
