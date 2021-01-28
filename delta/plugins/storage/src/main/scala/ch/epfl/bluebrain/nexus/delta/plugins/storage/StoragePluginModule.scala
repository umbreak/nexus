package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Storages and Files wiring
  */
object StoragePluginModule extends ModuleDef {

  make[StoragePluginConfig].from { StoragePluginConfig.load(_) }

  make[EventLog[Envelope[StorageEvent]]].fromEffect { databaseEventLog[StorageEvent](_, _) }

  make[Storages].fromEffect {
    (
        cfg: StoragePluginConfig,
        log: EventLog[Envelope[StorageEvent]],
        client: HttpClient,
        permissions: Permissions,
        orgs: Organizations,
        projects: Projects,
        rcr: RemoteContextResolution,
        as: ActorSystem[Nothing],
        scheduler: Scheduler
    ) =>
      Storages(cfg.storages, log, permissions, orgs, projects)(client, UUIDF.random, Clock[UIO], scheduler, as, rcr)
  }

  make[StoragesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        storages: Storages,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      {
        val paginationConfig: PaginationConfig = cfg.storages.pagination
        val crypto: Crypto                     = cfg.storages.storageTypeConfig.encryption.crypto
        new StoragesRoutes(identities, acls, organizations, projects, storages)(
          baseUri,
          crypto,
          paginationConfig,
          s,
          cr,
          ordering
        )
      }
  }

  make[EventLog[Envelope[FileEvent]]].fromEffect { databaseEventLog[FileEvent](_, _) }

  make[Files].fromEffect {
    (
        cfg: StoragePluginConfig,
        log: EventLog[Envelope[FileEvent]],
        client: HttpClient,
        acls: Acls,
        orgs: Organizations,
        projects: Projects,
        storages: Storages,
        as: ActorSystem[Nothing],
        scheduler: Scheduler
    ) =>
      Files(cfg.files, log, acls, orgs, projects, storages)(client, UUIDF.random, Clock[UIO], scheduler, as)
  }

  make[FilesRoutes].from {
    (
        cfg: StoragePluginConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        files: Files,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      val storageConfig = cfg.storages.storageTypeConfig
      new FilesRoutes(identities, acls, organizations, projects, files)(baseUri, storageConfig, s, cr, ordering)
  }

  make[StoragePlugin].from { (storagesRoutes: StoragesRoutes, filesRoutes: FilesRoutes) =>
    new StoragePlugin(storagesRoutes, filesRoutes)
  }
}