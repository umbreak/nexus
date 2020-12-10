package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageGen._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.{DiskStorageConfig, StorageTypeConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageState.Initial
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{PermissionsDummy, ProjectSetup, SSEUtils}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues}
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors}

import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class StoragesSpec
    extends AbstractDBSpec
    with Matchers
    with IOValues
    with IOFixedClock
    with Inspectors
    with CirceLiteral
    with CancelAfterFailure
    with ConfigFixtures
    with StorageFixtures {

  implicit private val sc: Scheduler = Scheduler.global

  private val epoch = Instant.EPOCH
  private val time2 = Instant.ofEpochMilli(10L)
  private val realm = Label.unsafe("myrealm")
  private val bob   = User("Bob", realm)
  private val alice = User("Alice", realm)

  private val project = ProjectRef.unsafe("org", "proj")

  private val access: Storages.StorageAccess = {
    case disk: DiskStorageValue         =>
      if (disk.volume != diskFields.volume) IO.raiseError(StorageNotAccessible(dId, "wrong volume")) else IO.unit
    case s3: S3StorageValue             =>
      if (s3.bucket != s3Fields.bucket) IO.raiseError(StorageNotAccessible(dId, "wrong bucket")) else IO.unit
    case remote: RemoteDiskStorageValue =>
      if (remote.endpoint != remoteFields.endpoint.value) IO.raiseError(StorageNotAccessible(dId, "wrong endpoint"))
      else IO.unit
  }

  val allowedPerms = Set(
    diskFields.readPermission.value,
    diskFields.writePermission.value,
    s3Fields.readPermission.value,
    s3Fields.writePermission.value,
    remoteFields.readPermission.value,
    remoteFields.writePermission.value
  )

  val perms = PermissionsDummy(allowedPerms).accepted

  private val eval = evaluate(access, perms, config)(_, _)

  "The Storages state machine" when {

    "evaluating an incoming command" should {

      "create a new event from a CreateStorage command" in {
        val disk   = CreateStorage(dId, project, diskFields.copy(maxFileSize = Some(1)), json"""{"disk": "c"}""", bob)
        val s3     = CreateStorage(s3Id, project, s3Fields.copy(maxFileSize = Some(2)), json"""{"s3": "c"}""", bob)
        val remote =
          CreateStorage(rdId, project, remoteFields.copy(maxFileSize = Some(3)), json"""{"remote": "c"}""", bob)

        forAll(List(disk, s3, remote)) { cmd =>
          eval(Initial, cmd).accepted shouldEqual
            StorageCreated(cmd.id, cmd.project, cmd.fields.toValue(config).value, cmd.source, 1, epoch, bob)
        }
      }

      "create a new event from a UpdateStorage command" in {
        val disk          = UpdateStorage(dId, project, diskFields, json"""{"disk": "u"}""", 1, alice)
        val diskCurrent   = currentState(dId, project, diskVal.copy(maxFileSize = 1))
        val s3            = UpdateStorage(s3Id, project, s3Fields, json"""{"s3": "u"}""", 1, alice)
        val s3Current     = currentState(s3Id, project, s3Val.copy(maxFileSize = 2))
        val remote        = UpdateStorage(rdId, project, remoteFields, json"""{"remote": "u"}""", 1, alice)
        val remoteCurrent = currentState(rdId, project, remoteVal.copy(maxFileSize = 3))

        forAll(List(diskCurrent -> disk, s3Current -> s3, remoteCurrent -> remote)) { case (current, cmd) =>
          eval(current, cmd).accepted shouldEqual
            StorageUpdated(cmd.id, cmd.project, cmd.fields.toValue(config).value, cmd.source, 2, epoch, alice)
        }
      }

      "create a new event from a TagStorage command" in {
        val current = currentState(dId, project, diskVal, rev = 3)
        eval(current, TagStorage(dId, project, 2, Label.unsafe("myTag"), 3, alice)).accepted shouldEqual
          StorageTagAdded(dId, project, 2, Label.unsafe("myTag"), 4, epoch, alice)
      }

      "create a new event from a DeprecateStorage command" in {
        val current = currentState(dId, project, diskVal, rev = 3)
        eval(current, DeprecateStorage(dId, project, 3, alice)).accepted shouldEqual
          StorageDeprecated(dId, project, 4, epoch, alice)
      }

      "reject with IncorrectRev" in {
        val current  = currentState(dId, project, diskVal)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with StorageNotAccessible" in {
        val inaccessibleDiskVal   = diskFields.copy(volume = Files.createTempDirectory("other"))
        val inaccessibleS3Val     = s3Fields.copy(bucket = "other")
        val inaccessibleRemoteVal = remoteFields.copy(endpoint = Some("other.com"))
        val diskCurrent           = currentState(dId, project, diskVal)
        val s3Current             = currentState(s3Id, project, s3Val)
        val remoteCurrent         = currentState(rdId, project, remoteVal)

        forAll(List(dId -> inaccessibleDiskVal, s3Id -> inaccessibleS3Val, rdId -> inaccessibleRemoteVal)) {
          case (id, value) =>
            val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
            eval(Initial, createCmd).rejected shouldBe a[StorageNotAccessible]
        }

        forAll(
          List(
            diskCurrent   -> inaccessibleDiskVal,
            s3Current     -> inaccessibleS3Val,
            remoteCurrent -> inaccessibleRemoteVal
          )
        ) { case (current, value) =>
          val updateCmd = UpdateStorage(current.id, project, value, Json.obj(), 1L, alice)
          eval(current, updateCmd).rejected shouldBe a[StorageNotAccessible]
        }
      }

      "reject with InvalidMaxFileSize" in {
        val exceededSizeDiskVal   = diskFields.copy(maxFileSize = Some(100))
        val exceededSizeS3Val     = s3Fields.copy(maxFileSize = Some(100))
        val exceededSizeRemoteVal = remoteFields.copy(maxFileSize = Some(100))
        val diskCurrent           = currentState(dId, project, diskVal)
        val s3Current             = currentState(s3Id, project, s3Val)
        val remoteCurrent         = currentState(rdId, project, remoteVal)

        forAll(
          List(
            (dId, exceededSizeDiskVal, config.disk.defaultMaxFileSize),
            (s3Id, exceededSizeS3Val, config.amazon.value.defaultMaxFileSize),
            (rdId, exceededSizeRemoteVal, config.remoteDisk.value.defaultMaxFileSize)
          )
        ) { case (id, value, maxFileSize) =>
          val createCmd = CreateStorage(id, project, value, Json.obj(), bob)
          eval(Initial, createCmd).rejected shouldEqual InvalidMaxFileSize(id, 100, maxFileSize)
        }

        forAll(
          List(
            (diskCurrent, exceededSizeDiskVal, config.disk.defaultMaxFileSize),
            (s3Current, exceededSizeS3Val, config.amazon.get.defaultMaxFileSize),
            (remoteCurrent, exceededSizeRemoteVal, config.remoteDisk.value.defaultMaxFileSize)
          )
        ) { case (current, value, maxFileSize) =>
          val updateCmd = UpdateStorage(current.id, project, value, Json.obj(), 1L, alice)
          eval(current, updateCmd).rejected shouldEqual InvalidMaxFileSize(current.id, 100, maxFileSize)
        }
      }

      "reject with StorageAlreadyExists" in {
        val current = currentState(dId, project, diskVal)
        eval(current, CreateStorage(dId, project, diskFields, Json.obj(), bob)).rejectedWith[StorageAlreadyExists]
      }

      "reject with StorageNotFound" in {
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(Initial, cmd).rejectedWith[StorageNotFound]
        }
      }

      "reject with StorageIsDeprecated" in {
        val current  = currentState(dId, project, diskVal, rev = 2, deprecated = true)
        val commands = List(
          UpdateStorage(dId, project, diskFields, Json.obj(), 2, alice),
          TagStorage(dId, project, 1L, Label.unsafe("tag"), 2, alice),
          DeprecateStorage(dId, project, 2, alice)
        )
        forAll(commands) { cmd =>
          eval(current, cmd).rejectedWith[StorageIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = currentState(dId, project, diskVal)
        eval(current, TagStorage(dId, project, 3L, Label.unsafe("myTag"), 1L, alice)).rejected shouldEqual
          RevisionNotFound(provided = 3L, current = 1L)
      }

      "reject with DifferentStorageType" in {
        val diskCurrent   = currentState(dId, project, diskVal)
        val s3Current     = currentState(s3Id, project, s3Val)
        val remoteCurrent = currentState(rdId, project, remoteVal)
        val list          = List(
          diskCurrent   -> UpdateStorage(dId, project, s3Fields, Json.obj(), 1, alice),
          diskCurrent   -> UpdateStorage(dId, project, remoteFields, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, diskFields, Json.obj(), 1, alice),
          s3Current     -> UpdateStorage(s3Id, project, remoteFields, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, diskFields, Json.obj(), 1, alice),
          remoteCurrent -> UpdateStorage(rdId, project, s3Fields, Json.obj(), 1, alice)
        )
        forAll(list) { case (current, cmd) =>
          eval(current, cmd).rejectedWith[DifferentStorageType]
        }
      }
    }

    "reject with PermissionsAreNotDefined" in {
      val read         = Permission.unsafe("wrong/read")
      val write        = Permission.unsafe("wrong/write")
      val storageValue = s3Fields.copy(readPermission = Some(read), writePermission = Some(write))
      val current      = currentState(s3Id, project, s3Val)
      val list         = List(
        Initial -> CreateStorage(s3Id, project, storageValue, Json.obj(), bob),
        current -> UpdateStorage(s3Id, project, storageValue, Json.obj(), 1, alice)
      )
      forAll(list) { case (current, cmd) =>
        eval(current, cmd).rejected shouldEqual PermissionsAreNotDefined(Set(read, write))
      }
    }

    "reject with InvalidStorageType" in {
      val s3Current                 = currentState(s3Id, project, s3Val)
      val remoteCurrent             = currentState(rdId, project, remoteVal)
      val list                      = List(
        Initial       -> CreateStorage(s3Id, project, s3Fields, Json.obj(), bob),
        Initial       -> CreateStorage(s3Id, project, remoteFields, Json.obj(), bob),
        s3Current     -> UpdateStorage(s3Id, project, s3Fields, Json.obj(), 1, alice),
        remoteCurrent -> UpdateStorage(rdId, project, remoteFields, Json.obj(), 1, alice)
      )
      // format: off
      val config: StorageTypeConfig = StorageTypeConfig(
        disk        = DiskStorageConfig(Files.createTempDirectory("disk"), DigestAlgorithm.default, permissions.read, permissions.write, showLocation = false, 50),
        amazon      = None,
        remoteDisk  = None
      )
      // format: on
      val eval                      = evaluate(access, perms, config)(_, _)
      forAll(list) { case (current, cmd) =>
        eval(current, cmd).rejectedWith[InvalidStorageType]
      }
    }

    "producing next state" should {

      "from a new StorageCreated event" in {
        val event     = StorageCreated(dId, project, diskVal, json"""{"disk": "c"}""", 1, epoch, bob)
        val nextState = currentState(dId, project, diskVal, event.source, createdBy = bob, updatedBy = bob)

        next(Initial, event) shouldEqual nextState
        next(nextState, event) shouldEqual nextState
      }

      "from a new StorageUpdated event" in {
        val event = StorageUpdated(dId, project, diskVal, json"""{"disk": "c"}""", 2, time2, alice)
        next(Initial, event) shouldEqual Initial

        val currentDisk = diskVal.copy(maxFileSize = 2)
        val current     = currentState(dId, project, currentDisk, Json.obj(), createdBy = bob, updatedBy = bob)

        next(current, event) shouldEqual
          current.copy(rev = 2L, value = event.value, source = event.source, updatedAt = time2, updatedBy = alice)
      }

      "from a new StorageTagAdded event" in {
        val tag1    = Label.unsafe("tag1")
        val tag2    = Label.unsafe("tag2")
        val event   = StorageTagAdded(dId, project, 1, tag2, 3, time2, alice)
        val current = currentState(dId, project, diskVal, Json.obj(), tags = Map(tag1 -> 2), rev = 2)

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual
          current.copy(rev = 3, updatedAt = time2, updatedBy = alice, tags = Map(tag1 -> 2, tag2 -> 1))
      }

      "from a new StorageDeprecated event" in {
        val event   = StorageDeprecated(dId, project, 2, time2, alice)
        val current = currentState(dId, project, diskVal, Json.obj())

        next(Initial, event) shouldEqual Initial

        next(current, event) shouldEqual current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = alice)
      }
    }
  }

  "The Storages operations bundle" when {
    val uuid                      = UUID.randomUUID()
    implicit val uuidF: UUIDF     = UUIDF.fixed(uuid)
    implicit val caller: Caller   = Caller(bob, Set(bob, Group("mygroup", realm), Authenticated(realm)))
    implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

    val org                      = Label.unsafe("org")
    val orgDeprecated            = Label.unsafe("org-deprecated")
    val base                     = nxv.base
    val project                  = ProjectGen.project("org", "proj", base = base, mappings = ApiMappings.default)
    val deprecatedProject        = ProjectGen.project("org", "proj-deprecated")
    val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")
    val projectRef               = project.ref
    val storageConfig            = StoragesConfig(aggregate, keyValueStore, pagination, indexing, config)

    val tag = Label.unsafe("tag")

    def projectSetup =
      ProjectSetup
        .init(
          orgsToCreate = org :: orgDeprecated :: Nil,
          projectsToCreate = project :: deprecatedProject :: projectWithDeprecatedOrg :: Nil,
          projectsToDeprecate = deprecatedProject.ref :: Nil,
          organizationsToDeprecate = orgDeprecated :: Nil
        )

    val storages = (for {
      eventLog         <- EventLog.postgresEventLog[Envelope[StorageEvent]](EventLogUtils.toEnvelope).hideErrors
      (orgs, projects) <- projectSetup
      storages         <- Storages(storageConfig, eventLog, perms, orgs, projects, access)
    } yield storages).accepted

    "creating a storage" should {

      "succeed with the id present on the payload" in {
        val payload = diskFieldsJson deepMerge Json.obj(keywords.id -> dId.asJson)
        storages.create(projectRef, payload).accepted shouldEqual
          resourceFor(dId, projectRef, diskVal, payload, createdBy = bob, updatedBy = bob)
      }

      "succeed with the id present on the payload and passed" in {
        val payload = s3FieldsJson deepMerge Json.obj(keywords.id -> s3Id.asJson)
        storages.create(StringSegment("s3-storage"), projectRef, payload).accepted shouldEqual
          resourceFor(s3Id, projectRef, s3Val, payload, createdBy = bob, updatedBy = bob)
      }

      "succeed with the passed id" in {
        storages.create(IriSegment(rdId), projectRef, remoteFieldsJson).accepted shouldEqual
          resourceFor(rdId, projectRef, remoteVal, remoteFieldsJson, createdBy = bob, updatedBy = bob)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        val payload = s3FieldsJson deepMerge Json.obj(keywords.id -> s3Id.asJson)
        storages.create(IriSegment(otherId), projectRef, payload).rejected shouldEqual
          UnexpectedStorageId(id = otherId, payloadId = s3Id)
      }

      "reject if it already exists" in {
        storages.create(IriSegment(s3Id), projectRef, s3FieldsJson).rejected shouldEqual
          StorageAlreadyExists(s3Id, projectRef)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        storages.create(projectRef, s3FieldsJson).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        storages.create(deprecatedProject.ref, s3FieldsJson).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        storages.create(projectWithDeprecatedOrg.ref, s3FieldsJson).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating a storage" should {

      "succeed" in {
        val payload = diskFieldsJson deepMerge json"""{"default": false, "maxFileSize": 40}"""
        storages.update(IriSegment(dId), projectRef, 2, payload).accepted shouldEqual
          resourceFor(dId, projectRef, diskValUpdate, payload, rev = 3, createdBy = bob, updatedBy = bob)
      }

      "reject if it doesn't exists" in {
        storages.update(IriSegment(nxv + "other"), projectRef, 1, diskFieldsJson).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.update(IriSegment(dId), projectRef, 2, diskFieldsJson).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        storages.update(IriSegment(dId), deprecatedProject.ref, 2, diskFieldsJson).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        storages.update(IriSegment(dId), projectWithDeprecatedOrg.ref, 2, diskFieldsJson).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "tagging a storage" should {

      "succeed" in {
        storages.tag(IriSegment(rdId), projectRef, tag, tagRev = 1, 1).accepted shouldEqual
          resourceFor(
            rdId,
            projectRef,
            remoteVal,
            remoteFieldsJson,
            rev = 2,
            createdBy = bob,
            updatedBy = bob,
            tags = Map(tag -> 1)
          )
      }

      "reject if it doesn't exists" in {
        storages.tag(IriSegment(nxv + "other"), projectRef, tag, tagRev = 1, 1).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.tag(IriSegment(rdId), projectRef, tag, tagRev = 2, 2).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        storages.tag(IriSegment(rdId), deprecatedProject.ref, tag, tagRev = 2, 2).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        storages.tag(IriSegment(rdId), projectWithDeprecatedOrg.ref, tag, tagRev = 2, 2).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "deprecating a storage" should {

      "succeed" in {
        val payload = s3FieldsJson deepMerge json"""{"@id": "$s3Id", "default": false}"""
        storages.deprecate(IriSegment(s3Id), projectRef, 2).accepted shouldEqual
          resourceFor(
            s3Id,
            projectRef,
            s3Val.copy(default = false),
            payload,
            rev = 3,
            deprecated = true,
            createdBy = bob,
            updatedBy = bob
          )
      }

      "reject if it doesn't exists" in {
        storages.deprecate(IriSegment(nxv + "other"), projectRef, 1).rejectedWith[StorageNotFound]
      }

      "reject if the revision passed is incorrect" in {
        storages.deprecate(IriSegment(s3Id), projectRef, 5).rejected shouldEqual
          IncorrectRev(provided = 5, expected = 3)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.deprecate(IriSegment(s3Id), projectRef, 3).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        storages.deprecate(IriSegment(s3Id), deprecatedProject.ref, 1).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(deprecatedProject.ref))
      }

      "reject if organization is deprecated" in {
        storages.tag(IriSegment(s3Id), projectWithDeprecatedOrg.ref, tag, 1, 2).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }

    }

    "fetching a storage" should {
      val resourceRev1 = resourceFor(rdId, projectRef, remoteVal, remoteFieldsJson, createdBy = bob, updatedBy = bob)
      val resourceRev2 = resourceFor(
        rdId,
        projectRef,
        remoteVal,
        remoteFieldsJson,
        rev = 2,
        createdBy = bob,
        updatedBy = bob,
        tags = Map(tag -> 1)
      )

      "succeed" in {
        storages.fetch(IriSegment(rdId), projectRef).accepted shouldEqual resourceRev2
      }

      "succeed by tag" in {
        storages.fetchBy(IriSegment(rdId), projectRef, tag).accepted shouldEqual resourceRev1
      }

      "succeed by rev" in {
        storages.fetchAt(IriSegment(rdId), projectRef, 2).accepted shouldEqual resourceRev2
        storages.fetchAt(IriSegment(rdId), projectRef, 1).accepted shouldEqual resourceRev1
      }

      "reject if tag does not exist" in {
        val otherTag = Label.unsafe("other")
        storages.fetchBy(IriSegment(rdId), projectRef, otherTag).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        storages.fetchAt(IriSegment(rdId), projectRef, 5).rejected shouldEqual
          RevisionNotFound(provided = 5, current = 2)
      }

      "fail fetching if storage does not exist" in {
        val notFound = nxv + "notFound"
        storages.fetch(IriSegment(notFound), projectRef).rejectedWith[StorageNotFound]
        storages.fetchBy(IriSegment(notFound), projectRef, tag).rejectedWith[StorageNotFound]
        storages.fetchAt(IriSegment(notFound), projectRef, 2L).rejectedWith[StorageNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        storages.fetch(IriSegment(rdId), projectRef).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

    }

    "fetching SSE" should {
      val allEvents = SSEUtils.list(
        dId  -> StorageCreated,
        s3Id -> StorageCreated,
        dId  -> StorageUpdated,
        rdId -> StorageCreated,
        s3Id -> StorageUpdated,
        dId  -> StorageUpdated,
        rdId -> StorageTagAdded,
        s3Id -> StorageDeprecated
      )

      "get the different events from start" in {
        val streams = List(
          storages.events(NoOffset),
          storages.events(org, NoOffset).accepted,
          storages.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents
        }
      }

      "get the different events from offset 2" in {
        val streams = List(
          storages.events(Sequence(2L)),
          storages.events(org, Sequence(2L)).accepted,
          storages.events(projectRef, Sequence(2L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take((allEvents.size - 2).toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents.drop(2)
        }
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        storages.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if organization does not exist" in {
        val org = Label.unsafe("other")
        storages.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }
  }

}