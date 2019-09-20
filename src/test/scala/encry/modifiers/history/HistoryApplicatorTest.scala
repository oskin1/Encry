package encry.modifiers.history

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import encry.modifiers.InstanceFactory
import encry.settings.Settings
import encry.storage.VersionalStorage
import encry.utils.ChainUtils._
import encry.utils.{ChainUtils, FileHelper}
import encry.view.actors.HistoryApplicator.StartModifiersApplicationOnStateApplicator
import encry.view.actors.NodeViewHolder.ReceivableMessages.ModifierFromRemote
import encry.view.actors.{HistoryApplicator, StateApplicator}
import encry.view.history.History
import encry.view.state.{BoxHolder, UtxoState}
import encry.view.wallet.EncryWallet
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.Block
import org.encryfoundation.common.modifiers.state.box.AssetBox
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{Height, ModifierId}
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

import scala.collection.Seq
import scala.concurrent.duration._

class HistoryApplicatorTest extends TestKit(ActorSystem())
  with WordSpecLike
  with ImplicitSender
  with BeforeAndAfterAll
  with Matchers
  with InstanceFactory
  with OneInstancePerTest
  with Settings {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def genHistoryBlocks(initialHistory: History, count: Int): (History, Seq[Block]) = {
    (0 until count).foldLeft(initialHistory, Seq.empty[Block]) {
      case ((prevHistory, blocks), _) =>
        val block: Block = generateNextBlock(prevHistory)
          prevHistory.append(block.header)
          prevHistory.append(block.payload)
        (prevHistory.reportModifierIsValid(block), blocks :+ block)
    }
  }

  def blockToModifiers(block: Block): Seq[PersistentModifier] = Seq(block.header, block.payload)

  def generateChain(blockQty: Int): (UtxoState, List[Block]) = {
    val numberOfInputsInOneTransaction = 1//10
    val transactionsNumberInEachBlock = 100//1000
    val initialboxCount = 10//100000

    val initialBoxes: IndexedSeq[AssetBox] = (0 until initialboxCount).map(nonce =>
      genHardcodedBox(privKey.publicImage.address.address, nonce)
    )
    val boxesHolder: BoxHolder = BoxHolder(initialBoxes)

    val genesisBlock: Block = ChainUtils.generateGenesisBlock(Height @@ 0)

    val state: UtxoState = utxoFromBoxHolder(boxesHolder, dir, None, settings, VersionalStorage.LevelDB)
      .applyModifier(genesisBlock).right.get

    val stateGenerationResults: (List[Block], Block, UtxoState, IndexedSeq[AssetBox]) =
      (0 until blockQty).foldLeft(List.empty[Block], genesisBlock, state, initialBoxes) {
        case ((blocks, block, stateL, boxes), _) =>
          val nextBlockMainChain: Block = generateNextBlockForStateWithSpendingAllPreviousBoxes(
            block, stateL, block.payload.txs.flatMap(_.newBoxes.map(_.asInstanceOf[AssetBox])).toIndexedSeq)
          val stateN: UtxoState = stateL.applyModifier(nextBlockMainChain).right.get
          (blocks :+ nextBlockMainChain,
            nextBlockMainChain, stateN, boxes.drop(transactionsNumberInEachBlock * numberOfInputsInOneTransaction)
          )
      }
    (state, genesisBlock +: stateGenerationResults._1)
  }

  def printIds(blocks: List[Block]) = {
    blocks.foreach { b =>
      println(s"header.timestamp: ${b.header.timestamp}")
      println(s"header.id: ${Algos.encode(b.header.id)}")
      println(s"header.payloadId: ${Algos.encode(b.header.payloadId)}")
      println(s"payload.id: ${Algos.encode(b.payload.id)}")
      println(s"payload.headerId: ${Algos.encode(b.payload.headerId)}")
    }
  }

  val dir: File = FileHelper.getRandomTempDir
  val wallet: EncryWallet = EncryWallet.readOrGenerate(settings.copy(directory = dir.getAbsolutePath))

  val initialHistory: History = generateDummyHistory(settings)

  val nodeViewHolder = TestProbe()
  val influx = TestProbe()

  val timeout: FiniteDuration = 10 seconds

  //printIds(chain)
  //println(s"${settings.constants.RetargetingEpochsQty}")
  //println(s"${settings.constants.EpochLength}")

  //  "HistoryApplicator add locall generated block" should {
  //    "chain synced" in {
  //
  //      val dir = FileHelper.getRandomTempDir
  //      val history: History = generateDummyHistory(settings)
  //      val wallet: EncryWallet = EncryWallet.readOrGenerate(settings.copy(directory = dir.getAbsolutePath))
  //
  //      val bxs = TestHelper.genAssetBoxes
  //      val boxHolder = BoxHolder(bxs)
  //      val state = utxoFromBoxHolder(boxHolder, FileHelper.getRandomTempDir, settings)
  //
  //      val nodeViewHolder = TestProbe()
  //      val influx = TestProbe()
  //
  //      val historyBlocks = (0 until 10).foldLeft(history, Seq.empty[Block]) {
  //        case ((prevHistory, blocks), _) =>
  //          val block: Block = generateNextBlock(prevHistory)
  //          prevHistory.append(block.header)
  //          prevHistory.append(block.payload)
  //          (prevHistory.reportModifierIsValid(block), blocks :+ block)
  //      }
  //
  //      val block: Block = generateNextBlock(history)
  //
  //      val historyApplicator: TestActorRef[HistoryApplicator] =
  //        TestActorRef[HistoryApplicator](
  //          HistoryApplicator.props(history, settings, state, wallet, nodeViewHolder.ref, Some(influx.ref))
  //        )
  //
  //      system.eventStream.subscribe(self, classOf[FullBlockChainIsSynced])
  //
  //      historyApplicator ! LocallyGeneratedBlock(block)
  //
  //      expectMsg(timeout, FullBlockChainIsSynced())
  //    }
  //  }

  "HistoryApplicator" should {
    "check queues" in {

      val (state, chain) = generateChain(1)

      val modifiers: Seq[PersistentModifier] = chain.flatMap(blockToModifiers)
      val modifierIds = modifiers.map(_.id)

      val historyApplicator: TestActorRef[HistoryApplicator] =
        TestActorRef[HistoryApplicator](
          HistoryApplicator.props(initialHistory, settings, state, wallet, nodeViewHolder.ref, Some(influx.ref))
        )

      modifiers.foreach(historyApplicator ! ModifierFromRemote(_))

      println(s"modifiersQueue: ${historyApplicator.underlyingActor.modifiersQueue.size}")
      println(s"currentNumberOfAppliedModifiers: ${historyApplicator.underlyingActor.currentNumberOfAppliedModifiers}")

      val modifiersQueue = historyApplicator.underlyingActor.modifiersQueue
      modifiersQueue.size shouldBe 2
      historyApplicator.underlyingActor.currentNumberOfAppliedModifiers shouldBe 2

      historyApplicator ! StateApplicator.RequestNextModifier

      historyApplicator.underlyingActor.modifiersQueue shouldBe 0
      historyApplicator.underlyingActor.currentNumberOfAppliedModifiers shouldBe 2

      val history = historyApplicator.underlyingActor.history

      expectMsgType[StartModifiersApplicationOnStateApplicator](timeout)

      assert(modifierIds.forall(history.isModifierDefined))
      assert(modifierIds.map(Algos.encode) == modifiersQueue.map(_._1))
    }
  }

  "HistoryApplicator" should {
    "check queue for rollback limit" in {

      println(s"maxVersions: ${settings.levelDB.maxVersions}")

      val (state, chain) = generateChain(120)

      val historyApplicator: TestActorRef[HistoryApplicator] =
        TestActorRef[HistoryApplicator](
          HistoryApplicator.props(initialHistory, settings, state, wallet, nodeViewHolder.ref, Some(influx.ref))
            //.withDispatcher("history-applicator-dispatcher"), "historyApplicator"
        )

      val modifiers: Seq[PersistentModifier] = chain.flatMap(blockToModifiers)

      //system.eventStream.subscribe(self, classOf[FullBlockChainIsSynced])

      modifiers.foreach(historyApplicator ! ModifierFromRemote(_))

      //      println(s"modifiersQueue: ${historyApplicator.underlyingActor.modifiersQueue.size}")
      //      println(s"currentNumberOfAppliedModifiers: ${historyApplicator.underlyingActor.currentNumberOfAppliedModifiers}")

      //      historyApplicator.underlyingActor.modifiersQueue shouldBe 0
      //      historyApplicator.underlyingActor.currentNumberOfAppliedModifiers shouldBe 2

      //val modifiersQueue = historyApplicator.underlyingActor.modifiersQueue
      //val history = historyApplicator.underlyingActor.history

      //expectMsgType[StartModifiersApplicationOnStateApplicator](timeout)

      Thread.sleep(30000)

      //expectMsg(timeout, FullBlockChainIsSynced())

      //      assert(modifierIds.forall(history.isModifierDefined))
      //      assert(modifierIds.map(Algos.encode) == modifiersQueue.map(_._1))
    }
  }

}
