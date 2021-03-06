package encry.view.state

import org.encryfoundation.common.utils.TaggedTypes.{ADDigest, Height}
import org.encryfoundation.prismlang.core.wrapped.{PObject, PValue}
import org.encryfoundation.prismlang.core.{PConvertible, Types}

case class EncryStateView(height: Height, lastBlockTimestamp: Long, stateDigest: ADDigest) extends PConvertible {

  override val tpe: Types.Product = Types.EncryState

  override def asVal: PValue = PValue(PObject(Map(
    "height" -> PValue(height, Types.PInt),
    "lastBlockTimestamp" -> PValue(lastBlockTimestamp, Types.PInt),
    "stateDigest" -> PValue(stateDigest, Types.PCollection.ofByte)
  ), tpe), tpe)
}