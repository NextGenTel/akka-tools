package no.nextgentel.oss.akkatools.cluster

import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

import akka.actor.{Props, Cancellable, Actor, ActorLogging}
import akka.cluster.{ClusterEvent, Cluster}
import ClusterEvent._

import scala.concurrent.duration.FiniteDuration

trait FatalClusterErrorHandler {
  def onFatalClusterError(errorMsg: String)
}

case class ClusterStartTimeout()

case class DoHousekeeping()


object ClusterListener {
  def props(clusterConfig: AkkaClusterConfig,
            repo: ClusterNodeRepo,
            fatalClusterErrorHandler: FatalClusterErrorHandler,
            writeClusterNodeAliveInterval: FiniteDuration = FiniteDuration(5, TimeUnit.SECONDS),
            clusterNodesAliveSinceCheck: FiniteDuration = FiniteDuration(20, TimeUnit.SECONDS)) = Props(
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
  var clusterStartTimeout = context.system.scheduler.scheduleOnce(maxAllowedTimeBeforeClusterIsReady, self, ClusterStartTimeout())
  var errorDetectionIsStarted = false
  val nodeName = clusterConfig.thisHostnameAndPort()

  // Since we're starting up, just make sure that we do not find info about ourself from our last run
  removeClusterNodeAlive()


  override def preStart {
    cluster.subscribe(self, initialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop {
    removeClusterNodeAlive() // do some cleanup
    errorDetectionIsStarted = false
    cluster.unsubscribe(self)
  }

  def receive = {
    case mUp: MemberUp =>
      upMembers = upMembers + mUp.member.address.toString
      log.info("Member is Up: {} - memberCount: {}", mUp.member, upMembers.size)
      if (!errorDetectionIsStarted) {
        startErrorDetection()
        errorDetectionIsStarted = true
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
  }

  private def startErrorDetection() {
    log.info("Starting Cluster Error detection")
    clusterStartTimeout.cancel
    writeClusterNodeAlive()
    scheduleDoHousekeeping()
  }

  private def doHousekeeping() {
    writeClusterNodeAlive
    if (!checkForErrorSituation()) {
      scheduleDoHousekeeping()
    }
  }

  private def writeClusterNodeAlive() {
    log.debug("Updating writeClusterNodeAlive for {}", nodeName)
    repo.writeClusterNodeAlive(nodeName, OffsetDateTime.now)
  }

  private def checkForErrorSituation(): Boolean = {
    val aliveClusterNodes = repo.findAliveClusterNodes(clusterNodesAliveSinceCheck)
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

  private def removeClusterNodeAlive() {
    log.debug("removeClusterNodeAlive for {}", nodeName)
    repo.removeClusterNodeAlive(nodeName)
  }

  protected def scheduleDoHousekeeping() {
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
