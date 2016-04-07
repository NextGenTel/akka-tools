package no.nextgentel.oss.akkatools.persistence.jdbcjournal

case class JournalEntry(persistenceId: PersistenceId, payload: AnyRef) {
  def payloadAs[T](): T = payload.asInstanceOf[T]
}
