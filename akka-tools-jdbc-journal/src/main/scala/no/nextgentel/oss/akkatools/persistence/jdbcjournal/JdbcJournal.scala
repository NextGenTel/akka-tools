package no.nextgentel.oss.akkatools.persistence.jdbcjournal


import java.nio.charset.Charset
import javax.sql.DataSource

import PersistenceIdType._
import akka.actor.ActorLogging
import akka.persistence.AtomicWrite
import akka.persistence.PersistentRepr
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import com.typesafe.config.Config
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializableButNotDeserializable
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try




object JdbcJournalConfig {

  // Java helper
  def create(dataSource: DataSource, schemaName: String, fatalErrorHandler: JdbcJournalErrorHandler) = JdbcJournalConfig(dataSource, Option(schemaName), fatalErrorHandler)
}

case class JdbcJournalConfig
(
  dataSource: DataSource,
  schemaName: Option[String],
  fatalErrorHandler: JdbcJournalErrorHandler, // The fatalErrorHandler is called when something bad has happend - like getting unique PK key errors - Which is probably a symptom of split brain
  persistenceIdSplitter: PersistenceIdSplitter = new PersistenceIdSplitterLastSlashImpl(),
  maxRowsPrRead: Int = JdbcJournal.DEFAULT_MAX_ROWS_PR_READ)

object JdbcJournal {
  val DEFAULT_MAX_ROWS_PR_READ = 1000

  val jacksonJsonSerializer_className = "no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer"

  // This must be called at startup, before any akka-persistence-code is executed
  @deprecated("Instead use the default SingletonJdbcJournalRuntimeDataFactory or configure another one in application.conf")
  def init(config: JdbcJournalConfig): Unit = {
    SingletonJdbcJournalRuntimeDataFactory.init(config)
  }

}

object SingletonJdbcJournalRuntimeDataFactory extends JdbcJournalRuntimeDataFactory {

  private var maxRowsPrRead = JdbcJournal.DEFAULT_MAX_ROWS_PR_READ
  private var _repo: Option[StorageRepo] = None
  private var _persistenceIdSplitter: Option[PersistenceIdSplitter] = None

  def init(config: JdbcJournalConfig): Unit = {
    _repo = Some(new StorageRepoImpl(config.dataSource, config.schemaName, config.fatalErrorHandler))
    _persistenceIdSplitter = Some(config.persistenceIdSplitter)
    maxRowsPrRead = config.maxRowsPrRead
  }

  private def repo(): StorageRepo = _repo.getOrElse(throw new Exception("JdbcJournal not configured yet"))
  private def clusterNodeRepo() = repo().asInstanceOf[ClusterNodeRepo]

  private def persistenceIdSplitter(): PersistenceIdSplitter = _persistenceIdSplitter.getOrElse(throw new Exception("JdbcJournal not configured yet"))

  override def createJdbcJournalRuntimeData(): JdbcJournalRuntimeData = {
    JdbcJournalRuntimeData(repo(), clusterNodeRepo(), persistenceIdSplitter(), maxRowsPrRead)
  }
}

// Need this class so that the journal impl can create an instance of the class if configured to use it
class SingletonJdbcJournalRuntimeDataFactory extends JdbcJournalRuntimeDataFactory {
  override def createJdbcJournalRuntimeData(): JdbcJournalRuntimeData = SingletonJdbcJournalRuntimeDataFactory.createJdbcJournalRuntimeData()
}


trait JdbcJournalRuntimeDataFactory {
  def createJdbcJournalRuntimeData():JdbcJournalRuntimeData
}

case class JdbcJournalRuntimeData( repo:StorageRepo, clusterNodeRepo:ClusterNodeRepo, persistenceIdSplitter:PersistenceIdSplitter, maxRowsPrRead:Int)

trait JdbcJournalExtractRuntimeData {

  val config: Config

  val jdbcJournalRuntimeDataFactoryClassName = config.getString("jdbcJournalRuntimeDataFactory")
  val runtimeData:JdbcJournalRuntimeData = try {
    val jdbcJournalRuntimeDataFactory = Class.forName(jdbcJournalRuntimeDataFactoryClassName).newInstance().asInstanceOf[JdbcJournalRuntimeDataFactory]
    jdbcJournalRuntimeDataFactory.createJdbcJournalRuntimeData()
  } catch {
    case e:Exception => throw new Exception(s"Failed to get JdbcJournalRuntimeData from jdbcJournalRuntimeDataFactory '$jdbcJournalRuntimeDataFactoryClassName'", e)
  }
}
