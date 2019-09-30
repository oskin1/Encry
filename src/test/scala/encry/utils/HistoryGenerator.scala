package encry.utils

import encry.consensus.EquihashPowScheme
import encry.crypto.equihash.EquihashValidationErrors
import encry.settings.EncryAppSettings
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.view.history.History
import encry.view.history.storage.HistoryStorage
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.modifiers.history.Header
import org.encryfoundation.common.utils.TaggedTypes.Height
import org.iq80.leveldb.Options

object HistoryGenerator {

  def dummyHistory(settings: EncryAppSettings, withoutPow: Boolean = false): History = {

    val indexStore: LSMStore = new LSMStore(FileHelper.getRandomTempDir, keepVersions = 0)
    val objectsStore: LSMStore = new LSMStore(FileHelper.getRandomTempDir, keepVersions = 0)
    val levelDBInit = LevelDbFactory.factory.open(FileHelper.getRandomTempDir, new Options)
    val vldbInit = VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, settings.levelDB))
    val storage: HistoryStorage = new HistoryStorage(vldbInit)

    val ntp: NetworkTimeProvider = new NetworkTimeProvider(settings.ntp)

    class EquihashPowSchemeWithoutValidateSolution(n: Char, k: Char, version: Byte, preGenesisHeight: Height, maxTarget: BigInt)
      extends EquihashPowScheme(n: Char, k: Char, version: Byte, preGenesisHeight: Height, maxTarget: BigInt) {
      override def verify(header: Header): Either[EquihashValidationErrors, Boolean] = Right(true)
    }

    if (withoutPow) {
      new History {
        override val historyStorage: HistoryStorage = storage
        override val timeProvider: NetworkTimeProvider = ntp
        override val powScheme: EquihashPowScheme =
          new EquihashPowSchemeWithoutValidateSolution(settings.constants.n, settings.constants.k,
            settings.constants.Version, settings.constants.PreGenesisHeight, settings.constants.MaxTarget)
      }
    } else
      new History {
        override val historyStorage: HistoryStorage = storage
        override val timeProvider: NetworkTimeProvider = ntp
      }

  }

}
