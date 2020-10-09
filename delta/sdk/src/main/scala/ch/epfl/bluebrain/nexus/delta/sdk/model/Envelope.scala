package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.persistence.query.Offset

/**
  * A typed event envelope.
  *
  * @param event         the event
  * @param offset        the event offset
  * @param persistenceId the event persistence id
  * @param sequenceNr    the event sequence number
  * @param timestamp     the event timestamp
  */
final case class Envelope[E <: Event](
    event: E,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    timestamp: Long
)
