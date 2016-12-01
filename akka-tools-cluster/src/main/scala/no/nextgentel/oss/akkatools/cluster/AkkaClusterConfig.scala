package no.nextgentel.oss.akkatools.cluster

import java.net.{UnknownHostException, InetAddress}
import java.util.{List => JList}
import scala.collection.JavaConverters._

object AkkaClusterConfig {

  // Java API
  def create(hostname:String, port:Int, seedNodes:JList[String]):AkkaClusterConfig = {
    AkkaClusterConfig(Option(hostname), port, seedNodes.asScala.toList)
  }
}

case class AkkaClusterConfig
(
  // Will be resolved if null
  private val hostname:Option[String],
  // the tcp port used on this node
  val port:Int,
  // list of all seed-nodes - basically single01 and single02 - on the following form: <host>:<port>
  // e.g in yaml:
  //    - "single01-testing.nextgentel.net:9091"
  //    - "single02-testing.nextgentel.net:9091"
  val seedNodes:List[String]
  ) {

  lazy val thisHostname:String = hostname.getOrElse(resolveHostName())

  def thisHostnameAndPort():String = thisHostname+":"+port


  /**
   * Generates akka config string use to configure the remote listening on this node,
   * and info about all other (seed) nodes.
   * It is VERY important that 'actorSystemName' is using the same name as ActorSystem.create( [name] ).
   * If this name is not correct, it will not work to connect to the remote actor system,
   * even though the host and port is correct.
   * @param actorSystemName the same name as used in ActorSystem.create( [name] ) on all nodes.
   * @return akka config string that can be used in ConfigFactory.parseString()
   */
  def generateAkkaConfig(actorSystemName:String):String = {
    if (port == null.asInstanceOf[Int]) throw new Exception("port is not specified")
    if (seedNodes == null || seedNodes.isEmpty) throw new Exception("seedNodes is not specified")

    val seedNodesString = "[" + seedNodes.map {
      hostAndPort => "\"akka.tcp://" + actorSystemName + "@" + hostAndPort + "\""
    }.mkString(",") + "]"

    s"""akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      |akka.remote.enabled-transports = ["akka.remote.netty.tcp"]
      |akka.remote.netty.tcp.hostname="$thisHostname"
      |akka.remote.netty.tcp.port=$port
      |akka.cluster.seed-nodes = $seedNodesString
    """.stripMargin
  }


  protected def resolveHostName(): String = {
    try {
      val addr: InetAddress = null
      return InetAddress.getLocalHost.getCanonicalHostName
    } catch {
      case ex: UnknownHostException => throw new Exception("Error resolving hostName", ex)
    }
  }

  def withSeedNodeList(newSeedNodeList:List[String]):AkkaClusterConfig = {
    new AkkaClusterConfig(Some(thisHostname), port, newSeedNodeList)
  }
}
