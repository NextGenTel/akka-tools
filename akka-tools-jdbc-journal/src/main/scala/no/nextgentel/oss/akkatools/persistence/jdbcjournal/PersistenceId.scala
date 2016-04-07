package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import no.nextgentel.oss.akkatools.persistence.jdbcjournal.PersistenceIdType.PersistenceIdType

object PersistenceIdType extends Enumeration {
  type PersistenceIdType = Value
  val FULL = Value
  val ONLY_TYPE = Value
}

import PersistenceIdType._

case class PersistenceId(private val _typePath: String, private val _id: String, private val _persistenceIdType: PersistenceIdType = FULL) {

  def persistenceIdType() = _persistenceIdType

  def typePath() = _typePath

  def id(): String = {
    if (!isFull()) {
      throw new RuntimeException("Cannot get Id-part when persistenceIdType is not FULL")
    } else {
      _id
    }
  }

  def isFull() = FULL == _persistenceIdType

  override def toString: String = {
    "PersistenceId{" + "typePath='" + typePath + '\'' + (if (isFull) (", id='" + id + '\'') else "") + '}'
  }

}


trait PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId
  def splitChar():Option[Char]
}


// This is an impl not using the the split-functionality.
// It does no splitting at all
class PersistenceIdSplitterDefaultAkkaImpl extends PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId = {
    return new PersistenceId(persistenceId, "")
  }

  override def splitChar(): Option[Char] = None
}

object PersistenceIdSplitterLastSlashImpl {
  val WILDCARD: String = "*"
}

// Splits on the last slash
// Nice to use when persistenceId's looks like URLs
class PersistenceIdSplitterLastSlashImpl extends PersistenceIdSplitterLastSomethingImpl('/')


// Splits on the last occurrence of '_splitChar'
class PersistenceIdSplitterLastSomethingImpl(_splitChar:Char) extends PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId = {
    val i: Int = persistenceId.lastIndexOf(_splitChar)
    if (i < 0) {
      return new PersistenceId(persistenceId, "")
    } else {
      val typePath: String = persistenceId.substring(0, i + 1)
      val id: String = persistenceId.substring(i + 1, persistenceId.length)
      if (PersistenceIdSplitterLastSlashImpl.WILDCARD == id) {
        return PersistenceId(typePath, null, ONLY_TYPE)
      } else {
        return PersistenceId(typePath, id)
      }
    }
  }

  override def splitChar(): Option[Char] = Some(_splitChar)
}
