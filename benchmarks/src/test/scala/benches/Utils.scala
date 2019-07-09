package benches

import java.io.File
import com.typesafe.scalalogging.StrictLogging
import encry.modifiers.mempool.TransactionFactory
import encry.settings.{EncryAppSettings, NodeSettings}
import encry.storage.levelDb.versionalLevelDB.VersionalLevelDBCompanion.{LevelDBVersion, VersionalLevelDbKey, VersionalLevelDbValue}
import encry.storage.levelDb.versionalLevelDB._
import encry.utils.{FileHelper, Mnemonic, NetworkTimeProvider}
import encry.view.history.EncryHistory
import encry.view.history.processors.payload.BlockPayloadProcessor
import encry.view.history.storage.HistoryStorage
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.crypto.equihash.EquihashSolution
import org.encryfoundation.common.crypto.{PrivateKey25519, PublicKey25519, Signature25519}
import org.encryfoundation.common.modifiers.history.{Block, Header, Payload}
import org.encryfoundation.common.modifiers.mempool.directive.{AssetIssuingDirective, DataDirective, Directive, TransferDirective}
import org.encryfoundation.common.modifiers.mempool.transaction.EncryAddress.Address
import org.encryfoundation.common.modifiers.mempool.transaction._
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.modifiers.state.box.{AssetBox, EncryProposition, MonetaryBox}
import org.encryfoundation.common.utils.TaggedTypes._
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.encryfoundation.prismlang.core.wrapped.BoxedValue
import org.iq80.leveldb.Options
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.crypto.signatures.{Curve25519, PrivateKey, PublicKey}
import scorex.utils.Random
import scala.collection.immutable
import scala.util.{Random => R}

object Utils extends StrictLogging {

  val mnemonicKey: String = "index another island accuse valid aerobic little absurd bunker keep insect scissors"
  val privKey: PrivateKey25519 = createPrivKey(Some(mnemonicKey))

  val defaultKeySize: Int = 32
  val defaultValueSize: Int = 256

  def generateRandomKey(keySize: Int = defaultKeySize): VersionalLevelDbKey =
    VersionalLevelDbKey @@ Random.randomBytes(keySize)

  def generateRandomValue(valueSize: Int = defaultValueSize): VersionalLevelDbValue =
    VersionalLevelDbValue @@ Random.randomBytes(valueSize)

  def genRandomInsertValue(keySize: Int = defaultKeySize,
                           valueSize: Int = defaultValueSize): (VersionalLevelDbKey, VersionalLevelDbValue) =
    (generateRandomKey(keySize), generateRandomValue(valueSize))

