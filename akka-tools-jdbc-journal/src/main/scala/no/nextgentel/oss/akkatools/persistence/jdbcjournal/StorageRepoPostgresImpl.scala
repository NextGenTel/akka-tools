package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.time.{OffsetDateTime, ZoneId}
import java.util.Date

import javax.sql.DataSource
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import org.slf4j.LoggerFactory
import org.sql2o.data.Row
import org.sql2o.{Sql2o, Sql2oException}

import scala.concurrent.duration.FiniteDuration

class StorageRepoPostgresImpl(sql2o: Sql2o, config:StorageRepoConfig, _errorHandler:Option[JdbcJournalErrorHandler]) extends StorageRepoWithClusterNodeRepo {

  def this(dataSource:DataSource, config:StorageRepoConfig = StorageRepoConfig(), _errorHandler:Option[JdbcJournalErrorHandler] = None) = this(new Sql2o(dataSource), config, _errorHandler)

  import scala.jdk.CollectionConverters._

  // wrap it
  val errorHandler = new JdbcJournalDetectFatalOracleErrorHandler(_errorHandler.getOrElse(
    new JdbcJournalErrorHandler {
      override def onError(e: Exception): Unit = LoggerFactory.getLogger(getClass).error("Fatal jdbc-journal-error (custom errorHandler not configured): " + e, e)
    }
  ))

  lazy val schemaPrefix = config.schemaName.map( s => s + ".").getOrElse("")
  lazy val tableName_journal = s"${schemaPrefix}${config.tableName_journal}"
  lazy val sequenceName_journalIndex = s"${schemaPrefix}${config.sequenceName_journalIndex}"
  lazy val tableName_snapshot = s"${schemaPrefix}${config.tableName_snapshot}"

  def loadJournalEntries(persistenceId: PersistenceId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto] = {
    val sequenceNrColumnName = persistenceId match {
      case p:PersistenceIdSingle    => "sequenceNr"
      case p:PersistenceIdTagsOnly => "journalIndex"
    }

    val preSql = s"select typePath, id, $sequenceNrColumnName, persistentRepr, updated from ${tableName_journal} where "
    val postSql = s" and $sequenceNrColumnName >= :fromSequenceNr and $sequenceNrColumnName <= :toSequenceNr and persistentRepr is not null order by $sequenceNrColumnName limit :max"



      // Must use open due to clob/blob
      val conn = sql2o.open
      try {
        val query =  persistenceId match {
          case p:PersistenceIdSingle =>
            conn.createQuery(preSql + " typePath = :typePath and id = :id " + postSql)
              .addParameter("typePath", p.tag)
              .addParameter("id", p.uniqueId)

          case p:PersistenceIdSingleTagOnly =>
            conn.createQuery(preSql + " typePath = :typePath " + postSql)
              .addParameter("typePath", p.tag)

          case p:PersistenceIdMultipleTags =>
            conn.createQuery(preSql + " typePath in (" + p.tags.map( s => "'" + s + "'" ).mkString(",") + ") " + postSql)
        }
        query.addParameter("fromSequenceNr", fromSequenceNr).addParameter("toSequenceNr", toSequenceNr).addParameter("max", max)

        val table = query.executeAndFetchTable
        table.rows.asScala.toList.map{
          r:Row =>
            JournalEntryDto(
              r.getString("typePath"),
              r.getString("id"),
              r.getLong(sequenceNrColumnName),
              r.getObject("persistentRepr", classOf[Array[Byte]]),
              null,
              OffsetDateTime.ofInstant(r.getDate("updated").toInstant(), ZoneId.systemDefault()))
        }
      } finally {
        if (conn != null) conn.close()
      }
  }

