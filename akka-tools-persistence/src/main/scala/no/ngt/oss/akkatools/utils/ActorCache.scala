package no.ngt.oss.akkatools.utils

import java.util.concurrent.TimeUnit

import akka.actor._
import com.google.common.cache._

import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.reflect.ClassTag

case class CheckCache()
case class ForwardToCachedActor[K](key:K, msg:AnyRef)

object ActorCache {
  def props[K:ClassTag](cacheLoader:(K)=>Props, expireAfter: Duration = Duration(2, TimeUnit.MINUTES)) = Props(new ActorCache[K](cacheLoader, expireAfter))
}

class ActorCache[K:ClassTag](cacheLoader:(K)=>Props, expireAfter: Duration) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher

  val removalListener = new RemovalListener[AnyRef, ActorRef] {
    override def onRemoval(notification: RemovalNotification[AnyRef, ActorRef]): Unit = {
      val key = notification.getKey.asInstanceOf[K]
      log.debug("Stopping actor for " + key)
      val actor = notification.getValue
      actor ! PoisonPill
    }
  }

  val realCachLoader = new CacheLoader[AnyRef,ActorRef] {
    override def load(key: AnyRef): ActorRef = {
      log.debug("Creating actor for " + key)
      val props:Props = cacheLoader(key.asInstanceOf[K])
      context.actorOf( props )
    }
  }

  val cache:LoadingCache[AnyRef, ActorRef] = CacheBuilder.newBuilder
    .expireAfterAccess(expireAfter.toMillis, TimeUnit.MILLISECONDS)
    .removalListener(removalListener)
    .build(realCachLoader)

  val waitPeriode = FiniteDuration.apply(expireAfter.toMillis / 2, TimeUnit.MILLISECONDS)

  scheduleNextCacheCheck()

  def scheduleNextCacheCheck(): Unit = {
    context.system.scheduler.scheduleOnce(waitPeriode, self, CheckCache())
  }

  def receive = {
    case CheckCache() => {
      cache.cleanUp()
      scheduleNextCacheCheck()
    }
    case ForwardToCachedActor(key:K, msg) =>
      try {
        val actor = cache.get(key.asInstanceOf[AnyRef])
        log.debug("Forwarding message for " + key + " to " + actor)
        actor forward msg
      } catch {
        case e:Exception =>
          log.error(e, "Error forwarding message (with key "+key+") " + msg)
      }
    case x:AnyRef =>
      log.warning("Droping unknown msg: " + x)
  }

  @throws(classOf[Exception])
  override def postStop {
    super.postStop
    cache.invalidateAll
    cache.cleanUp
  }

}
