import ReleaseTransformations._
import sbt.{Credentials, Path}
import sbtrelease.ReleasePlugin.autoImport.releaseStepCommand


lazy val commonSettings = Seq(
  organization := "no.nextgentel.oss.akka-tools",
  organizationName := "NextGenTel AS",
  organizationHomepage := Some(url("http://www.nextgentel.net")),
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq("2.12.13", "2.13.5"),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  sonatypeProfileName := "no.nextgentel",
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials_sonatype"),
  homepage := Some(url("https://github.com/NextGenTel/akka-tools")),
  licenses := Seq("MIT" -> url("https://github.com/NextGenTel/akka-tools/blob/master/LICENSE.txt")),
  startYear := Some(2015),
  pomExtra := (
      <scm>
        <url>git@github.com:NextGenTel/akka-tools.git</url>
        <connection>scm:git:git@github.com:NextGenTel/akka-tools.git</connection>
      </scm>
      <developers>
        <developer>
          <id>mbknor</id>
          <name>Morten Kjetland</name>
          <url>https://github.com/mbknor</url>
        </developer>
      </developers>),
  compileOrder in Test := CompileOrder.Mixed,
  javacOptions ++= Seq("-source", "1.16", "-target", "1.16"),
  scalacOptions ++= Seq("-unchecked", "-deprecation"),
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  //resolvers += "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven"
)


val akkaVersion = "2.6.14"
val jacksonVersion = "2.12.3"
val jacksonScalaModuleVersion = jacksonVersion
val slf4jVersion = "1.7.30"
val scalaTestVersion = "3.2.7"

lazy val akkaToolsCommonDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "org.slf4j" % "slf4j-api" % slf4jVersion)

lazy val akkaToolsPersistenceDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
  "org.slf4j" % "slf4j-api" % slf4jVersion,
  "com.google.guava" % "guava" % "30.1.1-jre",
)

lazy val akkaToolsJsonSerializingDependencies = Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonScalaModuleVersion exclude("org.scala-lang", "scala-reflect")
)

lazy val akkaToolsJdbcJournalDependencies = Seq(
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
  "org.sql2o" % "sql2o" % "1.6.0",
  "com.typesafe.akka" %% "akka-persistence-tck" % akkaVersion % "test"
)

lazy val akkaToolsClusterDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "org.slf4j" % "slf4j-api" % slf4jVersion
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
  "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion % "test",
  "org.awaitility" % "awaitility-scala" % "4.0.3" % "test"
)

lazy val testDbDependencies = Seq(
  "org.liquibase" % "liquibase-core" % "4.3.3" % "test",
  "com.mattbertolini" % "liquibase-slf4j" % "4.0.0" % "test",
  "com.h2database" % "h2" % "1.4.200" % "test"
)

lazy val exampleAggregatesDependencies = Seq(
  "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "org.liquibase" % "liquibase-core" % "4.3.3",
  "com.mattbertolini" % "liquibase-slf4j" % "4.0.0",
  "com.h2database" % "h2" % "1.4.200"
)

lazy val root = (project in file("."))
  .settings(name := "akka-tools-parent")
  .settings(commonSettings: _*)
  .aggregate(
    akkaToolsCommon,
    akkaToolsPersistence,
    akkaToolsJsonSerializing,
    akkaToolsJdbcJournal,
    akkaToolsCluster,
    akkaExampleAggregates)

lazy val akkaToolsCommon = (project in file("akka-tools-common"))
  .settings(name := "akka-tools-common")
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsCommonDependencies))

lazy val akkaToolsPersistence = (project in file("akka-tools-persistence"))
  .settings(name := "akka-tools-persistence")
  .settings(commonSettings: _*)
  .dependsOn(akkaToolsCommon, akkaToolsJdbcJournal)
  .settings(libraryDependencies ++= (akkaToolsPersistenceDependencies))
  .settings(libraryDependencies ++= (testDependencies))
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-persistence-tck" % akkaVersion % "test",
    "junit" % "junit" % "4.13.2" % "test"))


lazy val akkaToolsJsonSerializing = (project in file("akka-tools-json-serializing"))
  .settings(name := "akka-tools-json-serializing")
  .dependsOn(akkaToolsCommon)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsJsonSerializingDependencies))
  .settings(libraryDependencies ++= (testDependencies))


lazy val akkaToolsJdbcJournal = (project in file("akka-tools-jdbc-journal"))
  .settings(name := "akka-tools-jdbc-journal")
  .dependsOn(akkaToolsCommon)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsJdbcJournalDependencies))
  .settings(libraryDependencies ++= (testDbDependencies))
  .settings(libraryDependencies ++= (testDependencies))

lazy val akkaToolsCluster = (project in file("akka-tools-cluster"))
  .settings(name := "akka-tools-cluster")
  .dependsOn(akkaToolsCommon)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsClusterDependencies))
  .settings(libraryDependencies ++= (testDependencies))
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"))

lazy val akkaExampleAggregates = (project in file("examples/aggregates"))
  .settings(name := "example-aggregates")
  .dependsOn(akkaToolsPersistence)
  .dependsOn(akkaToolsCluster)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (exampleAggregatesDependencies))
  .settings(libraryDependencies ++= (testDependencies))
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % "test"))


releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  commitNextVersion,
  pushChanges,
  releaseStepCommand("sonatypeRelease")
)
