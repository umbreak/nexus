package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.instances.TripleInstances
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax}

package object implicits
    extends TripleInstances
    with JsonSyntax
    with IriSyntax
    with JsonLdEncoderSyntax
    with IterableSyntax
