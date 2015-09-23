package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.sql.Connection
import javax.sql.DataSource

import liquibase.{Contexts, Liquibase}
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.h2.jdbcx.JdbcDataSource

object DataSourceUtil {


  def createDataSource(h2DbName:String, pathToLiquibaseFile:String):DataSource = {
    this.synchronized {
      val dataSource = new JdbcDataSource
      dataSource.setURL(s"jdbc:h2:mem:$h2DbName;mode=oracle;DB_CLOSE_DELAY=-1")
      dataSource.setUser("sa")
      dataSource.setPassword("sa")

      // We need to grab a connection and not release it to prevent the db from being
      // released when no connections are active..
      dataSource.getConnection

      updateDb(dataSource, pathToLiquibaseFile)
      dataSource
    }
  }


  private def createLiquibase(dbConnection: Connection, diffFilePath: String): Liquibase = {
    val database = DatabaseFactory.getInstance.findCorrectDatabaseImplementation(new JdbcConnection(dbConnection))
    val classLoader = DataSourceUtil.getClass.getClassLoader
    val resourceAccessor = new ClassLoaderResourceAccessor(classLoader)
    new Liquibase(diffFilePath, resourceAccessor, database)
  }

  private def updateDb(db: DataSource, diffFilePath: String): Unit = {
    val dbConnection = db.getConnection
    val liquibase = createLiquibase(dbConnection, diffFilePath)
    try {
      liquibase.update(null.asInstanceOf[Contexts])
    } catch {
      case e: Throwable => throw e
    } finally {
      liquibase.forceReleaseLocks()
      dbConnection.rollback()
      dbConnection.close()
    }
  }


}