  def insertPersistentReprList(dtoList: Seq[JournalEntryDto]): Unit = {

    val sql = s"insert into ${tableName_journal} (typePath, id, sequenceNr, journalIndex, persistentRepr, payload_write_only, updated) " +
      s"values (:typePath, :id, :sequenceNr, nextval('${sequenceName_journalIndex}'), :persistentRepr, :payload_write_only, current_timestamp)"

    // Insert the whole list in one transaction
    val c = sql2o.beginTransaction()
    try {

      dtoList.foreach {
        dto =>
          val insert = c.createQuery(sql)
          try {

            insert.addParameter("typePath", dto.typePath)
              .addParameter("id", dto.uniqueId)
              .addParameter("sequenceNr", dto.sequenceNr)
              .addParameter("persistentRepr", dto.persistentRepr)
              .addParameter("payload_write_only", dto.payloadWriteOnly)
              .executeUpdate
          } catch {
            case e: Sql2oException => {
              val exception = new Exception(s"Error updating journal for typePath=${dto.typePath}, id=${dto.uniqueId} and sequenceNr=${dto.sequenceNr}: ${e.getMessage}", e)
              errorHandler.onError(e)
              throw exception
            }
          } finally {
            insert.close()
          }
      }

      c.commit(true)
    } catch {
      case e:Throwable =>
        c.rollback(true)
        throw e
    }

  }

