package no.nextgentel.oss.akkatools.persistence

import java.io.UnsupportedEncodingException
import java.net.URLDecoder

import akka.actor.{ActorPath, PoisonPill}
import akka.contrib.pattern.ShardRegion

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

abstract class EnhancedPersistentShardingActor[E:ClassTag, Ex <: Exception : ClassTag]
(
  idleTimeout:FiniteDuration,
  ourDispatcherActor:ActorPath
  ) extends EnhancedPersistentActor[E, Ex](idleTimeout) {

  def this(ourDispatcherActor:ActorPath) = this(EnhancedPersistentActor.DEFAULT_IDLE_TIMEOUT_IN_SECONDS, ourDispatcherActor)

  override def persistenceId: String = {
    // Same as in akka 2.3.3
    // We need to have it resolvable like this
    // Sharding is creating instances of these actors with no id in constructor, so we must resolve it from the path.
    // Sharding IS giving eavh actor a unique name based on the (correct) id.
    val pathWithoutAddress: String = self.path.toStringWithoutAddress
    try {
      // Sharding urlencodes the last part of the path which is our id - therefor we must url-decode it
      return URLDecoder.decode(pathWithoutAddress, "UTF-8")
    }
    catch {
      case e: UnsupportedEncodingException => {
        throw new RuntimeException("Error url-decoding persistenceId " + pathWithoutAddress, e)
      }
    }
  }

  protected def onInactiveTimeout() {
    context.parent ! ShardRegion.Passivate(PoisonPill)
  }

  // The dispatchId that is used when sending messages to this actor - typically serviceId or requestId - extract it from actorPath
  lazy val dispatchId: String = {
    val id: String = self.path.name
    try {
      URLDecoder.decode(id, "UTF-8")
    }
    catch {
      case e: UnsupportedEncodingException => {
        throw new RuntimeException("Error url-decoding dispatchId " + id, e)
      }
    }
  }

  // Sending messages with our dispatcherId as confirmation routing info - to help the confirmation coming back to us
  override protected def sendAsDurableMessage(payload: AnyRef, destinationActor: ActorPath): Unit =
    sendAsDurableMessage(payload, destinationActor, dispatchId)
}

abstract class EnhancedPersistentShardingJavaActor[Ex <: Exception : ClassTag](idleTimeout:FiniteDuration, ourDispatcherActor:ActorPath) extends EnhancedPersistentShardingActor[AnyRef, Ex](idleTimeout, ourDispatcherActor) with EnhancedPersistentJavaActorLike {

  def this(ourDispatcherActor:ActorPath) = this(EnhancedPersistentActor.DEFAULT_IDLE_TIMEOUT_IN_SECONDS, ourDispatcherActor)

}