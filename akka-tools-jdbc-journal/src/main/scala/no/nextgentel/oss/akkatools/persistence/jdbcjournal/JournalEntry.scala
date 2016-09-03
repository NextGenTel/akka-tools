package no.nextgentel.oss.akkatools.persistence.jdbcjournal

case class JournalEntry(persistenceId: PersistenceIdSingle, payload: AnyRef) {
  def payloadAs[T](): T = payload.asInstanceOf[T]
}