  def generateRandomLevelDbElemsWithoutDeletions(qty: Int, qtyOfElemsToInsert: Int): List[LevelDbDiff] =
    (0 until qty).foldLeft(List.empty[LevelDbDiff]) {
      case (acc, i) =>
        LevelDbDiff(
          LevelDBVersion @@ Random.randomBytes(),
          List((0 until qtyOfElemsToInsert).map(_ => genRandomInsertValue()): _*)
        ) :: acc
    }

//  def generateGenesisBlockValidForState(state: UtxoState): Block = {
//    val txs = Seq(coinbaseTransaction(0))
//    val (adProofN: SerializedAdProof, adDigest: ADDigest) = state.generateProofs(txs).get
//    val adPN: Digest32 = ADProofs.proofDigest(adProofN)
//    val header = genHeader.copy(
//      parentId = Header.GenesisParentId,
//      adProofsRoot = adPN,
//      stateRoot = adDigest,
//      height = TestNetConstants.GenesisHeight
//    )
//    Block(header, Payload(header.id, txs), None)
//  }
//
//  def generateGenesisBlockValidForHistory: Block = {
//    val header = genHeader.copy(parentId = Header.GenesisParentId, height = TestNetConstants.GenesisHeight)
//    Block(header, Payload(header.id, Seq(coinbaseTransaction)), None)
//  }
//
//  def generateNextBlockValidForState(prevBlock: Block,
//                                     state: UtxoState,
//                                     box: Seq[AssetBox],
//                                     transactionsNumberInEachBlock: Int,
//                                     numberOfInputsInOneTransaction: Int,
//                                     numberOfOutputsInOneTransaction: Int): Block = {
//
//    val transactions: Seq[Transaction] = (0 until transactionsNumberInEachBlock).foldLeft(box, Seq.empty[Transaction]) {
//      case ((boxes, transactionsL), _) =>
//        val tx: Transaction = defaultPaymentTransactionScratch(
//          privKey,
//          fee = 111,
//          timestamp = 11L,
//          useBoxes = boxes.take(numberOfInputsInOneTransaction).toIndexedSeq,
//          recipient = randomAddress,
//          amount = 10000,
//          numOfOutputs = numberOfOutputsInOneTransaction
//        )
//        (boxes.drop(numberOfInputsInOneTransaction), transactionsL :+ tx)
//    }._2 ++ Seq(coinbaseTransaction(prevBlock.header.height + 1))
//    logger.info(s"Number of generated transactions: ${transactions.size}.")
//    val (adProofN: SerializedAdProof, adDigest: ADDigest) = state.generateProofs(transactions).get
//    val adPN: Digest32 = ADProofs.proofDigest(adProofN)
//    val header = Header(
//      1.toByte,
//      prevBlock.id,
//      adPN,
//      adDigest,
//      Payload.rootHash(transactions.map(_.id)),
//      System.currentTimeMillis(),
//      prevBlock.header.height + 1,
//      R.nextLong(),
//      Difficulty @@ BigInt(1),
//      EquihashSolution(Seq(1, 3))
//    )
//    Block(header, Payload(header.id, transactions), None)
//  }
//
//  def generateNextBlockForStateWithSpendingAllPreviousBoxes(prevBlock: Block,
//                                                            state: UtxoState,
//                                                            box: Seq[AssetBox],
//                                                            splitCoef: Int = 2,
//                                                            addDiff: Difficulty = Difficulty @@ BigInt(0)): Block = {
//
//    val transactions: Seq[Transaction] = box.indices.foldLeft(box, Seq.empty[Transaction]) {
//      case ((boxes, transactionsL), _) =>
//        val tx: Transaction = defaultPaymentTransactionScratch(
//          privKey,
//          fee = 1,
//          timestamp = 11L,
//          useBoxes = IndexedSeq(boxes.head),
//          recipient = privKey.publicImage.address.address,
//          amount = boxes.head.amount - 1,
//          numOfOutputs = splitCoef
//        )
//        (boxes.tail, transactionsL :+ tx)
//    }._2.filter(tx => state.isValid(tx)) ++ Seq(coinbaseTransaction(prevBlock.header.height + 1))
//    logger.info(s"Number of generated transactions: ${transactions.size}.")
//    val (adProofN: SerializedAdProof, adDigest: ADDigest) = state.generateProofs(transactions).get
//    val adPN: Digest32 = ADProofs.proofDigest(adProofN)
//    val header = Header(
//      1.toByte,
//      prevBlock.id,
//      adPN,
//      adDigest,
//      Payload.rootHash(transactions.map(_.id)),
//      System.currentTimeMillis(),
//      prevBlock.header.height + 1,
//      R.nextLong(),
//      Difficulty @@ (BigInt(1) + addDiff),
//      EquihashSolution(Seq(1, 3))
//    )
//    Block(header, Payload(header.id, transactions), None)
//  }

  def generateNextBlockValidForHistory(history: EncryHistory,
                                       difficultyDiff: BigInt = 0,
                                       prevBlock: Option[Block],
                                       txs: Seq[Transaction]): Block = {
    val previousHeaderId: ModifierId = prevBlock.map(_.id).getOrElse(Header.GenesisParentId)
    val requiredDifficulty: Difficulty = prevBlock.map(b => history.requiredDifficultyAfter(b.header))
      .getOrElse(TestNetConstants.InitialDifficulty)
    val header = genHeader.copy(
      parentId = previousHeaderId,
      height = history.bestHeaderHeight + 1,
      difficulty = Difficulty @@ (requiredDifficulty + difficultyDiff),
      transactionsRoot = Payload.rootHash(txs.map(_.id))
    )
    Block(header, Payload(header.id, txs))
  }

