lazy val commonSettings = Seq(
  organization := "no.ngt.oss.akka-tools",
  version := "0.9-SNAPSHOT",
  scalaVersion := "2.11.6",
  publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath+"/.m2/repository")))
)


val akkaVersion = "2.3.11"
val jacksonVersion = "2.4.6"
val jacksonScalaModuleVersion = "2.4.5"
val slf4jVersion = "1.7.7"

lazy val akkaToolsPersistenceDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,
  "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
  "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
  "org.slf4j" % "slf4j-api" % slf4jVersion,
  "com.google.guava" % "guava" % "18.0")

lazy val akkaToolsJsonSerializingDependencies = Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonScalaModuleVersion exclude("org.scala-lang", "scala-reflect")
)

lazy val akkaToolsJdbcJournalDependencies = Seq(
  "org.sql2o" % "sql2o" % "1.5.2"
)

lazy val akkaToolsClusterDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,
  "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
  "org.slf4j" % "slf4j-api" % slf4jVersion
)

lazy val testDependencies = Seq(
  "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test",
  "ch.qos.logback" % "logback-classic" % "1.1.3" % "test"
)

lazy val root = (project in file("."))
  .settings(name := "akka-tools-parent")
  .settings(commonSettings: _*)
  .aggregate(akkaToolsPersistence, akkaToolsJsonSerializing, akkaToolsJdbcJournal, akkaToolsCluster)


lazy val akkaToolsPersistence = (project in file("akka-tools-persistence"))
  .settings(name := "akka-tools-persistence")
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsPersistenceDependencies))

lazy val akkaToolsJsonSerializing = (project in file("akka-tools-json-serializing"))
  .settings(name := "akka-tools-json-serializing")
  .dependsOn(akkaToolsPersistence)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsJsonSerializingDependencies))
  .settings(libraryDependencies ++= (testDependencies))


lazy val akkaToolsJdbcJournal = (project in file("akka-tools-jdbc-journal"))
  .settings(name := "akka-tools-jdbc-journal")
  .dependsOn(akkaToolsCluster)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsJdbcJournalDependencies))

lazy val akkaToolsCluster = (project in file("akka-tools-cluster"))
  .settings(name := "akka-tools-cluster")
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= (akkaToolsClusterDependencies))