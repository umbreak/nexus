package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.RemoteSse
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig.RemoteSourceClientConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.{MetadataPredicates, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, SuccessMessage}
import fs2.{Chunk, Stream}
import monix.bio.{IO, Task}

/**
  * A Stream source generated by reading events from remote source and fetching a given resource
  * and transforming it into [[BlazegraphIndexingStreamEntry]]
  */
trait RemoteIndexingSource {
  def apply(
      source: RemoteProjectSource,
      offset: Offset
  ): Stream[Task, Chunk[Message[BlazegraphIndexingStreamEntry]]]
}

object RemoteIndexingSource {

  private[indexing] type RemoteProjectStream  = (RemoteProjectSource, Offset) => Stream[Task, (Offset, RemoteSse)]
  private[indexing] type RemoteResourceNQuads =
    (RemoteProjectSource, Iri, Option[TagLabel]) => HttpResult[Option[NQuads]]

  /**
    * Create a stream source generated by reading events from remote source and fetching a given resource
    * and transforming it into [[BlazegraphIndexingStreamEntry]]
    */
  def apply(
      remoteProjectStream: RemoteProjectStream,
      remoteResourceNQuads: RemoteResourceNQuads,
      config: RemoteSourceClientConfig,
      metadataPredicates: MetadataPredicates
  ): RemoteIndexingSource = (source: RemoteProjectSource, offset: Offset) => {
    remoteProjectStream(source, offset)
      .map { case (offset, sse: RemoteSse) =>
        SuccessMessage(
          offset,
          sse.instant,
          s"remote-${source.endpoint}-${source.project}-${sse.resourceId}",
          sse.rev,
          sse,
          Vector.empty
        )
      }
      .groupWithin(config.maxBatchSize, config.retryDelay)
      .discardDuplicates()
      .evalMapFilterValue(sse => remoteResourceNQuads(source, sse.resourceId, source.resourceTag).map(_.map((_, sse))))
      .evalMapValue { case (quads, sse) =>
        IO.fromEither(BlazegraphIndexingStreamEntry.fromNQuads(sse.resourceId, quads, metadataPredicates))
      }
  }
}
