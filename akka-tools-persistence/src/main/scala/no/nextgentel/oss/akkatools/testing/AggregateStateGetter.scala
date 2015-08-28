package no.nextgentel.oss.akkatools.testing

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import no.nextgentel.oss.akkatools.persistence.GetState

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

object AggregateStateGetter {
  val defaultTimeout = Duration("60s")

  def apply[S:ClassTag](aggregateActorRef:ActorRef, timeout:Duration = defaultTimeout)(implicit system:ActorSystem):AggregateStateGetter[S] = new AggregateStateGetter[S](system, aggregateActorRef, timeout)
}

import AggregateStateGetter._

class AggregateStateGetter[S:ClassTag](system:ActorSystem, aggregateActorRef:ActorRef, timeout:Duration) {

  def getState():S = {
    implicit val ec = system.dispatcher
    implicit val t = Timeout(timeout.toMillis, TimeUnit.MILLISECONDS)
    val f = ask(aggregateActorRef, GetState()).mapTo[S]
    Await.result(f, timeout)
  }

}

class AggregateStateGetterJava(system:ActorSystem, aggregateActorRef:ActorRef, timeout:Duration)
  extends AggregateStateGetter[Any](system, aggregateActorRef, timeout) {

  def this(system:ActorSystem, aggregateActorRef:ActorRef) = this(system, aggregateActorRef, defaultTimeout)
}
