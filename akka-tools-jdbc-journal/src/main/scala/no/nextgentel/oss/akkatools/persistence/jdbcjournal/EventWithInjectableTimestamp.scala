package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.time.OffsetDateTime

trait EventWithInjectableTimestamp {
  def cloneWithInjectedTimestamp(timestamp:OffsetDateTime):AnyRef
}
