package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenges, Location}
import akka.http.scaladsl.model.{EntityStreamSizeException, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure._
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives.uriPrefix
import ch.epfl.bluebrain.nexus.commons.http.{RdfMediaTypes, RejectionHandling}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.SparqlClientError
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.KgError._
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator
import ch.epfl.bluebrain.nexus.kg.cache.Caches._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.KgRoutes._
import ch.epfl.bluebrain.nexus.kg.routes.AppInfoRoutes.StatusGroup
import ch.epfl.bluebrain.nexus.kg.routes.Status._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import ch.epfl.bluebrain.nexus.service.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.service.routes.CassandraHealth
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{cors, corsRejectionHandler}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.circe.Json
import io.circe.parser.parse
import com.typesafe.scalalogging.Logger
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.control.NonFatal

/**
  * Generates the routes for all the platform resources
  *
 * @param resources the resources operations
  */
@SuppressWarnings(Array("MaxParameters"))
class KgRoutes(
    resources: Resources[Task],
    resolvers: Resolvers[Task],
    views: Views[Task],
    storages: Storages[Task],
    schemas: Schemas[Task],
    files: Files[Task],
    archives: Archives[Task],
    tags: Tags[Task],
    acls: Acls[Task],
    realms: Realms[Task],
    coordinator: ProjectViewCoordinator[Task]
)(implicit
    system: ActorSystem,
    clients: Clients[Task],
    cache: Caches[Task],
    config: AppConfig
) extends AuthDirectives(acls, realms)(config.http, global) {
  import clients._
  implicit val um: FromEntityUnmarshaller[String] =
    PredefinedFromEntityUnmarshallers.stringUnmarshaller
      .forContentTypes(RdfMediaTypes.`application/sparql-query`, MediaTypes.`text/plain`)

  implicit private val projectCache: ProjectCache[Task]  = cache.project
  implicit private val orgCache: OrganizationCache[Task] = cache.org
  implicit private val viewCache: ViewCache[Task]        = cache.view
  implicit private val pagination: PaginationConfig      = config.pagination

  private val healthStatusGroup = StatusGroup(CassandraHealth(system), new ClusterStatus(Cluster(system)))
  private val appInfoRoutes     = AppInfoRoutes(config.description, healthStatusGroup).routes

  private def list(implicit caller: Caller, project: ProjectResource): Route = {
    val projectPath = project.value.path
    (get & paginated & searchParams(project) & pathEndOrSingleSlash) { (pagination, params) =>
      operationName(s"/${config.http.prefix}/resources/{org}/{project}") {
        (authorizeFor(projectPath, read) & extractUri) { implicit uri =>
          val listed =
            viewCache.getDefaultElasticSearch(ProjectRef(project.uuid)).flatMap(resources.list(_, params, pagination))
          complete(listed.runWithStatus(OK))
        }
      }
    }
  }

  private def projectEvents(implicit project: ProjectResource, caller: Caller): Route =
    (get & pathPrefix("events") & pathEndOrSingleSlash) {
      new EventRoutes(acls, realms, caller).projectRoutes(project)
    }

  private def createDefault(implicit caller: Caller, subject: Subject, project: ProjectResource): Route = {
    val projectPath =
      Path.Segment(project.value.label, Path.Slash(Path.Segment(project.value.organizationLabel, Path./)))
    (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
      operationName(s"/${config.http.prefix}/resources/{org}/{project}") {
        (authorizeFor(projectPath, ResourceRoutes.write) & projectNotDeprecated) {
          entity(as[Json]) { source =>
            complete(resources.create(unconstrainedRef, source).value.runWithStatus(Created))
          }
        }
      }
    }
  }

  private def routesSelector(
      segment: IdOrUnderscore
  )(implicit subject: Subject, caller: Caller, project: ProjectResource) =
    segment match {
      case Underscore                    => routeSelectorUndescore
      case SchemaId(`archiveSchemaUri`)  => new ArchiveRoutes(archives, acls, realms).routes
      case SchemaId(`resolverSchemaUri`) => new ResolverRoutes(resolvers, tags, acls, realms).routes
      case SchemaId(`viewSchemaUri`)     => new ViewRoutes(views, tags, acls, realms, coordinator).routes
      case SchemaId(`shaclSchemaUri`)    => new SchemaRoutes(schemas, tags, acls, realms).routes
      case SchemaId(`fileSchemaUri`)     => new FileRoutes(files, resources, tags, acls, realms).routes
      case SchemaId(`storageSchemaUri`)  => new StorageRoutes(storages, tags, acls, realms).routes
      case SchemaId(schema)              =>
        new ResourceRoutes(resources, tags, acls, realms, schema.ref).routes ~ list ~ createDefault
      case _                             => reject()
    }

  private def routeSelectorUndescore(implicit subject: Subject, caller: Caller, project: ProjectResource) =
    pathPrefix(IdSegment) { id =>
      // format: off
      onSuccess(resources.fetchSchema(Id(ProjectRef(project.uuid), id)).value.runToFuture) {
        case Right(`resolverRef`)         =>  new ResolverRoutes(resolvers, tags, acls, realms).routes(id)
        case Right(`viewRef`)             =>  new ViewRoutes(views, tags, acls, realms, coordinator).routes(id)
        case Right(`shaclRef`)            => new SchemaRoutes(schemas, tags, acls, realms).routes(id)
        case Right(`fileRef`)             => new FileRoutes(files, resources, tags, acls, realms).routes(id)
        case Right(`storageRef`)          => new StorageRoutes(storages, tags, acls, realms).routes(id)
        case Right(schema)                => new ResourceRoutes(resources, tags, acls, realms, schema).routes(id) ~ list ~ createDefault
        case Left(_: Rejection.NotFound)  => new ResourceRoutes(resources, tags, acls, realms, unconstrainedRef).routes(id) ~ list ~ createDefault
        case Left(err) => complete(err)
      }
      // format: on
    } ~ list ~ createDefault

  def routes: Route =
    wrap(
      concat(
        extractCaller { implicit caller =>
          implicit val subject: Subject = caller.subject
          concat(
            (get & pathPrefix(config.http.prefix / "events") & pathEndOrSingleSlash) {
              new GlobalEventRoutes(acls, realms, caller).routes
            },
            (get & pathPrefix(config.http.prefix / "resources" / "events") & pathEndOrSingleSlash) {
              new GlobalEventRoutes(acls, realms, caller).routes
            },
            (get & pathPrefix(config.http.prefix / "resources" / Segment / "events") & pathEndOrSingleSlash) { label =>
              org(label).apply { implicit organization =>
                new EventRoutes(acls, realms, caller).organizationRoutes(organization)
              }
            },
            pathPrefix(config.http.prefix / Segment) { resourceSegment =>
              project.apply { implicit project =>
                resourceSegment match {
                  case "resources" =>
                    pathPrefix(IdSegmentOrUnderscore)(routesSelector) ~ list ~ createDefault ~ projectEvents
                  case segment     => mapToSchema(segment).map(routesSelector).getOrElse(reject())
                }
              }
            }
          )
        },
        appInfoRoutes
      )
    )

  private def mapToSchema(resourceSegment: String): Option[SchemaId] =
    resourceSegment match {
      case "archives"  => Some(SchemaId(archiveSchemaUri))
      case "views"     => Some(SchemaId(viewSchemaUri))
      case "resolvers" => Some(SchemaId(resolverSchemaUri))
      case "schemas"   => Some(SchemaId(shaclSchemaUri))
      case "storages"  => Some(SchemaId(storageSchemaUri))
      case "files"     => Some(SchemaId(fileSchemaUri))
      case _           => None

    }
}

object KgRoutes {

  private[this] val logger = Logger[this.type]

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  final val exceptionHandler: ExceptionHandler = {
    def completeGeneric(): Route =
      complete(InternalError("The system experienced an unexpected error, please try again later."): KgError)

    ExceptionHandler {
      // suppress errors from withSizeLimit directive
      case EntityStreamSizeException(limit, actual) =>
        complete(FileSizeExceed(limit, actual): KgError)
      case err: NotFound                            =>
        // suppress errors for not found
        complete(err: KgError)
      case AuthenticationFailed                     =>
        // suppress errors for authentication failures
        val status = KgError.kgErrorStatusFrom(AuthenticationFailed)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        complete((status, List(header), AuthenticationFailed: KgError))
      case AuthorizationFailed                      =>
        // suppress errors for authorization failures
        complete(AuthorizationFailed: KgError)
      case err: UnacceptedResponseContentType       =>
        // suppress errors for unaccepted response content type
        complete(err: KgError)
      case err: ProjectNotFound                     =>
        // suppress error
        complete(err: KgError)
      case err: OrganizationNotFound                =>
        // suppress error
        complete(err: KgError)
      case err: ProjectIsDeprecated                 =>
        // suppress error
        complete(err: KgError)
      case err: RemoteFileNotFound                  =>
        // suppress error
        complete(err: KgError)
      case err: StorageClientError.InvalidPath      =>
        // suppress error
        complete(StatusCodes.BadRequest -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError.NotFound         =>
        // suppress error
        complete(StatusCodes.NotFound -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError                  =>
        // suppress error
        logger.error(s"Received unexpected response from remote storage: '${err.message}'")
        complete(
          StatusCodes.BadGateway -> (RemoteStorageError(
            "The downstream storage service experienced an unexpected error, please try again later."
          ): KgError)
        )
      case UnsupportedOperation                     =>
        // suppress error
        complete(UnsupportedOperation: KgError)
      case err: InvalidOutputFormat                 =>
        // suppress error
        complete(err: KgError)
      case ElasticSearchClientError(status, body)   =>
        parse(body) match {
          case Right(json) => complete(status -> json)
          case Left(_)     => complete(status -> body)
        }
      case SparqlClientError(status, body)          => complete(status -> body)
      case f: ElasticSearchFailure                  =>
        logger.error(s"Received unexpected response from ES: '${f.message}' with body: '${f.body}'")
        completeGeneric()
      case err: KgError                             =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
      case NonFatal(err)                            =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
    }
  }

  /**
    * @return a complete RejectionHandler for all library and code rejections
    */
  final val rejectionHandler: RejectionHandler = {
    val custom = RejectionHandling.apply { r: Rejection =>
      logger.debug(s"Handling rejection '$r'")
      r
    }
    corsRejectionHandler withFallback custom withFallback RejectionHandling.notFound withFallback RejectionHandler.default
  }

  /**
    * Wraps the provided route with CORS, rejection and exception handling.
    *
    * @param route the route to wrap
    */
  final def wrap(route: Route)(implicit hc: HttpConfig): Route = {
    val corsSettings = CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
    cors(corsSettings) {
      handleExceptions(exceptionHandler) {
        handleRejections(rejectionHandler) {
          uriPrefix(hc.publicUri) {
            route
          }
        }
      }
    }
  }
}
