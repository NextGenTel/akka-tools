package no.nextgentel.oss.akkatools.aggregate

import akka.actor._
import akka.cluster.sharding.{ClusterShardingSettings, ClusterSharding}
import akka.util.Timeout
import no.nextgentel.oss.akkatools.aggregate.AggregateStarter.AggregatePropsCreator
import no.nextgentel.oss.akkatools.utils.{ForwardToCachedActor, ActorCache}

import scala.concurrent.Future
import scala.reflect.ClassTag

object AggregateStarter {
  /**
    * A function which given the input dispatcher ActorPath, creates the Props-object which is needed to
    * create an instance of your aggregate actor.
    *
    * This dispatcher can/should be used as the dmSelf-parameter
    */
  type AggregatePropsCreator = (ActorPath) => Props

}


// Use this trait if jou need to mix in the GeneralAggregateStarter-code into your own class
trait AggregateStarterLike {
  val name:String
  val system:ActorSystem

  private lazy val messageExtractor:AggregateCmdMessageExtractor = createAggregateCmdMessageExtractor()
  private val dispatcherName = name + "Dispatcher"
  val dispatcher = system.actorOf(Props(new DispatcherActor(dispatcherName)), dispatcherName)
  protected var aggregatePropsCreator:Option[AggregatePropsCreator] = None

  private def createAggregateCmdMessageExtractor() = new AggregateCmdMessageExtractor()

  protected def getAggregatePropsCreator():AggregatePropsCreator = aggregatePropsCreator.getOrElse( throw new Exception("aggregatePropsCreator must be initialized before start()") )
  protected def setAggregatePropsCreator(creator:AggregatePropsCreator):Unit = aggregatePropsCreator = Some(creator)

  def start(): Unit = {
    val aggregateProps:Props = getAggregatePropsCreator().apply(dispatcher.path)

    ClusterSharding.get(system).start(name, aggregateProps, ClusterShardingSettings(system), messageExtractor)

    // Now that the shard is created/started, we can configure the dispatcher
    dispatcher ! ClusterSharding.get(system).shardRegion(name)
  }

}

// Extend this class if you would like to create a specific AggregateStarte-class for your aggregate
abstract class AggregateStarter(val name:String, val system:ActorSystem) extends AggregateStarterLike {
}


/*
 Use this class if you would like to start your aggregate like this:

  new GeneralAggregateStarter().withAggregatePropsCreator {
    dmSelf =>
       Props( new MyAggregateActor(dmSelf, someOtherParam1, param2)
  }.start()
*/
class AggregateStarterSimple(name:String, system:ActorSystem) extends AggregateStarter(name, system) {

  def withAggregatePropsCreator(creator:AggregatePropsCreator):AggregateStarterSimple = {
    aggregatePropsCreator = Some(creator)
    this
  }
}

object AggregateStarterSimple {
  // This method lets you skip "new" if using GeneralAggregateStarter
  def apply(name:String, actorSystem:ActorSystem):AggregateStarterSimple = new AggregateStarterSimple(name, actorSystem)
}


trait AggregateViewAsker {
  def askView(aggregateId:String, msg:AnyRef)(implicit timeout: Timeout):Future[Any]
  def sendToView(aggregateId:String, msg:AnyRef, sender:ActorRef = ActorRef.noSender):Unit
}

trait AggregateViewStarter extends AggregateViewAsker {

  val system:ActorSystem

  protected def viewName():String = getClass.getSimpleName

  private lazy val viewCache = system.actorOf(
    ActorCache.props( {id:String => createViewProps(id)}),
    "viewCache_"+viewName)

  def createViewProps(aggregateId:String):Props

  // Will create or reuse existing view and ask it
  def askView(aggregateId:String, msg:AnyRef)(implicit timeout: Timeout):Future[Any] = {
    akka.pattern.ask(viewCache, ForwardToCachedActor(aggregateId, msg))
  }

  def sendToView(aggregateId:String, msg:AnyRef, sender:ActorRef = ActorRef.noSender): Unit = {
    viewCache.tell(ForwardToCachedActor(aggregateId, msg), sender)
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
