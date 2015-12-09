package no.nextgentel.oss.akkatools.cluster

import java.util.concurrent.TimeUnit

import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

// Must be used together with ClusterListener
object SeedNodesListOrderingResolver {
  val log = LoggerFactory.getLogger(getClass)
  def resolveSeedNodesList(repo:ClusterNodeRepo, clusterConfig:AkkaClusterConfig, maxAliveAge:FiniteDuration = FiniteDuration(20, TimeUnit.SECONDS)):AkkaClusterConfig = {

    val ourNode = clusterConfig.thisHostnameAndPort()

    // Since we're starting up, just make sure that we do not find info about ourself from our last run
    log.debug(s"removeClusterNodeAlive for $ourNode")
    repo.removeClusterNodeAlive(ourNode)

    val allSeedNodes = clusterConfig.seedNodes

    val weAreSeedNode = allSeedNodes.contains(ourNode)
    if ( !weAreSeedNode) {
      log.info("We are NOT a seedNode")
    }

    val aliveNodes = repo.findAliveClusterNodes(maxAliveAge).map {
      node =>
        // alive nodes are listed on this form:
        //    akka.tcp://SomeAkkaSystem@host1:9999
        // We must remove everything before hostname:port
        val index = node.indexOf('@')
        if ( index >= 0) node.substring(index+1) else node
    }

    val seedNodeListToUse = if ( aliveNodes.isEmpty ) {
      if (weAreSeedNode) {
        val allNodesExceptOur = allSeedNodes.filter(n => n != ourNode)
        val list = List(ourNode) ++ allNodesExceptOur

        log.info("No other clusterNodes found as alive - We must be first seed node - seedNodeListToUse: " + list)
        list
      } else {
        log.info("No other clusterNodes found as alive - Since we're not a seedNode, we're using the list as is - seedNodeListToUse: " + allSeedNodes)
        allSeedNodes
      }
    } else {

      if (weAreSeedNode) {
        val allNodesExceptOurAndAliveOnes = allSeedNodes.filter(n => n != ourNode && !aliveNodes.contains(n))

        val list = aliveNodes ++ List(ourNode) ++ allNodesExceptOurAndAliveOnes

        log.info("Found other alive clusterNodes - we should not be first seed node. Alive cluster nodes: " + aliveNodes.mkString(",") + " - seedNodeListToUse: " + list)
        list
      } else {
        val allNodesExceptAliveOnes = allSeedNodes.filter(n => !aliveNodes.contains(n))

        val list = aliveNodes ++ allNodesExceptAliveOnes

        log.info("Found other alive clusterNodes - Alive cluster nodes: " + aliveNodes.mkString(",") + " - seedNodeListToUse: " + list)
        list

      }
    }

    clusterConfig.withSeedNodeList(seedNodeListToUse)
  }
}
