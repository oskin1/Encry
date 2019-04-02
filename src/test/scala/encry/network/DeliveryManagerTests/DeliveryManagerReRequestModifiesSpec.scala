package encry.network.DeliveryManagerTests

import java.net.InetSocketAddress
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import encry.consensus.History.Older
import encry.modifiers.InstanceFactory
import encry.modifiers.history.{Block, Header}
import encry.modifiers.mempool.Transaction
import encry.network.BasicMessagesRepo.{Handshake, RequestModifiersNetworkMessage}
import encry.network.DeliveryManager
import encry.network.DeliveryManagerTests.DMUtils.{generateBlocks, initialiseDeliveryManager, toKey}
import encry.network.NodeViewSynchronizer.ReceivableMessages.{CheckDelivery, HandshakedPeer, OtherNodeSyncingStatus, RequestFromLocal}
import encry.network.PeerConnectionHandler.{ConnectedPeer, Incoming}
import encry.settings.EncryAppSettings
import encry.utils.CoreTaggedTypes.ModifierId
import encry.view.history.EncryHistory
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}
import scala.concurrent.duration._
import scala.collection.mutable.WrappedArray

class DeliveryManagerReRequestModifiesSpec extends WordSpecLike
  with BeforeAndAfterAll
  with Matchers
  with InstanceFactory
  with OneInstancePerTest {

  implicit val system: ActorSystem = ActorSystem("SynchronousTestingSpec")
  val settings: EncryAppSettings = DummyEncryAppSettingsReader.read

  override def afterAll(): Unit = system.terminate()

  "ReRequestModifies" should {
    val testProbeActor: TestProbe = TestProbe()
    val initialState = initialiseDeliveryManager(isBlockChainSynced = true, isMining = true, settings)
    val deliveryManager: TestActorRef[DeliveryManager] = initialState._1
    val newPeer = new InetSocketAddress("172.16.13.10", 9001)
    val peer: ConnectedPeer = ConnectedPeer(newPeer, testProbeActor.ref, Incoming,
      Handshake(protocolToBytes(settings.network.appVersion), "peer", Some(newPeer), System.currentTimeMillis()))
    val blocks: (EncryHistory, List[Block]) = generateBlocks(10, generateDummyHistory(settings))
    val headerIds: List[ModifierId] = blocks._2.map(_.header.id)
    deliveryManager ! HandshakedPeer(peer)
    deliveryManager ! OtherNodeSyncingStatus(peer, Older, None)

    "re-ask necessary modifier several times (number of attempts from settings) and remove modifier from " +
      "expectedModifiers collection after all the attempts will expire" in {
      deliveryManager ! RequestFromLocal(peer, Header.modifierTypeId, Seq(headerIds.head))
      //settings.network.maxDeliveryChecks + (1) is using for expecting CheckDelivery which will trigger modifier removal
      testProbeActor.receiveN(settings.network.maxDeliveryChecks + 1,
        ((settings.network.maxDeliveryChecks + 1) * settings.network.deliveryTimeout._1).seconds)
      //this thread sleep is using for expecting modifier removal
      Thread.sleep((settings.network.deliveryTimeout._1 + 1) * 1000)
      assert(deliveryManager.underlyingActor.expectedModifiers.getOrElse(newPeer.getAddress, Map.empty).isEmpty)
    }
    "don't re-ask unnecessary modifiers" in {
      (0 to headerIds.size).foreach { _ =>
        deliveryManager ! CheckDelivery(peer, Header.modifierTypeId, headerIds.head)
        testProbeActor.expectNoMsg(0.5.seconds)
        assert(deliveryManager.underlyingActor.expectedModifiers
          .getOrElse(peer.socketAddress.getAddress, Map.empty).isEmpty)
      }
    }
    "remove expired modifier from expectedModifiers collection" in {
      deliveryManager ! RequestFromLocal(peer, Header.modifierTypeId, Seq(headerIds.head))
      (0 to settings.network.maxDeliveryChecks).foreach(_ =>
        deliveryManager ! CheckDelivery(peer, Header.modifierTypeId, headerIds.head))
      assert(deliveryManager.underlyingActor.expectedModifiers
        .getOrElse(peer.socketAddress.getAddress, Map.empty).isEmpty)
    }
    "correctly handle reRequest for modifier which number of tries has expired" in {
      deliveryManager ! RequestFromLocal(peer, Header.modifierTypeId, Seq(headerIds.head))
      (0 until settings.network.maxDeliveryChecks).foreach(_ =>
        deliveryManager ! CheckDelivery(peer, Header.modifierTypeId, headerIds.head))
      deliveryManager ! RequestFromLocal(peer, Header.modifierTypeId, Seq(headerIds.head))
      testProbeActor.expectMsg(RequestModifiersNetworkMessage(Header.modifierTypeId -> Seq(headerIds.head)))
      assert(deliveryManager.underlyingActor.expectedModifiers.size == 1)
    }
    "remove peer from expectedModifiers if expected modifiers collection from this peer is empty" in {
      deliveryManager ! RequestFromLocal(peer, Header.modifierTypeId, Seq(headerIds.head))
      (0 to settings.network.maxDeliveryChecks).foreach(_ =>
        deliveryManager ! CheckDelivery(peer, Header.modifierTypeId, headerIds.head))
      assert(deliveryManager.underlyingActor.expectedModifiers
        .getOrElse(peer.socketAddress.getAddress, Map.empty) == Map.empty)
    }
    "remove reRequested transactions from expected modifiers if CheckDelivery " in {
      val transactions: Seq[ModifierId] = genValidPaymentTxs(2).map(_.id)
      val txsToKey: Seq[WrappedArray.ofByte] = transactions.map(toKey)
      deliveryManager ! RequestFromLocal(peer, Transaction.ModifierTypeId, transactions)
      deliveryManager ! CheckDelivery(peer, Transaction.ModifierTypeId, transactions.head)
      assert(deliveryManager.underlyingActor.expectedModifiers
        .getOrElse(peer.socketAddress.getAddress, Map.empty).size == 9)
      assert(deliveryManager.underlyingActor.expectedModifiers
        .getOrElse(peer.socketAddress.getAddress, Map.empty).forall(id => txsToKey.tail.contains(id._1)))
      transactions.foreach(tx => deliveryManager ! CheckDelivery(peer, Transaction.ModifierTypeId, tx))
      assert(deliveryManager.underlyingActor.expectedModifiers
        .getOrElse(peer.socketAddress.getAddress, Map.empty) == Map.empty)
    }
  }
}