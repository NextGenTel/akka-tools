package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.time.{OffsetDateTime, ZoneId}
import java.util.Date
import javax.sql.DataSource

import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import org.sql2o.data.{Row, Table}
import org.sql2o._
import org.sql2o.quirks.OracleQuirks

import scala.concurrent.duration.FiniteDuration

case class JournalEntryDto(persistenceId: PersistenceId, sequenceNr:Long, persistentRepr:Array[Byte], payloadWriteOnly:String, timestamp:OffsetDateTime)
case class SnapshotEntry(persistenceId:String, sequenceNr:Long, timestamp:Long, snapshot:Array[Byte], manifest:String, serializerId:Option[Int])


trait StorageRepo {
  def insertPersistentReprList(dtoList: Seq[JournalEntryDto])

  def deleteJournalEntryTo(persistenceId: PersistenceId, toSequenceNr: Long)

  def loadJournalEntries(persistenceId: PersistenceId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto]

  def findHighestSequenceNr(persistenceId: PersistenceId, fromSequenceNr: Long): Long

  def writeSnapshot(snapshotEntry: SnapshotEntry)

  def findSnapshotEntry(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Option[SnapshotEntry]

  def deleteSnapshot(persistenceId: String, sequenceNr: Long, timestamp: Long)

  def deleteSnapshotsMatching(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long)
}

trait JdbcJournalErrorHandler {
  def onError(e:Exception)
}

class JdbcJournalDetectFatalOracleErrorHandler(fatalErrorHandler:JdbcJournalErrorHandler) extends JdbcJournalErrorHandler {
  override def onError(e: Exception): Unit = {
    if (e.getMessage != null && e.getMessage.contains("ORA-00001: unique constraint")) {
      fatalErrorHandler.onError(e)
    }
  }
}

class StorageRepoImpl(sql2o: Sql2o, schemaName: Option[String], _errorHandler:JdbcJournalErrorHandler) extends StorageRepo with ClusterNodeRepo {

  // backward compatible constructor
  def this(sql2o:Sql2o, schemaName:String, _errorHandler:JdbcJournalErrorHandler) = this(sql2o, Option(schemaName), _errorHandler)
  def this(dataSource:DataSource, schemaName:Option[String], _errorHandler:JdbcJournalErrorHandler) = this(new Sql2o(dataSource, new OracleQuirks()), schemaName, _errorHandler)

  import scala.collection.JavaConverters._

  // wrap it
  val errorHandler = new JdbcJournalDetectFatalOracleErrorHandler(_errorHandler)

  lazy val schemaPrefix = schemaName.map( s => s + ".").getOrElse("")

  def loadJournalEntries(persistenceId: PersistenceId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto] = {
    val sequenceNrColumnName = persistenceId match {
      case p:PersistenceIdSingle    => "sequenceNr"
      case p:PersistenceIdTagOnly => "journalIndex"
    }

    val sql = s"select * from (select typePath, id, $sequenceNrColumnName, persistentRepr, updated from ${schemaPrefix}t_journal where typePath = :typePath " +
      (if (persistenceId.isInstanceOf[PersistenceIdSingle]) " and id = :id " else "") +
      s" and $sequenceNrColumnName >= :fromSequenceNr and $sequenceNrColumnName <= :toSequenceNr and persistentRepr is not null order by $sequenceNrColumnName) where rownum <= :max"

      // Must use open due to clob/blob
      val conn = sql2o.open
      try {
        val query = conn.createQuery(sql).addParameter("typePath", persistenceId.tag)
        persistenceId match {
          case p:PersistenceIdSingle =>
            query.addParameter("id", p.uniqueId)
          case _ =>
        }

        query.addParameter("fromSequenceNr", fromSequenceNr).addParameter("toSequenceNr", toSequenceNr).addParameter("max", max)
        val table = query.executeAndFetchTable
        table.rows.asScala.toList.map{
          r:Row =>
            JournalEntryDto(
              persistenceId,
              r.getLong(sequenceNrColumnName),
              r.getObject("persistentRepr", classOf[Array[Byte]]),
              null,
              OffsetDateTime.ofInstant(r.getDate("updated").toInstant(), ZoneId.systemDefault()))
        }
      } finally {
        if (conn != null) conn.close()
      }
  }

