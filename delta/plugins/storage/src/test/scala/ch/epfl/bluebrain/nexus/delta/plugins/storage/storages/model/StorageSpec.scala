package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StorageSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with RemoteContextResolutionFixture
    with IOValues
    with StorageFixtures {
  "A Storage" should {
    val project       = ProjectRef(Label.unsafe("org"), Label.unsafe("project"))
    val tag           = Label.unsafe("tag")
    val diskStorage   = DiskStorage(nxv + "disk", project, diskVal, Map.empty, json"""{"disk": "value"}""")
    val s3Storage     = S3Storage(nxv + "s3", project, s3Val, Map(tag -> 1), json"""{"s3": "value"}""")
    val remoteStorage = RemoteDiskStorage(nxv + "remote", project, remoteVal, Map.empty, json"""{"remote": "value"}""")

    "be compacted" in {
      forAll(List(diskStorage -> diskJson, s3Storage -> s3Json, remoteStorage -> remoteJson)) {
        case (value, compacted) => value.toCompactedJsonLd.accepted.json shouldEqual compacted
      }
    }

    "be expanded" in {
      val diskJson   = jsonContentOf("storage/disk-storage-expanded.json")
      val s3Json     = jsonContentOf("storage/s3-storage-expanded.json")
      val remoteJson = jsonContentOf("storage/remote-storage-expanded.json")

      forAll(List(diskStorage -> diskJson, s3Storage -> s3Json, remoteStorage -> remoteJson)) {
        case (value, expanded) => value.toExpandedJsonLd.accepted.json shouldEqual expanded
      }
    }
  }

}