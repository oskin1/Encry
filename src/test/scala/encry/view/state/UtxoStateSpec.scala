package encry.view.state

import java.io.File

import akka.actor.ActorRef
import encry.avltree
import encry.avltree.{NodeParameters, PersistentBatchAVLProver, VersionedAVLStorage}
import encry.modifiers.mempool.TransactionFactory
import encry.settings.{Constants, EncryAppSettings, LevelDBSettings}
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.utils.{EncryGenerator, FileHelper, TestHelper}
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.crypto
import org.encryfoundation.common.modifiers.mempool.transaction.{Pay2PubKeyAddress, Transaction}
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.Algos.HF
import org.encryfoundation.common.utils.TaggedTypes.{ADDigest, ADValue, Height, SerializedAdProof}
import org.iq80.leveldb.Options
import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.hash.Digest32
import scorex.crypto.signatures.{Curve25519, PrivateKey, PublicKey}
import scorex.utils.Random

class UtxoStateSpec extends PropSpec with Matchers with EncryGenerator {

  val settings: EncryAppSettings = EncryAppSettings.read

  def utxoFromBoxHolder(bh: BoxHolder,
                        dir: File,
                        nodeViewHolderRef: Option[ActorRef],
                        settings: EncryAppSettings): UtxoState = {
    val p = new avltree.BatchAVLProver[Digest32, Algos.HF](keyLength = 32, valueLengthOpt = None)
    bh.sortedBoxes.foreach(b => p.performOneOperation(avltree.Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))
    val reopenedLevelDb = LevelDbFactory.factory.open(dir, new Options)
    val vldb = VLDBWrapper(VersionalLevelDBCompanion(reopenedLevelDb, LevelDBSettings(100, 33), keySize = 33))
    val persistentProver: avltree.PersistentBatchAVLProver[Digest32, HF] = {
      val np: NodeParameters = NodeParameters(keySize = 32, valueSize = None, labelSize = 32)
      val storage: VersionedAVLStorage[Digest32] = new VersionedAVLStorage(vldb, np, settings)(Algos.hash)
      PersistentBatchAVLProver.create(p, storage).get
    }
    new UtxoState(
      persistentProver,
      EncryState.genesisStateVersion,
      org.encryfoundation.common.utils.Constants.Chain.GenesisHeight,
      vldb,
      0L,
      None,
      settings,
      None
    )
  }

  property("Proofs for transaction") {

    val (privKey: PrivateKey, pubKey: PublicKey) = Curve25519.createKeyPair(Random.randomBytes())
    val secret: crypto.PrivateKey25519 = org.encryfoundation.common.crypto.PrivateKey25519(privKey, pubKey)

    val initialBoxes: Seq[AssetBox] = genValidAssetBoxes(secret, amount = 100000, qty = 50)

    val bh: BoxHolder = BoxHolder(initialBoxes)

    val state: UtxoState = utxoFromBoxHolder(bh, FileHelper.getRandomTempDir, None, settings)

    val regularTransactions: Seq[Transaction] = initialBoxes.map { bx =>
      TransactionFactory.defaultPaymentTransactionScratch(
        secret, 10000, timestamp, IndexedSeq(bx), randomAddress, 5000)
    }

    val fees: Amount = regularTransactions.map(_.fee).sum

    val coinbase: Transaction = TransactionFactory
      .coinbaseTransactionScratch(
        secret.publicImage,
        timestamp,
        25L,
        fees,
        Height @@ 100
      )

    val transactions: Seq[Transaction] = regularTransactions.sortBy(_.timestamp) :+ coinbase

    val (_: SerializedAdProof, adDigest: ADDigest) = state.generateProofs(transactions).get

    state.applyBlockTransactions(transactions, adDigest).isSuccess shouldBe true
  }

  property("FilterValid(txs) should return only valid txs (against current state).") {

    val bxs = TestHelper.genAssetBoxes

    val bh = BoxHolder(bxs)

    val state = utxoFromBoxHolder(bh, FileHelper.getRandomTempDir, None, settings)

    val factory = TestHelper
    val keys = factory.genKeys(TestHelper.Props.keysQty)

    val fee = factory.Props.txFee

    val validTxs = keys.zip(bxs).map { case (k, bx) =>
      val useBoxes = IndexedSeq(bx)
      TransactionFactory.defaultPaymentTransactionScratch(k, fee,
        timestamp, useBoxes, randomAddress, factory.Props.boxValue - 4300)
    }

    val invalidTxs = keys.map { k =>
      val useBoxes =
        IndexedSeq(factory.genAssetBox(Pay2PubKeyAddress(PublicKey @@ Random.randomBytes()).address))
      TransactionFactory.defaultPaymentTransactionScratch(k, fee,
        timestamp, useBoxes, randomAddress, 3000000L)
    }

    val filteredValidTxs = state.filterValid(validTxs)

    filteredValidTxs.size shouldEqual validTxs.size

    val filteredInvalidTxs = state.filterValid(invalidTxs)

    filteredInvalidTxs.isEmpty shouldBe true

    val filteredValidAndInvalidTxs = state.filterValid(validTxs ++ invalidTxs)

    filteredValidAndInvalidTxs.size shouldEqual validTxs.size
  }

  property("Txs application") {

    val bxs = TestHelper.genAssetBoxes

    val bh = BoxHolder(bxs)

    val state = utxoFromBoxHolder(bh, FileHelper.getRandomTempDir, None, settings)

    val factory = TestHelper
    val keys = factory.genKeys(TestHelper.Props.keysQty)

    val fee = factory.Props.txFee

    val validTxs = keys.zip(bxs).map { case (k, bx) =>
      val useBoxes = IndexedSeq(bx)
      TransactionFactory.defaultPaymentTransactionScratch(k, fee,
        timestamp, useBoxes, randomAddress, factory.Props.boxValue - 4300)
    }

    val expectedDigest = state.generateProofs(validTxs)

    val applyTry = state.applyBlockTransactions(validTxs, expectedDigest.get._2)

    applyTry.isSuccess shouldBe true
  }
}