  def insertPersistentReprList(dtoList: Seq[JournalEntryDto]) {

    val sql = s"insert into ${schemaPrefix}t_journal (typePath, id, sequenceNr, journalIndex, persistentRepr, payload_write_only, updated) " +
      s"values (:typePath, :id, :sequenceNr,${schemaPrefix}s_journalIndex_seq.nextval, :persistentRepr, :payload_write_only, sysdate)"

    // Insert the whole list in one transaction
    val c = sql2o.beginTransaction()
    try {

      dtoList.foreach {
        dto =>
          val insert = c.createQuery(sql)
          try {

            insert.addParameter("typePath", dto.persistenceId.tag)
              .addParameter("id", dto.persistenceId.uniqueId)
              .addParameter("sequenceNr", dto.sequenceNr)
              .addParameter("persistentRepr", dto.persistentRepr)
              .addParameter("payload_write_only", dto.payloadWriteOnly)
              .executeUpdate
          } catch {
            case e: Sql2oException => {
              val exception = new Exception("Error updating journal for persistenceId=" + dto.persistenceId + " and sequenceNr=" + dto.sequenceNr + ": " + e.getMessage, e)
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
  def deleteJournalEntryTo(persistenceId: PersistenceId, toSequenceNr: Long) {
    val sql = s"update ${schemaPrefix}t_journal set persistentRepr = null, payload_write_only = null where typePath = :typePath and id = :id and sequenceNr <= :toSequenceNr"
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
      case p:PersistenceIdTagOnly => "journalIndex"
    }


    val sql = s"select max($sequenceNrColumnName) from ${schemaPrefix}t_journal where typePath = :typePath " + (if (persistenceId.isInstanceOf[PersistenceIdSingle]) " and id = :id " else "") + s" and $sequenceNrColumnName>=:fromSequenceNr"
    val c = sql2o.open()
    try {

      val query = c.createQuery(sql).addParameter("typePath", persistenceId.tag)
      persistenceId match {
        case p:PersistenceIdSingle =>
          query.addParameter("id", p.uniqueId)
        case _ =>
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

  def writeSnapshot(e: SnapshotEntry) {
    val sql = s"insert into ${schemaPrefix}t_snapshot (persistenceId,sequenceNr,timestamp,snapshot,snapshotClassname,serializerId,updated) values (:persistenceId,:sequenceNr,:timestamp,:snapshot,:snapshotClassname,:serializerId,sysdate)"
    val c = sql2o.open()
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
    val sql = s"select * from (Select * from ${schemaPrefix}t_snapshot where persistenceId = :persistenceId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp order by timestamp desc) where rownum <= 1"
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

  def deleteSnapshot(persistenceId: String, sequenceNr: Long, timestamp: Long) {
    val sql = s"delete from ${schemaPrefix}t_snapshot where persistenceId = :persistenceId  and sequenceNr = :sequenceNr  and (:timestamp = 0 OR timestamp = :timestamp)"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("persistenceId", persistenceId).addParameter("sequenceNr", sequenceNr).addParameter("timestamp", timestamp).executeUpdate
    } finally {
      c.close()
    }
  }

  def deleteSnapshotsMatching(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long) {
    val sql = s"delete from ${schemaPrefix}t_snapshot where persistenceId = :persistenceId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp"
    val c = sql2o.open()
    try {
      c.createQuery(sql).addParameter("persistenceId", persistenceId).addParameter("maxSequenceNr", maxSequenceNr).addParameter("maxTimestamp", maxTimestamp).executeUpdate
    } finally {
      c.close()
    }
  }

  def writeClusterNodeAlive(nodeName: String, timestamp: OffsetDateTime, joined:Boolean) {
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

  def removeClusterNodeAlive(nodeName: String) {
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