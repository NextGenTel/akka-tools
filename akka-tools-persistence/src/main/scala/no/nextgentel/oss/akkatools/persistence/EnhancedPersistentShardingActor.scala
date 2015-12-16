package no.nextgentel.oss.akkatools.persistence

import java.io.UnsupportedEncodingException
import java.net.URLDecoder

import akka.actor.{ActorPath, PoisonPill}
import akka.cluster.sharding.ShardRegion

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
  *
  * @param dmSelf dmSelf is used as the address where the DM-confirmation-messages should be sent.
  *               In a sharding environment, this has to be our dispatcher which knows how to reach the sharding mechanism.
  *               If null, we'll fallback to self - useful when testing
  * @tparam E     Superclass/trait representing your events
  * @tparam Ex    Exception-type representing a "known error" - not a failure
  */
abstract class EnhancedPersistentShardingActor[E:ClassTag, Ex <: Exception : ClassTag]
(
  // dmSelf is used as the address where the DM-confirmation-messages should be sent.
  // In a sharding environment, this has to be our dispatcher which knows how to reach the sharding mechanism.
  // If null, we'll fallback to self - useful when testing
  dmSelf:ActorPath
  ) extends EnhancedPersistentActor[E, Ex] {

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  def persistenceIdBase():String

  private lazy val resolvedPersistenceId:String = {
    // Same as in akka 2.3.3
    // We need to have it resolvable like this
    // Sharding is creating instances of these actors with no id in constructor, so we must resolve it from the path.
    // Sharding IS giving each actor a unique name based on the (correct) id.


    // Must extract the id as the last part of the url
    val rawIdFromPath = self.path.elements.last
    val id = try {
      // Sharding urlencodes the last part of the path which is our id - therefor we must url-decode it
      URLDecoder.decode(rawIdFromPath, "UTF-8")
    }
    catch {
      case e: UnsupportedEncodingException => {
        throw new RuntimeException("Error url-decoding rawIdFromPath " + rawIdFromPath, e)
      }
    }

    persistenceIdBase + id

  }

  override def persistenceId: String = resolvedPersistenceId

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


  // If dmSelf is missing, we're fallbacking to super-impl which is self.
  // When running in a sharded environment, we cannot use our real actors self.
  // We must use our dispatcher, which knows about the sharding mechanism.
  // This has to be like this to make sure the DM confirmation-message is received by the correct sharded instance of our actor.
  override protected def getDMSelf(): ActorPath = Option(dmSelf).getOrElse(super.getDMSelf())

  // Sending messages with our dispatcherId as confirmation routing info - to help the confirmation coming back to us
  override protected def sendAsDurableMessage(payload: AnyRef, destinationActor: ActorPath): Unit =
    sendAsDurableMessage(payload, destinationActor, dispatchId)

  // Sending messages with our dispatcherId as confirmation routing info - to help the confirmation coming back to us
  override protected def sendAsDurableMessage(sendAsDurableMessage: SendAsDurableMessage): Unit = {
    super.sendAsDurableMessage( sendAsDurableMessage.copy(confirmationRoutingInfo = dispatchId) )
  }
}

abstract class EnhancedPersistentShardingJavaActor[Ex <: Exception : ClassTag]
(
  // dmSelf is used as the address where the DM-confirmation-messages should be sent.
  // In a sharding environment, this has to be our dispatcher which knows how to reach the sharding mechanism.
  // If null, we'll fallback to self - useful when testing
  dmSelf:ActorPath
) extends EnhancedPersistentShardingActor[AnyRef, Ex](dmSelf) with EnhancedPersistentJavaActorLike {

}