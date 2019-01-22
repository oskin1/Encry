package encry.storage.levelDb.versionalLevelDB

import com.typesafe.scalalogging.StrictLogging
import encry.modifiers.NodeViewModifier
import encry.settings.Constants
import encry.utils.CoreTaggedTypes.ModifierId
import org.encryfoundation.common.Algos
import org.encryfoundation.common.Algos.HF
import org.iq80.leveldb.DB
import scorex.crypto.hash.Digest32

import scala.util.Try

trait VersionalLevelDB[D <: RevertabaleDiff[D]] extends StrictLogging {

  val db: DB

  //TODO: Maybe get this param from settings?
  val maxRollbackDepth: Int = Constants.Chain.MaxRollbackDepth

  var versionsList: List[Version[D]] = List.empty[Version[D]]

  def add(modifier: NodeViewModifier): Unit

  def applyDiff(diff: D): Unit

  def getAll: Seq[(Array[Byte], Array[Byte])] = {
    var elementsBuffer: Seq[(Array[Byte], Array[Byte])] = Seq.empty
    val iterator = db.iterator()
    iterator.seekToFirst()
    while (iterator.hasNext) {
      val nextElem = iterator.next()
      elementsBuffer = elementsBuffer :+ (nextElem.getKey -> nextElem.getValue)
    }
    iterator.seekToLast()
    elementsBuffer
  }

  def getDiffsPath(targetNodeId: ModifierId,
                   currentNodesList: List[Version[D]] = versionsList,
                   diffs: Seq[D] = Seq.empty,
                   persistantProver: encry.avltree.PersistentBatchAVLProver[Digest32, HF]): Seq[D] = {
    if (currentNodesList.nonEmpty && targetNodeId == currentNodesList.last.modifierId) diffs
    else if (currentNodesList.nonEmpty)
      getDiffsPath(
        targetNodeId,
        currentNodesList.init,
        diffs ++ currentNodesList.lastOption.map(_.diffs.map(_.revert(persistantProver))).getOrElse(Seq.empty),
        persistantProver
      )
    else diffs
  }

  def tryRollbackTo(rollbackPoint: ModifierId,
                    prover: encry.avltree.PersistentBatchAVLProver[Digest32, HF],
                    diffsPath: Seq[D] = Seq.empty[D]): Try[Unit] = Try {
    if (checkRollbackPoint(rollbackPoint)) {
      rollbackTo(rollbackPoint, prover, diffsPath)
    }
    else throw new Exception(s"Impossible to rollback to: ${Algos.encode(rollbackPoint)}.\n")
  }

  def rollbackTo(rollbackPoint: ModifierId,
                 prover: encry.avltree.PersistentBatchAVLProver[Digest32, HF],
                 diffsPath: Seq[D] = Seq.empty[D]): Unit = {
    val diffs = getDiffsPath(rollbackPoint, persistantProver = prover)
    versionsList = versionsList.reverse.dropWhile(ver => !(ver.modifierId sameElements rollbackPoint)).reverse
    applyDiff(diffs.tail.foldLeft(diffs.head)(_ ++ _))
  }

  private def checkRollbackPoint(rollbackPoint: ModifierId, nodesList: List[Version[D]] = versionsList): Boolean = {
    if (nodesList.isEmpty) {
      logger.info(s"VersionalLevelDb: Rollback point ${Algos.encode(rollbackPoint)} doesn't exist!")
      false
    }
    else if (nodesList.last.modifierId sameElements rollbackPoint) true
    else checkRollbackPoint(rollbackPoint, nodesList.init)
  }
}
