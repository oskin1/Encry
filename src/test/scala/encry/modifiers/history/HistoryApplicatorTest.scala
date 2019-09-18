package encry.modifiers.history

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import encry.EncryApp.settings
import encry.modifiers.InstanceFactory
import encry.network.DeliveryManager.FullBlockChainIsSynced
import encry.network.ModifiersToNetworkUtils
import encry.network.NodeViewSynchronizer.ReceivableMessages.UpdatedHistory
import encry.settings.{EncryAppSettings, Settings}
import encry.storage.VersionalStorage
import encry.storage.VersionalStorage.{StorageKey, StorageValue, StorageVersion}
import encry.storage.iodb.versionalIODB.IODBWrapper
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.utils.{FileHelper, TestHelper}
import encry.view.actors
import encry.view.actors.{HistoryApplicator, StateApplicator}
import encry.view.actors.HistoryApplicator.ModifierToHistoryAppending
import encry.view.actors.NodeViewHolder.ReceivableMessages.{LocallyGeneratedBlock, ModifierFromRemote}
import encry.view.actors.StateApplicator.NotificationAboutSuccessfullyAppliedModifier
import encry.view.history.History
import encry.view.state.{BoxHolder, UtxoState}
import encry.view.wallet.EncryWallet
import io.iohk.iodb.LSMStore
import org.encryfoundation.common.modifiers.history.{Block, Payload, PayloadProtoSerializer}
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.iq80.leveldb.Options
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._
class HistoryApplicatorTest extends TestKit(ActorSystem())
  with WordSpecLike
  with ImplicitSender
  with InstanceFactory
  with Matchers
  with BeforeAndAfterAll
  with Settings {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout: FiniteDuration = 5 seconds

  def utxoFromBoxHolder(bh: BoxHolder, dir: File, settings: EncryAppSettings): UtxoState = {
    val storage = settings.storage.state match {
      case VersionalStorage.IODB =>
        IODBWrapper(new LSMStore(dir, keepVersions = TestNetConstants.DefaultKeepVersions))
      case VersionalStorage.LevelDB =>
        val levelDBInit = LevelDbFactory.factory.open(dir, new Options)
        VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, settings.levelDB, keySize = 32))
    }
    storage.insert(
      StorageVersion @@ Array.fill(32)(0: Byte),
      bh.boxes.values.map(bx => (StorageKey !@@ bx.id, StorageValue @@ bx.bytes)).toList
    )
    new UtxoState(storage, settings.constants)
  }

  "HistoryApplicator" should {
    "add Modifier" in {

      val dir = FileHelper.getRandomTempDir
      val history: History = generateDummyHistory(settings)
      val wallet: EncryWallet = EncryWallet.readOrGenerate(settings.copy(directory = dir.getAbsolutePath))

      val bxs = TestHelper.genAssetBoxes
      val bh = BoxHolder(bxs)
      val state = utxoFromBoxHolder(bh, FileHelper.getRandomTempDir, settings)

      val nodeViewHolder = TestProbe()
      val influx = TestProbe()

      //      val historyBlocks = (0 until 10).foldLeft(history) {
      //        case (prevHistory, _) =>
      //          val block: Block = generateNextBlock(prevHistory)
      //          prevHistory.append(block.header).get._1.append(block.payload).get._1.reportModifierIsValid(block)
      //      }

      val historyBlocks = (0 until 10).foldLeft(history, Seq.empty[Block]) {
        case ((prevHistory, blocks), _) =>
          val block: Block = generateNextBlock(prevHistory)
          prevHistory.append(block.header)
          prevHistory.append(block.payload)
          (prevHistory.reportModifierIsValid(block), blocks :+ block)
      }
      val payload = Payload(ModifierId @@ scorex.utils.Random.randomBytes(), Seq(coinbaseTransaction))

      val block: Block = generateNextBlock(history)

      val modifiers: Map[ModifierId, Array[Byte]] = historyBlocks._2.map { b =>
        b.payload.id -> PayloadProtoSerializer.toProto(b.payload).toByteArray
      }.toMap// + (payload.id -> PayloadProtoSerializer.toProto(payload).toByteArray)

      val historyApplicator: ActorRef = system.actorOf(
        Props(new HistoryApplicator(history, state, wallet, settings, nodeViewHolder.ref, Some(influx.ref)) {
          override def receive: Receive = {
            val r = super.receive
            println(s"currentNumberOfAppliedModifiers: $currentNumberOfAppliedModifiers")
            println(s"modifiersQueue: $modifiersQueue")
            r
          }})
        //HistoryApplicator.props(history, settings, state, wallet, nodeViewHolder.ref, Some(influx.ref))
        //  .withDispatcher("history-applicator-dispatcher")
        , "historyApplicator")

//      import akka.actor.ActorDSL._
//      val captureEventsActor = actor {
//        new Act {
//          become {
//            case FullBlockChainIsSynced() => println(s"fullBlockChainIsSynced")
//          }
//        }
//      }

      val logProbe = TestProbe()
      system.eventStream.subscribe(logProbe.ref, classOf[FullBlockChainIsSynced])

      //      val probe = TestProbe()
//      probe.watch(historyApplicator)

      val modifier = ModifiersToNetworkUtils.fromProto(Payload.modifierTypeId, PayloadProtoSerializer.toProto(block.payload).toByteArray)

//      historyApplicator ! ModifierFromRemote(modifier.get)
      historyApplicator ! LocallyGeneratedBlock(block)

      //Thread.sleep(100000)
      logProbe.expectMsg(FullBlockChainIsSynced())

    }
  }

}
