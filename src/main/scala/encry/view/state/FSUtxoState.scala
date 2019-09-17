package encry.view.state

import java.io.File

import akka.actor.ActorRef
import com.typesafe.scalalogging.StrictLogging
import encry.storage.VersionalStorage
import encry.utils.CoreTaggedTypes.VersionTag
import encry.view.NodeViewErrors.ModifierApplyError
import encry.view.history.History
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.{Block, Header}
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{ADKey, Height, ModifierId}
import org.encryfoundation.common.validation.ValidationResult
import cats.syntax.either._
import cats.instances.list._
import com.google.common.primitives.Ints
import encry.settings.EncryAppSettings
import encry.storage.VersionalStorage.{StorageKey, StorageValue, StorageVersion}
import encry.storage.iodb.versionalIODB.IODBWrapper
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.utils.implicits.UTXO.combineAll
import encry.view.state.UtxoState.{StateChange, initialStateBoxes, logger}
import encry.utils.implicits.UTXO._
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.modifiers.state.box.EncryBaseBox
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.iq80.leveldb.Options
import scorex.crypto.hash.Digest32
import encry.EncryApp._
import scala.collection.immutable.HashSet
import scala.util.Try

final case class FSUtxoState(storage: VersionalStorage) extends State with StrictLogging {

  override var height: Height = TestNetConstants.PreGenesisHeight
  override var lastBlockTimestamp: Long = 0L

  var stateBatchToAdd: HashSet[StorageKey] = HashSet.empty
  var stateBatchToDelete: HashSet[StorageKey] = HashSet.empty

  override def applyModifier(mod: PersistentModifier): FSUtxoState = {
    mod match {
      case header: Header =>
        logger.info(s"\n\nStarting to applyModifier as a header: ${Algos.encode(mod.id)} to state at height ${header.height}")
        FSUtxoState(
          storage
        )
      case block: Block =>
        val combinedStateChange = combineAll(block.payload.txs.map(tx => FSUtxoState.tx2StateChange(tx, block)).toList)
        stateBatchToAdd ++= combinedStateChange.outputsToDb.map(_._1).toSet
        stateBatchToDelete ++= combinedStateChange.inputsToDb.toSet
        if (height % settings.network.networkChunkSize == 0) {
          val toAdd = stateBatchToAdd.diff(stateBatchToDelete).map(_ -> StorageValue @@ Array.emptyByteArray)
          val toDelete = stateBatchToDelete.diff(stateBatchToAdd)
          storage.insert(
            StorageVersion !@@ block.id,
            toAdd.toList,
            toDelete.toList
          )
          stateBatchToAdd = HashSet.empty
          stateBatchToDelete = HashSet.empty
        }
        FSUtxoState(
          storage
        )
    }
  }

  override def rollbackTo(version: VersionTag): Try[FSUtxoState] = Try {
    storage.versions.find(_ sameElements version) match {
      case Some(_) =>
        logger.info(s"Rollback to version ${Algos.encode(version)}")
        storage.rollbackTo(StorageVersion !@@ version)
        val stateHeight: Int = storage.get(StorageKey @@ UtxoState.bestHeightKey.untag(Digest32))
          .map(d => Ints.fromByteArray(d)).getOrElse(TestNetConstants.GenesisHeight)
        FSUtxoState(
          storage
        )
      case None => throw new Exception(s"Impossible to rollback to version ${Algos.encode(version)}")
    }
  }

  override def validate(tx: Transaction, allowedOutputDelta: Amount): Either[ValidationResult, Transaction] =
    tx.asRight[ValidationResult]

  def resolveState(history: History): UtxoState = {
    val boxes = storage.getAll(-1).flatMap {case (key, value) =>
      val (blockId, txId) = value.splitAt(32)
      val block = history.getBlockByHeaderIdDB(ModifierId @@ blockId).get
      val tx = block.payload.txs.find(_.id sameElements txId).get
      tx.newBoxes.find(_.id sameElements key).map(bx => (StorageKey !@@ bx.id, StorageValue @@ bx.bytes))
    }
    storage.insert(
      history.historyStorage.store.currentVersion,
      boxes.toList
    )
    UtxoState(storage)
  }
}

object FSUtxoState extends StrictLogging {

  def tx2StateChange(tx: Transaction, block: Block): StateChange = StateChange(
    tx.inputs.map(input => StorageKey !@@ input.boxId).toVector,
    tx.newBoxes.map(bx => (StorageKey !@@ bx.id, StorageValue @@ (block.id ++ tx.id))).toVector
  )

  def genesis(stateDir: File,
              nodeViewHolderRef: Option[ActorRef],
              settings: EncryAppSettings,
              statsSenderRef: Option[ActorRef]): FSUtxoState = {
    //check kind of storage
    val storage = settings.storage.state match {
      case VersionalStorage.IODB =>
        logger.info("Init state with iodb storage")
        IODBWrapper(new LSMStore(stateDir, keepVersions = TestNetConstants.DefaultKeepVersions))
      case VersionalStorage.LevelDB =>
        logger.info("Init state with levelDB storage")
        val levelDBInit = LevelDbFactory.factory.open(stateDir, new Options)
        VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, settings.levelDB, keySize = 32))
    }

    storage.insert(
      StorageVersion @@ Array.fill(32)(0: Byte),
      initialStateBoxes.map(bx => (StorageKey !@@ bx.id, StorageValue @@ Array.empty[Byte]))
    )

    new FSUtxoState(
      storage
    )
  }
}