  def genValidPaymentTxs(qty: Int): Seq[Transaction] = {
    val now = System.currentTimeMillis()
    (0 until qty).map { _ =>
      val useBoxes: IndexedSeq[AssetBox] = IndexedSeq(genAssetBox(privKey.publicImage.address.address))
      defaultPaymentTransactionScratch(privKey, 4300,
        now + scala.util.Random.nextInt(5000), useBoxes, randomAddress, 1000000)
    }
  }

  def genAssetBox(address: Address, amount: Amount = 100000L, tokenIdOpt: Option[ADKey] = None): AssetBox =
    AssetBox(EncryProposition.addressLocked(address), R.nextLong(), amount, tokenIdOpt)

//  def utxoFromBoxHolder(bh: BoxHolder,
//                        dir: File,
//                        nodeViewHolderRef: Option[ActorRef],
//                        settings: EncryAppSettings,
//                        storageType: StorageType): UtxoState = {
//    val p = new avltree.BatchAVLProver[Digest32, Algos.HF](keyLength = 32, valueLengthOpt = None)
//    bh.sortedBoxes.foreach(b => p.performOneOperation(avltree.Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))
//    val versionalStorage = storageType match {
//      case VersionalStorage.IODB =>
//        IODBWrapper(new LSMStore(dir, keySize = 32, keepVersions = 10))
//      case VersionalStorage.LevelDB =>
//        val reopenedLevelDb = LevelDbFactory.factory.open(dir, new Options)
//        VLDBWrapper(VersionalLevelDBCompanion(reopenedLevelDb, LevelDBSettings(100, 33), keySize = 33))
//    }
//    val persistentProver: avltree.PersistentBatchAVLProver[Digest32, HF] = {
//      val np: NodeParameters = NodeParameters(keySize = 32, valueSize = None, labelSize = 32)
//      val storage: VersionedAVLStorage[Digest32] = new VersionedAVLStorage(versionalStorage, np, settings)(Algos.hash)
//      PersistentBatchAVLProver.create(p, storage).get
//    }
//    new UtxoState(
//      persistentProver,
//      EncryState.genesisStateVersion,
//      TestNetConstants.GenesisHeight,
//      versionalStorage,
//      0L,
//      None,
//      settings,
//      None
//    )
//  }

  def getRandomTempDir: File = {
    val dir = java.nio.file.Files.createTempDirectory("encry_test_" + R.alphanumeric.take(15).mkString).toFile
    dir.deleteOnExit()
    dir
  }

  def genHeader: Header = {
    val random = new scala.util.Random
    Header(
      1.toByte,
      ModifierId @@ Random.randomBytes(),
      Digest32 @@ Random.randomBytes(),
      Math.abs(random.nextLong()),
      Math.abs(random.nextInt(10000)),
      random.nextLong(),
      TestNetConstants.InitialDifficulty,
      EquihashSolution(Seq(1, 3))
    )
  }

  def genHardcodedBox(address: Address, nonce: Long): AssetBox =
    AssetBox(EncryProposition.addressLocked(address), nonce, 10000000L, None)

  def randomAddress: Address = Pay2PubKeyAddress(PublicKey @@ Random.randomBytes()).address

  def coinbaseTransaction(height: Int): Transaction = TransactionFactory.coinbaseTransactionScratch(
    privKey.publicImage,
    System.currentTimeMillis(),
    supply = 10000000L,
    amount = 1L,
    height = Height @@ height
  )

  lazy val coinbaseTransaction: Transaction = {
    TransactionFactory.coinbaseTransactionScratch(
      privKey.publicImage,
      System.currentTimeMillis(),
      10L,
      0,
      Height @@ 100
    )
  }

