package no.nextgentel.oss.akkatools.cluster

import akka.actor._
import akka.cluster.singleton.{ClusterSingletonProxySettings, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonManager}

object ClusterSingletonHelper {

  def startClusterSingleton(system: ActorSystem, props: Props, name: String): ActorRef = {
    startClusterSingleton(system, props, name, PoisonPill)
  }

  def startClusterSingleton(system: ActorSystem, props: Props, name: String, terminationMessage:Any): ActorRef = {
    val singletonManagerName = name + "ClusterSingleton"
    system.actorOf(ClusterSingletonManager.props(
      props,
      terminationMessage,
      ClusterSingletonManagerSettings(system).withSingletonName(name)
    ), singletonManagerName)

    // Start the ClusterSingletonProxy-actor which we're going to use to access the single instance in our cluster
    val proxyActor = system.actorOf(ClusterSingletonProxy.props(
      s"/user/$singletonManagerName/$name",
      ClusterSingletonProxySettings(system)),
      name = name + "ActorProxy")

    proxyActor
  }
}
