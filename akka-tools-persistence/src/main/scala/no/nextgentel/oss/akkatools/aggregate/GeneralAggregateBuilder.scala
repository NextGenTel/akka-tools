package no.nextgentel.oss.akkatools.aggregate

import akka.actor._
import akka.cluster.sharding.{ClusterShardingSettings, ClusterSharding}
import akka.util.Timeout
import no.nextgentel.oss.akkatools.utils.{ForwardToCachedActor, ActorCache}

import scala.concurrent.Future
import scala.reflect.ClassTag

abstract class GeneralAggregateBuilder[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  actorSystem:ActorSystem
  ) {

  private lazy val messageExtractor:AggregateCmdMessageExtractor = createAggregateCmdMessageExtractor()

  def createAggregateCmdMessageExtractor() = new AggregateCmdMessageExtractor()


  def name():String = getClass.getSimpleName

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

    ClusterSharding.get(actorSystem).start(name, props, ClusterShardingSettings(actorSystem), messageExtractor)

    // Now that the shard is created/started, we can configure the dispatcher
    dispatcher ! ClusterSharding.get(actorSystem).shardRegion(name)
  }

  // Override this method to create Initial states for views
  def createInitialState(aggregateId:String):S = {
    throw new Exception("You must override createInitialState()")
  }

  def persistenceIdBase():String = {
    throw new Exception("You must override persistenceIdBase() - it MUST have the same value as the one used by the GeneralAggregate")
  }

  // Creates props for view
  def createViewProps(aggregateId:String):Props = {
    Props(new GeneralAggregateView[E,S](persistenceIdBase(), aggregateId, createInitialState(aggregateId)))
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
