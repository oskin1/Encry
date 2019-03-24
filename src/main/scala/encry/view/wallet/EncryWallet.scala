package encry.view.wallet

import java.io.File

import com.typesafe.scalalogging.StrictLogging
import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.Block
import encry.modifiers.mempool.Transaction
import encry.modifiers.state.box.{EncryBaseBox, EncryProposition}
import encry.settings.{Constants, EncryAppSettings}
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, WalletVersionalLevelDB, WalletVersionalLevelDBCompanion}
import encry.utils.CoreTaggedTypes.{ModifierId, VersionTag}
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.crypto.PublicKey25519
import org.iq80.leveldb.{DB, Options}

import scala.util.Try

case class EncryWallet(walletStorage: WalletVersionalLevelDB, accountManager: AccountManager) extends StrictLogging {

  def publicKeys: Set[PublicKey25519] = accountManager.publicAccounts.toSet

  def propositions: Set[EncryProposition] = publicKeys.map(pk => EncryProposition.pubKeyLocked(pk.pubKeyBytes))

  def scanPersistent(modifier: EncryPersistentModifier): EncryWallet = modifier match {
    case block: Block =>
      logger.info(s"Keys during sync: ${publicKeys}")
      val (newBxs: Seq[EncryBaseBox], spentBxs: Seq[EncryBaseBox]) =
        block.transactions.foldLeft(Seq[EncryBaseBox](), Seq[EncryBaseBox]()) {
          case ((nBxs, sBxs), tx: Transaction) =>
            val newBxsL: Seq[EncryBaseBox] = tx.newBoxes
              .foldLeft(Seq[EncryBaseBox]()) { case (nBxs2, bx) =>
                if (propositions.exists(_.contractHash sameElements bx.proposition.contractHash)) nBxs2 :+ bx else nBxs2
              }
            val spendBxsIdsL: Seq[EncryBaseBox] = tx.inputs
              .filter(input => walletStorage.containsBox(input.boxId))
              .foldLeft(Seq.empty[EncryBaseBox]) { case (boxes, input) =>
                walletStorage.getBoxById(input.boxId).map(bx => boxes :+ bx).getOrElse(boxes)
              }
            (nBxs ++ newBxsL) -> (sBxs ++ spendBxsIdsL)
        }
      walletStorage.updateWallet(modifier.id, newBxs, spentBxs)
      this

    case _ => this
  }

  def rollback(to: VersionTag): Try[Unit] = Try(walletStorage.rollback(ModifierId @@ to.untag(VersionTag)))

  def getBalances: Seq[(String, Long)] = walletStorage.getBalances.toSeq
}

object EncryWallet extends StrictLogging {

  def getWalletDir(settings: EncryAppSettings): File = new File(s"${settings.directory}/wallet")

  def getKeysDir(settings: EncryAppSettings): File = new File(s"${settings.directory}/keys")

  def readOrGenerate(settings: EncryAppSettings): EncryWallet = {
    val walletDir: File = getWalletDir(settings)
    walletDir.mkdirs()
    val keysDir: File = getKeysDir(settings)
    keysDir.mkdirs()
    val db: DB = LevelDbFactory.factory.open(walletDir, new Options)
    val accountManagerStore: LSMStore = new LSMStore(keysDir, keepVersions = 0, keySize = 33)
    val walletStorage = WalletVersionalLevelDBCompanion(db, settings.levelDB)
    val accountManager = AccountManager(accountManagerStore)
    //init keys
    accountManager.mandatoryAccount
    EncryWallet(walletStorage, accountManager)
  }
}