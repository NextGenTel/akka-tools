package no.ngt.oss.akkatools.aggregate

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.util.Timeout
import no.ngt.oss.akkatools.utils.{ForwardToCachedActor, ActorCache}

import scala.concurrent.Future
import scala.reflect.ClassTag

object GeneralAggregateBuilder {
  protected lazy val defaultMessageExtractor = new AggregateCmdMessageExtractor()

  def apply[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
  (
    actorSystem:ActorSystem,
    name: String,
    initialViewState:Option[S] = None,
    messageExtractor:AggregateCmdMessageExtractor = GeneralAggregateBuilder.defaultMessageExtractor):GeneralAggregateBuilder[E,S] =
    new GeneralAggregateBuilder[E,S](actorSystem, name, initialViewState, messageExtractor)
}

class GeneralAggregateBuilder[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  actorSystem:ActorSystem,
  name: String,
  initialViewState:Option[S] = None,
  messageExtractor:AggregateCmdMessageExtractor = GeneralAggregateBuilder.defaultMessageExtractor
  ) {

  private val dispatcherName = name + "Dispatcher"
  val dispatcher = actorSystem.actorOf(Props(new DispatcherActor(dispatcherName)), dispatcherName)

  private var generalAggregateProps:Option[(ActorPath)=>Props] = None

  def withGeneralAggregateProps(generalAggregateProps:(ActorPath)=>Props):GeneralAggregateBuilder[E,S] = {
    this.generalAggregateProps = Some(generalAggregateProps)
    this
  }

  def start(): Unit = {
    val props = generalAggregateProps match {
      case None => throw new Exception("withGeneralAggregateProps() must be called before start()")
      case Some(f) => f.apply(dispatcher.path)
    }

    ClusterSharding.get(actorSystem).start(name, props, messageExtractor)

    // Now that the shard is created/started, we can configure the dispatcher
    dispatcher ! ClusterSharding.get(actorSystem).shardRegion(name)
  }

  private lazy val resolvedGuardianName:String = actorSystem.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")

  // Creates props for view
  def createViewProps(aggregateId:String):Props = {

    // Should end up with the same base as our regular GeneralAggregates
    val persistenceIdBase = "/user/"+resolvedGuardianName+"/"+name+"/"

    val initialState = initialViewState.getOrElse(throw new Exception("Cannot create view when initialViewState is not defined"))

    Props(new GeneralAggregateView[E,S](persistenceIdBase, aggregateId, initialState))
  }

  // Create an starts view-actor
  def createView(aggregateId:String):ActorRef = actorSystem.actorOf(createViewProps(aggregateId))

  private lazy val viewCache = actorSystem.actorOf(
    ActorCache.props( {id:String => createViewProps(id)}),
    "viewCache_"+name)

  // Will create or reuse existing view and ask it
  def askView(aggregateId:String, msg:AnyRef)(implicit timeout: Timeout):Future[Any] = {
    import akka.pattern.ask
    val f = ask(viewCache, ForwardToCachedActor(aggregateId, msg))
    f
  }




}

class DispatcherActor(name:String) extends Actor with ActorLogging {
  import context._
  var destination = ActorRef.noSender

  def receive = {
    case d:ActorRef =>
      log.info(name + ": Dispatcher is ready")
      destination = d
      become(ready)
    case x:AnyRef =>
      log.error(name + ": Dispatcher not configured yet!!")
  }

  def ready:Receive = {
    case msg:AnyRef =>
      log.debug(s"$name: Dispatching to $destination: $msg")
      destination forward msg
  }
}
