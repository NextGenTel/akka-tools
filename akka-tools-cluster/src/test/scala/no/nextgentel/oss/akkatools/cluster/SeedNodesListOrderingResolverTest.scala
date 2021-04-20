package no.nextgentel.oss.akkatools.cluster

import org.scalatest.funsuite.AnyFunSuite

import java.time.OffsetDateTime
import scala.concurrent.duration.FiniteDuration

class SeedNodesListOrderingResolverTest extends AnyFunSuite {

  test("no live nodes") {
    val repo = new OurClusterNodeRepo(List())
    assert(AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999"))))

    assert(AkkaClusterConfig(Some("host1"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host1"), 9999, List("host2:9999", "host1:9999"))))
  }

  test("alive nodes found") {
    val repo = new OurClusterNodeRepo(List(NodeInfo("akka.tcp://MobilityService@host1:9999", true)))
    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999"))))

    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host2:9999", "host1:9999"))))
  }

  test("alive node (not joined yet) found ") {
    val repo = new OurClusterNodeRepo(List(NodeInfo("akka.tcp://MobilityService@host1:9999", false)))
    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999"))))

    assert(AkkaClusterConfig(Some("host2"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host2"), 9999, List("host2:9999", "host1:9999"))))
  }

  test("This node is not a seedNode - with alive Nodes") {
    val repo = new OurClusterNodeRepo(List(NodeInfo("akka.tcp://MobilityService@host1:9999", true), NodeInfo("akka.tcp://MobilityService@host2:9999", true)))
    assert(AkkaClusterConfig(Some("host3"), 9999, List("host1:9999", "host2:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host3"), 9999, List("host1:9999", "host2:9999"))))
  }

  test("This node is not a seedNode - with no alive Nodes") {
    val repo = new OurClusterNodeRepo(List())
    assert(AkkaClusterConfig(Some("host3"), 9999, List("host2:9999", "host1:9999")) ==
      SeedNodesListOrderingResolver.resolveSeedNodesList(repo, AkkaClusterConfig(Some("host3"), 9999, List("host2:9999", "host1:9999"))))
  }

  case class NodeInfo(host:String, joined:Boolean)

  class OurClusterNodeRepo(aliveClusterNodes:List[NodeInfo]) extends ClusterNodeRepo {
    // Writes to db that this clusterNode is alive
    override def writeClusterNodeAlive(nodeNameAndPort: String, timestamp: OffsetDateTime, joined:Boolean): Unit = {}

    override def removeClusterNodeAlive(nodeNameAndPort: String): Unit = {}

    // Returns list of all nodeNameAndPorts that has written it is alive since aliveAfter
    override def findAliveClusterNodes(clusterNodesAliveSinceCheck: FiniteDuration, onlyJoined:Boolean): List[String] = {
      if ( onlyJoined) {
        aliveClusterNodes.filter(_.joined).map(_.host)
      } else {
        aliveClusterNodes.map(_.host)
      }
    }
  }
}
