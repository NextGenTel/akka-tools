package no.ngt.oss.akkatools.cluster

import java.util.concurrent.TimeUnit

import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

// Must be used together with NgtClusterListener
object NgtSeedNodesListOrderingResolver {
  val log = LoggerFactory.getLogger(getClass)
  def resolveSeedNodesList(repo:ClusterNodeRepo, clusterConfig:NgtClusterConfig, maxAliveAge:FiniteDuration = FiniteDuration(20, TimeUnit.SECONDS)):NgtClusterConfig = {

    val ourSeedNode = clusterConfig.thisHostnameAndPort()

    // Since we're starting up, just make sure that we do not find info about ourself from our last run
    log.debug(s"removeClusterNodeAlive for $ourSeedNode")
    repo.removeClusterNodeAlive(ourSeedNode)

    val allSeedNodes = clusterConfig.seedNodes
    val aliveNodes = repo.findAliveClusterNodes(maxAliveAge)

    val seedNodeListToUse = if ( aliveNodes.isEmpty ) {
      val allNodesExceptOur = allSeedNodes.filter( n => n != ourSeedNode)
      val list = List(ourSeedNode) ++ allNodesExceptOur

      log.info("No other clusterNodes found as alive - We must be first seed node - seedNodeListToUse: " + list)
      list
    } else {

      val allNodesExceptOurAndAliveOnes = allSeedNodes.filter( n => n != ourSeedNode && !aliveNodes.contains(n))

      val list = aliveNodes ++ List(ourSeedNode) ++ allNodesExceptOurAndAliveOnes

      log.info("Found other alive clusterNodes - we should not be first seed node. Alive cluster nodes: " + aliveNodes.mkString(",") + " - seedNodeListToUse: " + list)
      list
    }

    clusterConfig.withSeedNodeList(seedNodeListToUse)
  }
}
