package encry.network

import java.io.File
import akka.actor.{Actor, ActorRef}
import com.typesafe.scalalogging.StrictLogging
import encry.consensus.History.ProgressInfo
import encry.network.AuxiliaryHistoryHolder._
import encry.settings.{EncryAppSettings, NodeSettings}
import encry.storage.VersionalStorage
import encry.storage.iodb.versionalIODB.IODBHistoryWrapper
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.utils.NetworkTimeProvider
import encry.view.history.EncryHistory
import encry.view.history.processors.payload.{BlockPayloadProcessor, EmptyBlockPayloadProcessor}
import encry.view.history.processors.proofs.{ADStateProofProcessor, FullStateProofProcessor}
import encry.view.history.storage.HistoryStorage
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.iq80.leveldb.Options

case class AuxiliaryHistoryHolder(var history: EncryHistory, syncronizer: ActorRef)
  extends Actor with StrictLogging {

  override def preStart(): Unit = syncronizer ! AuxHistoryChanged(history)

  override def receive: Receive = {
    case NewHistory(historyFromNvh) =>
      history = historyFromNvh
      syncronizer ! AuxHistoryChanged(history)
  }

  override def postStop(): Unit = {
    logger.warn(s"Stopping AuxiliaryHistoryHolder.")
  }
}

object AuxiliaryHistoryHolder {

  case class NewHistory(history: EncryHistory)

  private def getHistoryIndexDir(settings: EncryAppSettings): File = {
    val dir: File = new File(s"${settings.directory}/auxHistory/index")
    dir.mkdirs()
    dir
  }

  private def getHistoryObjectsDir(settings: EncryAppSettings): File = {
    val dir: File = new File(s"${settings.directory}/auxHistory/objects")
    dir.mkdirs()
    dir
  }

  protected[AuxiliaryHistoryHolder] def readOrGenerate(settingsEncry: EncryAppSettings, ntp: NetworkTimeProvider): EncryHistory = {

    val historyIndexDir: File = getHistoryIndexDir(settingsEncry)
    val vldbInit = settingsEncry.storage.auxHistory match {
      case VersionalStorage.IODB =>
        val historyObjectsDir: File = getHistoryObjectsDir(settingsEncry)
        val indexStore: LSMStore = new LSMStore(historyIndexDir, keepVersions = 0)
        val objectsStore: LSMStore = new LSMStore(historyObjectsDir, keepVersions = 0)
        IODBHistoryWrapper(indexStore, objectsStore)
      case VersionalStorage.LevelDB =>
        val levelDBInit = LevelDbFactory.factory.open(historyIndexDir, new Options)
        VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, settingsEncry.levelDB))
    }
    val storage: HistoryStorage = HistoryStorage(vldbInit)

    val history: EncryHistory = (settingsEncry.node.stateMode.isDigest, settingsEncry.node.verifyTransactions) match {
      case (true, true) =>
        new EncryHistory with ADStateProofProcessor with BlockPayloadProcessor {
          override protected val settings: EncryAppSettings = settingsEncry
          override protected val nodeSettings: NodeSettings = settings.node
          override protected val historyStorage: HistoryStorage = storage
          override protected val timeProvider: NetworkTimeProvider = ntp
          override protected val auxHistory: Boolean = true
        }
      case (false, true) =>
        new EncryHistory with FullStateProofProcessor with BlockPayloadProcessor {
          override protected val settings: EncryAppSettings = settingsEncry
          override protected val nodeSettings: NodeSettings = settings.node
          override protected val historyStorage: HistoryStorage = storage
          override protected val timeProvider: NetworkTimeProvider = ntp
          override protected val auxHistory: Boolean = true
        }
      case (true, false) =>
        new EncryHistory with ADStateProofProcessor with EmptyBlockPayloadProcessor {
          override protected val settings: EncryAppSettings = settingsEncry
          override protected val nodeSettings: NodeSettings = settings.node
          override protected val historyStorage: HistoryStorage = storage
          override protected val timeProvider: NetworkTimeProvider = ntp
          override protected val auxHistory: Boolean = true
        }
      case m => throw new Error(s"Unsupported settings ADState=:${m._1}, verifyTransactions=:${m._2}, ")
    }
    history
  }

  case class ReportModifierValid(mod: PersistentModifier)
  case class ReportModifierInvalid(mod: PersistentModifier, progressInfo: ProgressInfo[PersistentModifier])
  case class Append(mod: PersistentModifier)
  case class AuxHistoryChanged(history: EncryHistory)
}