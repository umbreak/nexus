package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.CacheIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, ExternalIndexingConfig}
import com.typesafe.config.Config
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import scala.annotation.nowarn
import scala.concurrent.duration._
import scala.util.Try

/**
  * Configuration for the ElasticSearchView plugin.
  *
  * @param base          the base uri to the Elasticsearch HTTP endpoint
  * @param client        configuration of the Elasticsearch client
  * @param aggregate     configuration of the underlying aggregate
  * @param keyValueStore configuration of the underlying key/value store
  * @param pagination    configuration for how pagination should behave in listing operations
  * @param cacheIndexing configuration of the cache indexing process
  * @param indexing      configuration of the external indexing process
  * @param maxViewRefs   configuration of the maximum number of view references allowed on an aggregated view
  * @param idleTimeout   the maximum idle duration in between events on the indexing stream after which the stream will be stopped
  */
final case class ElasticSearchViewsConfig(
    base: Uri,
    client: HttpClientConfig,
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    indexing: ExternalIndexingConfig,
    maxViewRefs: Int,
    idleTimeout: Duration
)

object ElasticSearchViewsConfig {

  /**
    * Converts a [[Config]] into an [[ElasticSearchViewsConfig]]
    */
  def load(config: Config): ElasticSearchViewsConfig =
    ConfigSource
      .fromConfig(config)
      .at("plugins.elasticsearch")
      .loadOrThrow[ElasticSearchViewsConfig]

  @nowarn("cat=unused")
  implicit private val uriConfigReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str))
      .filter(_.isAbsolute)
      .toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val elasticSearchViewsConfigReader: ConfigReader[ElasticSearchViewsConfig] =
    deriveReader[ElasticSearchViewsConfig].emap { c =>
      Either.cond(
        c.idleTimeout.gteq(10.minutes),
        c,
        new FailureReason { override def description: String = "'idle-timeout' must be greater than 10 minutes" }
      )
    }
}
