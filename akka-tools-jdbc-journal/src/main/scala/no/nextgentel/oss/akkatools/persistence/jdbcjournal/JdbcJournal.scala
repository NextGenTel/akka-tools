package no.nextgentel.oss.akkatools.persistence.jdbcjournal


import javax.sql.DataSource

import com.typesafe.config.Config
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo


object JdbcJournalConfig {

  val defaultConfigName = "default"

  private var name2Config = Map[String, JdbcJournalConfig]()

  def setConfig(config:JdbcJournalConfig): Unit = {
    setConfig(defaultConfigName, config)
  }

  def setConfig(configName:String, config:JdbcJournalConfig): Unit = {
    name2Config = name2Config + (configName -> config)
  }

  def getConfig(configName:String):JdbcJournalConfig = {
    name2Config.getOrElse(configName, throw new Exception(s"Configuration with name '$configName' has not ben set."))
  }
  def createJdbcJournalRuntimeData(): JdbcJournalRuntimeData = {
    createJdbcJournalRuntimeData(defaultConfigName)
  }
  def createJdbcJournalRuntimeData(configName:String): JdbcJournalRuntimeData = {
    val config = getConfig(configName)
    val repo = config.createStorageRepo()
    JdbcJournalRuntimeData(repo, repo, config.persistenceIdParser, config.maxRowsPrRead)
  }

  // Java helper
  def create(dataSource: DataSource, schemaName: String, fatalErrorHandler: JdbcJournalErrorHandler) = JdbcJournalConfig(dataSource, Option(fatalErrorHandler), StorageRepoConfig(Option(schemaName)))
}

case class JdbcJournalConfig
(
  dataSource: DataSource,
  fatalErrorHandler: Option[JdbcJournalErrorHandler] = None, // The fatalErrorHandler is called when something bad has happened - like getting unique PK key errors - Which is probably a symptom of split brain
  storageRepoConfig: StorageRepoConfig = StorageRepoConfig(schemaName = None),
  persistenceIdParser:PersistenceIdParser = new PersistenceIdParserImpl('/'),
  maxRowsPrRead: Int = 1000,
  databaseImpl:String = "oracle"
) {

  def createStorageRepo():StorageRepoWithClusterNodeRepo = {
    databaseImpl.toLowerCase() match {
      case "oracle" => new StorageRepoImpl(dataSource, storageRepoConfig, fatalErrorHandler)
      case "mysql" => new StorageRepoMySQLImpl(dataSource, storageRepoConfig, fatalErrorHandler)
      case "postgres" => new StorageRepoPostgresImpl(dataSource, storageRepoConfig, fatalErrorHandler)
      case x => throw new Exception(s"Do not understand databaseImpl=$databaseImpl")
    }
  }

  def withPersistenceIdParser(persistenceIdParser:PersistenceIdParser): JdbcJournalConfig = {
    this.copy(persistenceIdParser = persistenceIdParser)
  }

  def withMaxRowsPrRead(maxRowsPrRead: Int): JdbcJournalConfig = {
    this.copy(maxRowsPrRead = maxRowsPrRead)
  }

  def withDatabaseImpl(databaseImpl:String): JdbcJournalConfig = {
    this.copy(databaseImpl = databaseImpl)
  }
}



// ------------------------------------------------------------------

case class JdbcJournalRuntimeData( repo:StorageRepo, clusterNodeRepo:ClusterNodeRepo, persistenceIdParser:PersistenceIdParser, maxRowsPrRead:Int)

trait JdbcJournalRuntimeDataExtractor {

  val config: Config

  val configName = config.getString("configName")
  val runtimeData:JdbcJournalRuntimeData = JdbcJournalConfig.createJdbcJournalRuntimeData(configName)
}
