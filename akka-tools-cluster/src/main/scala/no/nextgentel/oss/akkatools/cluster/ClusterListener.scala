package no.nextgentel.oss.akkatools.cluster

import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import akka.cluster.{Cluster, ClusterEvent}
import ClusterEvent._
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

trait FatalClusterErrorHandler {
  def onFatalClusterError(errorMsg: String): Unit
}

case class ClusterStartTimeout()

case class DoHousekeeping()

case class AppIsShuttingDown()


object ClusterListener {

  def props(clusterConfig: AkkaClusterConfig,
            repo: ClusterNodeRepo,
            fatalClusterErrorHandler: FatalClusterErrorHandler):Props =
    props(
      clusterConfig,
      repo,
      fatalClusterErrorHandler,
      FiniteDuration(5, TimeUnit.SECONDS),
      FiniteDuration(20, TimeUnit.SECONDS))

  def props(clusterConfig: AkkaClusterConfig,
            repo: ClusterNodeRepo,
            fatalClusterErrorHandler: FatalClusterErrorHandler,
            writeClusterNodeAliveInterval: FiniteDuration,
            clusterNodesAliveSinceCheck: FiniteDuration) = Props(
    new ClusterListener(
      clusterConfig,
      repo,
      fatalClusterErrorHandler,
      writeClusterNodeAliveInterval,
      clusterNodesAliveSinceCheck))
}

class ClusterListener
(
  clusterConfig: AkkaClusterConfig,
  repo: ClusterNodeRepo,
  fatalClusterErrorHandler: FatalClusterErrorHandler,
  writeClusterNodeAliveInterval: FiniteDuration,
  clusterNodesAliveSinceCheck: FiniteDuration
  ) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  val cluster = Cluster.get(context.system)
  var upMembers = Set[String]()
  val maxAllowedTimeBeforeClusterIsReady = resolveMaxAllowedTimeBeforeClusterIsReady()
  var scheduledDoHousekeeping: Option[Cancellable] = None
  var clusterStartTimeout:Option[Cancellable] = Some(context.system.scheduler.scheduleOnce(maxAllowedTimeBeforeClusterIsReady, self, ClusterStartTimeout()))
  val nodeName = clusterConfig.thisHostnameAndPort()
  var appIsShuttingDown = false

  startErrorDetection()

  // When shutting down the app/jvm, it might happen that this takes a long time.
  // If this happens we must prevent the cluster-error-detection from running while waiting for shutdown.
  // If not, we might end up thinking that we have a critical cluster-error => trigger restart of application.
  {
    val thisActor = self
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        LoggerFactory.getLogger(getClass).info("Jvm is shutting down - notifying ClusterListener")
        thisActor ! AppIsShuttingDown()
      }
    })
  }


  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    removeClusterNodeAlive() // do some cleanup
    cluster.unsubscribe(self)
  }

  def receive = {
    case mUp: MemberUp =>
      upMembers = upMembers + mUp.member.address.toString
      log.info("Member is Up: {} - memberCount: {}", mUp.member, upMembers.size)
      if ( clusterStartTimeout.isDefined) {
        clusterStartTimeout.get.cancel()
        clusterStartTimeout = None
      }
    case mUnreachable: UnreachableMember =>
      log.warning("Member detected as unreachable: {} - memberCount: {}", mUnreachable.member, upMembers.size)

    case mRemoved: MemberRemoved =>

      upMembers = upMembers - mRemoved.member.address.toString
      log.info("Member is Removed: {} - memberCount: {}", mRemoved.member, upMembers.size)

    case m: MemberEvent => None
    case h: DoHousekeeping => doHousekeeping()
    case t: ClusterStartTimeout =>
      scheduledDoHousekeeping.map { c => c.cancel() }
      val errorMsg = "Fatal Cluster Error detected: Timed out waiting for clusterStart after " + maxAllowedTimeBeforeClusterIsReady
      log.error(errorMsg)
      fatalClusterErrorHandler.onFatalClusterError(errorMsg)
    case AppIsShuttingDown() =>
      log.info("Jvm is shutting down - stopping cluster-error-detection")
      scheduledDoHousekeeping.foreach(_.cancel())
      scheduledDoHousekeeping = None
      appIsShuttingDown = true

  }

  private def startErrorDetection(): Unit = {
    log.info("Starting Cluster Error detection")

    writeClusterNodeAlive()
    scheduleDoHousekeeping()
  }

  private def doHousekeeping(): Unit = {
    writeClusterNodeAlive()
    if (!checkForErrorSituation()) {
      scheduleDoHousekeeping()
    }
  }

  private def weHaveJoinedCluster(): Boolean = upMembers.nonEmpty

  private def writeClusterNodeAlive(): Unit = {

    log.debug(s"Updating writeClusterNodeAlive for $nodeName - weHaveJoinedCluster: ${weHaveJoinedCluster()}")
    repo.writeClusterNodeAlive(nodeName, OffsetDateTime.now, weHaveJoinedCluster())
  }

  private def checkForErrorSituation(): Boolean = {
    if( !weHaveJoinedCluster() ) {
      // No error-situation since we have not joined cluster yet
      return false
    }

    if ( appIsShuttingDown ) {
      // No error-situation since we're shutting down
      return false
    }

    val aliveClusterNodes = repo.findAliveClusterNodes(clusterNodesAliveSinceCheck, onlyJoined = true)
    log.debug("List of live clusterNodes from DB: " + aliveClusterNodes.mkString(","))
    if (aliveClusterNodes.size > upMembers.size) {
      val errorMsg = "Fatal Cluster Error detected: " +
        "We only see " + upMembers.size + " of " + aliveClusterNodes.size + " live nodes. " +
        "Nodes we see as part of Cluster: " + upMembers.mkString(",") + ", " +
        "Nodes that is live in db: " + aliveClusterNodes.mkString(",")
      log.error(errorMsg)
      removeClusterNodeAlive()
      fatalClusterErrorHandler.onFatalClusterError(errorMsg)
      true
    } else false
  }

  private def removeClusterNodeAlive(): Unit = {
    log.debug("removeClusterNodeAlive for {}", nodeName)
    repo.removeClusterNodeAlive(nodeName)
  }

  protected def scheduleDoHousekeeping(): Unit = {
    scheduledDoHousekeeping.map { c => c.cancel() }
    scheduledDoHousekeeping = Some(context.system.scheduler.scheduleOnce(writeClusterNodeAliveInterval, self, DoHousekeeping()))
  }

  private def resolveMaxAllowedTimeBeforeClusterIsReady(): FiniteDuration = {
    // maxAllowedTimeBeforeClusterIsReady must be greater than akka.cluster.auto-down-unreachable-after,
    // because when restarting, it will not be allowed to rejoin from the same node until the first one (the one that stopped)
    // is considered downed..
    val seconds = context.system.settings.config.getDuration("akka.cluster.auto-down-unreachable-after", TimeUnit.SECONDS) + 30
    val duration = FiniteDuration.apply(seconds, TimeUnit.SECONDS)
    log.info(s"Using maxAllowedTimeBeforeClusterIsReady = $duration")
    duration
  }

}
