package ch.epfl.bluebrain.nexus.delta.sdk.model.identities

import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Parent type for unique identities as recognized by the system. A client usually has multiple identities with the
  * exception where it performs calls without including an auth token (in which case his only identity is Anonymous).
  */
sealed trait Identity extends Product with Serializable

object Identity {

  /**
    * Parent type for identities that represent a uniquely identified caller.
    */
  sealed trait Subject extends Identity {

    /**
      * A [[Subject]] expressed as an Iri
      *
     * @param base the platform [[BaseUri]]
      */
    def id(implicit base: BaseUri): Iri
  }

  /**
    * The Anonymous type.
    */
  type Anonymous = Anonymous.type

  /**
    * The Anonymous singleton identity.
    */
  final case object Anonymous extends Subject {
    override def id(implicit base: BaseUri): Iri = base.endpoint.toIri / "anonymous"
  }

  /**
    * A user identity. It represents a unique person or a service account.
    *
   * @param subject the subject name (usually the preferred_username claim)
    * @param realm   the associated realm that asserts this identity
    */
  final case class User(subject: String, realm: Label) extends Subject {
    override def id(implicit base: BaseUri): Iri = base.endpoint.toIri / "realms" / realm.value / "users" / subject
  }

  /**
    * A group identity. It asserts that the caller belongs to a certain group of callers.
    *
   * @param group the group name (asserted by one entry in the groups claim)
    * @param realm the associated realm that asserts this identity
    */
  final case class Group(group: String, realm: Label) extends Identity

  /**
    * An authenticated identity is an arbitrary caller that has provided a valid AuthToken issued by a specific realm.
    *
    * @param realm the realm that asserts this identity
    */
  final case class Authenticated(realm: Label) extends Identity
}
