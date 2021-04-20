package no.nextgentel.oss.akkatools.cluster

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.slf4j.LoggerFactory

import scala.util.Random

object ClusterSingletonHelperTest {
  val port = 20000 + Random.nextInt(20000)
}

class ClusterSingletonHelperTest (_system:ActorSystem) extends TestKit(_system) with AnyFunSuiteLike with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.parseString(
      s"""akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
          |akka.remote.artery.canonical.hostname="localhost"
          |akka.remote.artery.canonical.port=${ClusterSingletonHelperTest.port}
          |akka.cluster.seed-nodes = ["akka://test-actor-system@localhost:${ClusterSingletonHelperTest.port}"]
    """.stripMargin
    ).withFallback(ConfigFactory.load("application-test.conf"))))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)


  test("start and communicate with cluster-singleton") {


    val started = TestProbe()
    val proxy = ClusterSingletonHelper.startClusterSingleton(system, Props(new OurClusterSingleton(started.ref)), "ocl")
    started.expectMsg("started")
    val sender = TestProbe()
    sender.send(proxy, "ping")
    sender.expectMsg("pong")

  }
}

class OurClusterSingleton(started:ActorRef) extends Actor {

  started ! "started"
  def receive = {
    case "ping" => sender() ! "pong"
  }
}
