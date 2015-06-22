package no.nextgentel.oss.akkatools.cluster

import akka.actor.{PoisonPill, ActorRef, Props, ActorContext}
import akka.contrib.pattern.{ClusterSingletonProxy, ClusterSingletonManager}

object ClusterSingletonHelper {

  def startClusterSingleton(context: ActorContext, props: Props, name: String): ActorRef = {
    startClusterSingleton(context, props, name, PoisonPill)
  }

  def startClusterSingleton(context: ActorContext, props: Props, name: String, terminationMessage:Any): ActorRef = {
    val singletonManagerName = name + "ClusterSingleton"
    context.actorOf(ClusterSingletonManager.props(
      singletonProps = props,
      singletonName = name,
      terminationMessage = terminationMessage,
      role = None
    ), singletonManagerName)

    // Start the ClusterSingletonProxy-actor which we're going to use to access the single instance in our cluster
    val proxyActor = context.actorOf(ClusterSingletonProxy.props(
      singletonPath = s"/user/$singletonManagerName/$name",
      role = None),
      name = name + "ActorProxy")

    proxyActor
  }
}