  // Since we need to keep track of the highest sequenceNr even after we have deleted an entry,
  // This method only clears the columns persistentRepr and payload_write_only to save space
  def deleteJournalEntryTo(persistenceId: PersistenceIdSingle, toSequenceNr: Long): Unit = {
    val sql = s"update ${tableName_journal} set persistentRepr = null, payload_write_only = null where typePath = :typePath and id = :id and sequenceNr <= :toSequenceNr"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("typePath", persistenceId.tag).addParameter("id", persistenceId.uniqueId).addParameter("toSequenceNr", toSequenceNr).executeUpdate
    } finally {
      c.close()
    }
  }

  // This one both looks at existing once and 'delete once' (with persistentRepr = null)
  def findHighestSequenceNr(persistenceId: PersistenceId, fromSequenceNr: Long): Long = {

    val sequenceNrColumnName = persistenceId match {
      case p:PersistenceIdSingle    => "sequenceNr"
      case p:PersistenceIdTagsOnly  => "journalIndex"
    }

    val preSql = s"select max($sequenceNrColumnName) from ${tableName_journal} where "
    val postSql = s" and $sequenceNrColumnName>=:fromSequenceNr"

    val c = sql2o.open
    try {

      val query = persistenceId match {
        case p:PersistenceIdSingle =>
          c.createQuery( preSql + " typePath = :typePath and id = :id " + postSql)
            .addParameter("typePath", p.tag)
            .addParameter("id", p.uniqueId)

        case p:PersistenceIdSingleTagOnly =>
          c.createQuery( preSql + " typePath = :typePath " + postSql)
            .addParameter("typePath", p.tag)

        case p:PersistenceIdMultipleTags =>
          c.createQuery( preSql + " typePath in (" + p.tags.map( s => "'" + s + "'").mkString(",") + ") " + postSql)
      }

      query.addParameter("fromSequenceNr", fromSequenceNr)
      val table = query.executeAndFetchTable

      if (table.rows.size == 0) {
        return Math.max(fromSequenceNr, 0L)
      }
      val number = Option(table.rows.get(0).getLong(0))
      if (number.isEmpty) {
        return Math.max(fromSequenceNr, 0L)
      }
      return Math.max(fromSequenceNr, number.get)
    } finally {
      c.close()
    }
  }

  def writeSnapshot(e: SnapshotEntry): Unit = {
    val sql = s"insert into ${tableName_snapshot} (persistenceId,sequenceNr,timestamp,snapshot,snapshotClassname,serializerId,updated) values (:persistenceId,:sequenceNr,:timestamp,:snapshot,:snapshotClassname,:serializerId,current_timestamp)"
    val c = sql2o.open()

    var deleteAndRetry = false

    try {
      c.createQuery(sql)
        .addParameter("persistenceId", e.persistenceId)
        .addParameter("sequenceNr", e.sequenceNr)
        .addParameter("timestamp", e.timestamp)
        .addParameter("snapshot", e.snapshot)
        .addParameter("snapshotClassname", e.manifest)
        .addParameter("serializerId", e.serializerId.get)
        .executeUpdate
    } catch {
      case ex: Sql2oException => {
        errorHandler.onError(ex)
        throw ex
      }
    } finally {
      c.close()
    }
  }

  def findSnapshotEntry(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Option[SnapshotEntry] = {
    val sql = s"Select * from ${tableName_snapshot} where persistenceId = :persistenceId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp order by sequenceNr desc, timestamp desc limit 1"
      // Must use open due to clob/blob
      val conn = sql2o.open
      try {
        val t = conn.createQuery(sql).addParameter("persistenceId", persistenceId).addParameter("maxSequenceNr", maxSequenceNr).addParameter("maxTimestamp", maxTimestamp).executeAndFetchTable
        if (t.rows.isEmpty) {
          None
        } else {
          val row: Row = t.rows.get(0)
          val e = SnapshotEntry(
            row.getString("persistenceId"),
            row.getLong("sequenceNr"),
            row.getLong("timestamp"),
            Option(row.getObject("snapshot", classOf[Array[Byte]]).asInstanceOf[Array[Byte]]).getOrElse( Array[Byte]() ), // Empty BLOB in Oracle is returned as NULL
            row.getString("snapshotClassname"),
            Option(row.getInteger("serializerId")).map(_.toInt).filter( i => i != 0))
          Some(e)
        }
      } finally {
        if (conn != null) conn.close()
      }
  }

  def deleteSnapshot(persistenceId: String, sequenceNr: Long, timestamp: Long): Unit = {
    val sql = s"delete from ${tableName_snapshot} where persistenceId = :persistenceId  and sequenceNr = :sequenceNr  and (:timestamp = 0 OR timestamp = :timestamp)"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("persistenceId", persistenceId).addParameter("sequenceNr", sequenceNr).addParameter("timestamp", timestamp).executeUpdate
    } finally {
      c.close()
    }
  }

  def deleteSnapshotsMatching(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Unit = {
    val sql = s"delete from ${tableName_snapshot} where persistenceId = :persistenceId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("persistenceId", persistenceId).addParameter("maxSequenceNr", maxSequenceNr).addParameter("maxTimestamp", maxTimestamp).executeUpdate
    } finally {
      c.close()
    }
  }

  def writeClusterNodeAlive(nodeName: String, timestamp: OffsetDateTime, joined:Boolean): Unit = {
    var sql = s"update ${schemaPrefix}t_cluster_nodes set lastSeen = :timestamp, joined = :joined where nodeName = :nodeName"
    val c = sql2o.open()
    try {
      val joinedAsInt:Int = if (joined) 1 else 0
      val updatedRows: Int = c.createQuery(sql)
        .addParameter("nodeName", nodeName)
        .addParameter("timestamp", Date.from(timestamp.toInstant))
        .addParameter("joined", joinedAsInt)
        .executeUpdate.getResult
      if (updatedRows == 0) {
        sql = s"insert into ${schemaPrefix}t_cluster_nodes(nodeName, lastSeen, joined) values (:nodeName, :timestamp, :joined)"
        c.createQuery(sql)
          .addParameter("nodeName", nodeName)
          .addParameter("timestamp", Date.from(timestamp.toInstant))
          .addParameter("joined", joinedAsInt)
          .executeUpdate
      }
    } finally {
      c.close()
    }
  }

  def removeClusterNodeAlive(nodeName: String): Unit = {
    val sql: String = s"delete from ${schemaPrefix}t_cluster_nodes where nodeName = :nodeName"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("nodeName", nodeName).executeUpdate.getResult
    } finally {
      c.close()
    }
  }

  def findAliveClusterNodes(clusterNodesAliveSinceCheck: FiniteDuration, onlyJoined:Boolean): List[String] = {
    val aliveAfter = OffsetDateTime.now.minusSeconds(clusterNodesAliveSinceCheck.toSeconds.toInt)
    val sql = s"select nodeName from ${schemaPrefix}t_cluster_nodes where lastSeen >= :aliveAfter" + (if (onlyJoined) " and joined = 1" else "") + " order by joined desc, lastSeen desc"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("aliveAfter", Date.from(aliveAfter.toInstant)).executeScalarList(classOf[String]).asScala.toList
    } finally {
      c.close()
    }
  }
}