  def createPrivKey(seed: Option[String]): PrivateKey25519 = {
    val (privateKey: PrivateKey, publicKey: PublicKey) = Curve25519.createKeyPair(
      Blake2b256.hash(
        seed.map {
          Mnemonic.seedFromMnemonic(_)
        }
          .getOrElse {
            val phrase: String = Mnemonic.entropyToMnemonicCode(scorex.utils.Random.randomBytes(16))
            Mnemonic.seedFromMnemonic(phrase)
          })
    )
    PrivateKey25519(privateKey, publicKey)
  }

  def generateInitialBoxes(qty: Int): immutable.IndexedSeq[AssetBox] =
    (0 until qty).map(_ => genAssetBox(privKey.publicImage.address.address))

  def generatePaymentTransactions(boxes: IndexedSeq[AssetBox],
                                  numberOfInputs: Int,
                                  numberOfOutputs: Int): Vector[Transaction] =
    (0 until boxes.size / numberOfInputs).foldLeft(boxes, Vector.empty[Transaction]) {
      case ((boxesLocal, transactions), _) =>
        val tx: Transaction = defaultPaymentTransactionScratch(
          privKey,
          fee = 111,
          timestamp = 11L,
          useBoxes = boxesLocal.take(numberOfInputs),
          recipient = randomAddress,
          amount = 10000,
          numOfOutputs = numberOfOutputs
        )
        (boxesLocal.drop(numberOfInputs), transactions :+ tx)
    }._2

  def generateDataTransactions(boxes: IndexedSeq[AssetBox],
                               numberOfInputs: Int,
                               numberOfOutputs: Int,
                               bytesQty: Int): Vector[Transaction] =
    (0 until boxes.size / numberOfInputs).foldLeft(boxes, Vector.empty[Transaction]) {
      case ((boxesLocal, transactions), _) =>
        val tx: Transaction = dataTransactionScratch(
          privKey,
          fee = 111,
          timestamp = 11L,
          useOutputs = boxesLocal.take(numberOfInputs),
          data = Random.randomBytes(bytesQty),
          amount = 200L,
          numOfOutputs = numberOfOutputs
        )
        (boxesLocal.drop(numberOfInputs), tx +: transactions)
    }._2

  def generateAssetTransactions(boxes: IndexedSeq[AssetBox],
                                numberOfInputs: Int,
                                numberOfOutputs: Int): Vector[Transaction] =
    (0 until boxes.size / numberOfInputs).foldLeft(boxes, Vector.empty[Transaction]) {
      case ((boxesLocal, transactions), _) =>
        val tx: Transaction = assetIssuingTransactionScratch(
          privKey,
          fee = 111,
          timestamp = 11L,
          useOutputs = boxesLocal.take(numberOfInputs),
          amount = 200L,
          numOfOutputs = numberOfOutputs
        )
        (boxesLocal.drop(numberOfInputs), tx +: transactions)
    }._2

  def defaultPaymentTransactionScratch(privKey: PrivateKey25519,
                                       fee: Amount,
                                       timestamp: Long,
                                       useBoxes: IndexedSeq[MonetaryBox],
                                       recipient: Address,
                                       amount: Amount,
                                       tokenIdOpt: Option[ADKey] = None,
                                       numOfOutputs: Int = 5): Transaction = {

    val pubKey: PublicKey25519 = privKey.publicImage

    val uInputs: IndexedSeq[Input] = useBoxes
      .map(bx => Input.unsigned(bx.id, Right(PubKeyLockedContract(pubKey.pubKeyBytes))))
      .toIndexedSeq

    val change: Amount = useBoxes.map(_.amount).sum - (amount + fee)

    val directives: IndexedSeq[TransferDirective] =
      if (change > 0) TransferDirective(recipient, amount, tokenIdOpt) +: (0 until numOfOutputs).map(_ =>
        TransferDirective(pubKey.address.address, change / numOfOutputs, tokenIdOpt))
      else IndexedSeq(TransferDirective(recipient, amount, tokenIdOpt))

    val uTransaction: UnsignedTransaction = UnsignedTransaction(fee, timestamp, uInputs, directives)

    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }

