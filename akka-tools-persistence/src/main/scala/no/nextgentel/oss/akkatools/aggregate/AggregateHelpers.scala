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


@deprecated(
  """Used AggregateStarter and AggregateViewStarter instead.
    |
    |The GeneralAggregateBuilder-approach ended up with a not so good mix of starting the Aggregate
    | and working with the view.
    |
    | Please have a look at
    | no.nextgentel.oss.akkatools.example.booking.BookingStarter
    | in
    | akka-tools/examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/Booking.scala
  """.stripMargin, "1.0.3")
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
