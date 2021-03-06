package ch.epfl.bluebrain.nexus.delta.sourcing.projections

sealed trait ProjectionId extends Product with Serializable {
  def value: String
}

object ProjectionId {

  final case class CacheProjectionId(value: String) extends ProjectionId

  final case class ViewProjectionId(value: String) extends ProjectionId

  final case class SourceProjectionId(value: String) extends ProjectionId

  final case class CompositeViewProjectionId(sourceId: SourceProjectionId, projectionId: ViewProjectionId)
      extends ProjectionId {
    override def value: String = s"${sourceId.value}_${projectionId.value}"

  }

}
