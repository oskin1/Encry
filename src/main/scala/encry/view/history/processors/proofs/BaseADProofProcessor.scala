package encry.view.history.processors.proofs

import encry.consensus.History.ProgressInfo
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.ADProofs
import scala.util.Try

trait BaseADProofProcessor {

  /**
    * Root hash only is kept in state
    */
  protected val adState: Boolean

  /**
    * @param m - modifier to process
    * @return ProgressInfo - info required for State to be consistent with History
    */
  protected def process(m: ADProofs): ProgressInfo[PersistentModifier]

  /**
    * @param m - ADProof to validate
    * @return Success() if ADProof is valid from History point of view, Failure(error) otherwise
    */
  protected def validate(m: ADProofs): Try[Unit]
}