  def dataTransactionScratch(privKey: PrivateKey25519,
                             fee: Long,
                             timestamp: Long,
                             useOutputs: IndexedSeq[MonetaryBox],
                             amount: Long,
                             data: Array[Byte],
                             numOfOutputs: Int = 5): Transaction = {

    val pubKey: PublicKey25519 = privKey.publicImage

    val uInputs: IndexedSeq[Input] = useOutputs
      .map(bx => Input.unsigned(bx.id, Right(PubKeyLockedContract(pubKey.pubKeyBytes))))
      .toIndexedSeq

    val change: Amount = useOutputs.map(_.amount).sum - (amount + fee)

    val directives: IndexedSeq[DataDirective] =
      (0 until numOfOutputs).foldLeft(IndexedSeq.empty[DataDirective]) { case (directivesAll, _) =>
        directivesAll :+ DataDirective(PubKeyLockedContract(privKey.publicImage.pubKeyBytes).contract.hash, data)
      }

    val newDirectives: IndexedSeq[Directive] =
      if (change > 0) TransferDirective(pubKey.address.address, amount, None) +: (0 until numOfOutputs).map(_ =>
        TransferDirective(pubKey.address.address, change / numOfOutputs, None)) ++: directives
      else directives

    val uTransaction: UnsignedTransaction = UnsignedTransaction(fee, timestamp, uInputs, newDirectives)

    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }

  def assetIssuingTransactionScratch(privKey: PrivateKey25519,
                                     fee: Long,
                                     timestamp: Long,
                                     useOutputs: IndexedSeq[MonetaryBox],
                                     amount: Long,
                                     numOfOutputs: Int = 5): Transaction = {
    val directives: IndexedSeq[AssetIssuingDirective] =
      (0 until numOfOutputs).foldLeft(IndexedSeq.empty[AssetIssuingDirective]) { case (directivesAll, _) =>
        directivesAll :+ AssetIssuingDirective(PubKeyLockedContract(privKey.publicImage.pubKeyBytes).contract.hash, amount)
      }

    val pubKey: PublicKey25519 = privKey.publicImage

    val uInputs: IndexedSeq[Input] = useOutputs
      .map(bx => Input.unsigned(bx.id, Right(PubKeyLockedContract(pubKey.pubKeyBytes))))
      .toIndexedSeq

    val change: Amount = useOutputs.map(_.amount).sum - (amount + fee)

    val newDirectives: IndexedSeq[Directive] =
      if (change > 0) TransferDirective(pubKey.address.address, amount, None) +: (0 until numOfOutputs).map(_ =>
        TransferDirective(pubKey.address.address, change / numOfOutputs, None)) ++: directives
      else directives

    val uTransaction: UnsignedTransaction = UnsignedTransaction(fee, timestamp, uInputs, newDirectives)

    val signature: Signature25519 = privKey.sign(uTransaction.messageToSign)

    uTransaction.toSigned(IndexedSeq.empty, Some(Proof(BoxedValue.Signature25519Value(signature.bytes.toList))))
  }

  def generateHistory(settingsEncry: EncryAppSettings, file: File): EncryHistory = {

    val indexStore: LSMStore = new LSMStore(FileHelper.getRandomTempDir, keepVersions = 0)
    val objectsStore: LSMStore = new LSMStore(FileHelper.getRandomTempDir, keepVersions = 0)
    val levelDBInit = LevelDbFactory.factory.open(FileHelper.getRandomTempDir, new Options)
    val vldbInit = VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, settingsEncry.levelDB))
    val storage: HistoryStorage = new HistoryStorage(vldbInit)

    val ntp: NetworkTimeProvider = new NetworkTimeProvider(settingsEncry.ntp)

    new EncryHistory with BlockPayloadProcessor {
      override protected val settings: EncryAppSettings = settingsEncry
      override protected val nodeSettings: NodeSettings = settings.node
      override protected val historyStorage: HistoryStorage = storage
      override protected val timeProvider: NetworkTimeProvider = ntp
    }
  }

}