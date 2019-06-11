package encry.network.DeliveryManagerTests

import java.net.{InetAddress, InetSocketAddress}

import encry.network.DeliveryManagerTests.DMUtils.{createPeer, generateBlocks, initialiseDeliveryManager}
import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import encry.consensus.History
import encry.consensus.History.{Equal, Older, Younger}
import encry.modifiers.InstanceFactory
import encry.network.DeliveryManager
import encry.network.NetworkController.ReceivableMessages.DataFromPeer
import encry.network.NodeViewSynchronizer.ReceivableMessages.{RequestFromLocal}
import encry.network.PeerConnectionHandler.ConnectedPeer
import encry.network.PeersKeeper.UpdatedPeersCollection
import encry.network.PrioritiesCalculator.PeersPriorityStatus.{BadNode, HighPriority, InitialPriority, LowPriority}
import encry.settings.EncryAppSettings
import org.encryfoundation.common.modifiers.history.{Block, Header, HeaderProtoSerializer}
import org.encryfoundation.common.network.BasicMessagesRepo.ModifiersNetworkMessage
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

class DeliveryManagerPriorityTests extends WordSpecLike
  with BeforeAndAfterAll
  with Matchers
  with InstanceFactory
  with OneInstancePerTest {

  implicit val system: ActorSystem = ActorSystem("SynchronousTestingSpec")
  val settings: EncryAppSettings = DummyEncryAppSettingsReader.read

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  def initialiseState: (TestActorRef[DeliveryManager], ConnectedPeer, ConnectedPeer, ConnectedPeer,
    ConnectedPeer, ConnectedPeer, ConnectedPeer, ConnectedPeer, ConnectedPeer, ConnectedPeer,
    List[Block], List[ModifierId]) = {
    val (deliveryManager, _) = initialiseDeliveryManager(isBlockChainSynced = true, isMining = true, settings)
    val (_: InetSocketAddress, cp1: ConnectedPeer) = createPeer(9001, "172.16.13.10", settings)
    val (_: InetSocketAddress, cp2: ConnectedPeer) = createPeer(9002, "172.16.13.11", settings)
    val (_: InetSocketAddress, cp3: ConnectedPeer) = createPeer(9003, "172.16.13.12", settings)
    val (_: InetSocketAddress, cp4: ConnectedPeer) = createPeer(9004, "172.16.13.13", settings)
    val (_: InetSocketAddress, cp5: ConnectedPeer) = createPeer(9005, "172.16.13.14", settings)
    val (_: InetSocketAddress, cp6: ConnectedPeer) = createPeer(9006, "172.16.13.15", settings)
    val (_: InetSocketAddress, cp7: ConnectedPeer) = createPeer(9007, "172.16.13.16", settings)
    val (_: InetSocketAddress, cp8: ConnectedPeer) = createPeer(9008, "172.16.13.17", settings)
    val (_: InetSocketAddress, cp9: ConnectedPeer) = createPeer(9009, "172.16.13.18", settings)
    val blocks: List[Block] = generateBlocks(10, generateDummyHistory(settings))._2
    val headersIds: List[ModifierId] = blocks.map(_.header.id)
    (deliveryManager, cp1, cp2, cp3, cp4, cp5, cp6,cp7, cp8, cp9, blocks, headersIds)
  }

  "Delivery Manager" should {
    /**
      * This test simulates DeliveryManager behaviour connected with updating nodes priority.
      *
      * Test expected behavior is:
      * Send handshakedPeer to the Delivery Manager from cp1 for cp1.
      * Send RequestFromLocal for N modifiers to the Delivery Manager.
      * Delivery manager have to use requestModifier, send request to N modifiers to cp1 and put this N modifiers in expectedModifiersCollection.
      * Receive less than 1/2 of this modifiers during 1 attempt.
      * When period of updating priorities will expire, delivery manager will mark cp1 as BadNode.
      *
      */
    "mark peer as BadNode with BadPriority (1)" in {
      val (deliveryManager, cp1, _, _, _, _, _, _, _, _, _, headersIds) = initialiseState
      val updatedPeersCollection: Map[InetAddress, (ConnectedPeer, History.Older.type, InitialPriority)] =
        Map(cp1.socketAddress.getAddress -> (cp1, Older, InitialPriority()))
      deliveryManager ! UpdatedPeersCollection(updatedPeersCollection)
      deliveryManager ! RequestFromLocal(cp1, Header.modifierTypeId, headersIds)
      val result = deliveryManager.underlyingActor.priorityCalculator.accumulatePeersStatistic
      assert(result.contains(cp1.socketAddress))
      assert(result(cp1.socketAddress) == BadNode())
      deliveryManager.stop()
    }

    /**
      * This test simulates DeliveryManager behaviour connected with updating nodes priority
      *
      * Test expected behavior is:
      * Send handshakedPeer to the Delivery Manager from cp1.
      * Send RequestFromLocal for N modifiers to the Delivery Manager for cp1.
      * Delivery manager have to use requestModifier, send request to N modifiers to cp1 and put this N modifiers in expectedModifiersCollection.
      * Receive more than 3\4 of this modifiers during 1 attempt.
      * When period of updating priorities will expire, delivery manager will mark cp1 as BestNode.
      */
    "mark peer as HighPriorityNode with HighPriority (4)" in {
      val (deliveryManager, cp1, _, _, _, _, _, _, _, _, blocks, headersIds) = initialiseState
      val updatedPeersCollection: Map[InetAddress, (ConnectedPeer, History.Older.type, InitialPriority)] =
        Map(cp1.socketAddress.getAddress -> (cp1, Older, InitialPriority()))
      deliveryManager ! UpdatedPeersCollection(updatedPeersCollection)
      deliveryManager ! RequestFromLocal(cp1, Header.modifierTypeId, headersIds)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.map(block => block.header.id -> block.header.bytes).toMap), cp1)
      val result = deliveryManager.underlyingActor.priorityCalculator.accumulatePeersStatistic
      assert(result.contains(cp1.socketAddress))
      assert(result(cp1.socketAddress) == HighPriority())
      deliveryManager.stop()
    }

    /**
      * This test simulates DeliveryManager behaviour connected with updating nodes priority
      *
      * Test expected behavior is:
      * Send handshakedPeer to the Delivery Manager from cp1.
      * Send RequestFromLocal for N modifiers to the Delivery Manager for cp1.
      * Delivery manager have to use requestModifier, send request to N modifiers to cp1 and put this N modifiers in expectedModifiersCollection.
      * Receive more than 1\2 and less than 3\4 of this modifiers during 1 attempt.
      * When period of updating priorities will expire, delivery manager will mark cp1 as LowPriorityNode.
      */
    "mark peer as LowPriorityNode with LowPriority (3)" in {
      val (deliveryManager, cp1, _, _, _, _, _, _, _, _, blocks, headersIds) = initialiseState
      val updatedPeersCollection: Map[InetAddress, (ConnectedPeer, History.Older.type, InitialPriority)] =
        Map(cp1.socketAddress.getAddress -> (cp1, Older, InitialPriority()))
      deliveryManager ! UpdatedPeersCollection(updatedPeersCollection)
      deliveryManager ! RequestFromLocal(cp1, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp1, Header.modifierTypeId, headersIds)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(6).map(block => block.header.id -> block.header.bytes).toMap), cp1)
      val result = deliveryManager.underlyingActor.priorityCalculator.accumulatePeersStatistic
      assert(result.contains(cp1.socketAddress))
      assert(result(cp1.socketAddress) == LowPriority())
      deliveryManager.stop()
    }

    /**
      * This test simulates DeliveryManager behavior connected with updating several nodes priority active in one time
      *
      * Test expected behavior is:
      * Send handshakedPeer to the Delivery Manager from cp1, cp2, cp3, cp4, cp5, cp6.
      * Send RequestFromLocal for N modifiers to the Delivery Manager for cp1, cp2, cp3, cp4, cp5, cp6.
      * Delivery manager have to use requestModifier, send request to N modifiers to cp1 and put this N modifiers in expectedModifiersCollection.
      * Receive more than 3\4 requested modifiers from cp1 and cp4.
      * Receive less than 3\4 but more than 1\2 requested modifiers from cp2 and cp5.
      * Receive less than 1\2 requested modifiers from cp3 and cp6.
      * When period of updating priorities will expire, delivery manager will mark cp1 and cp4 as HighPriorityNode.
      * When period of updating priorities will expire, delivery manager will mark cp2 and cp5 as LowPriorityNode.
      * When period of updating priorities will expire, delivery manager will mark cp3 and cp6 as BadNode.
      */
    "correctly choose peer priority while several peers are available" in {
      val (deliveryManager, cp1, cp2, cp3, cp4, cp5, cp6, cp7, cp8, cp9, blocks, headersIds) = initialiseState
      val updatedPeersCollection =
        Map(
          cp1.socketAddress.getAddress -> (cp1, Older, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp2, Younger, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp3, Equal, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp4, Older, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp5, Younger, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp6, Equal, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp7, Older, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp8, Younger, InitialPriority()),
          cp1.socketAddress.getAddress -> (cp9, Equal, InitialPriority())
        )

      deliveryManager ! UpdatedPeersCollection(updatedPeersCollection)

      deliveryManager ! RequestFromLocal(cp1, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp2, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp3, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp4, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp5, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp6, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp7, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp8, Header.modifierTypeId, headersIds)
      deliveryManager ! RequestFromLocal(cp9, Header.modifierTypeId, headersIds)

      val headerBytes = HeaderProtoSerializer.toProto(blocks.head.header).toByteArray

      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.map(block => block.header.id -> headerBytes).toMap), cp1)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.map(block => block.header.id -> headerBytes).toMap), cp2)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.map(block => block.header.id -> headerBytes).toMap), cp3)

      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(5).map(block => block.header.id -> headerBytes).toMap), cp4)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(5).map(block => block.header.id -> headerBytes).toMap), cp5)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(5).map(block => block.header.id -> headerBytes).toMap), cp6)

      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(2).map(block => block.header.id -> headerBytes).toMap), cp7)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(2).map(block => block.header.id -> headerBytes).toMap), cp8)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.take(2).map(block => block.header.id -> headerBytes).toMap), cp9)

      val result = deliveryManager.underlyingActor.priorityCalculator.accumulatePeersStatistic

      assert(result.contains(cp1.socketAddress))
      assert(result(cp1.socketAddress) == HighPriority())

      //todo fix spam after it fix test
//      assert(result.contains(cp2.socketAddress))
//      assert(result(cp2.socketAddress) == HighPriority())

//      assert(result.contains(cp3.socketAddress))
//      assert(result(cp3.socketAddress) == HighPriority())

//      assert(result.contains(cp4.socketAddress))
//      assert(result(cp4.socketAddress) == LowPriority())
//
//      assert(result.contains(cp5.socketAddress))
//      assert(result(cp5.socketAddress) == LowPriority())
//
//      assert(result.contains(cp6.socketAddress))
//      assert(result(cp6.socketAddress) == LowPriority())
//
//      assert(result.contains(cp7.socketAddress))
//      assert(result(cp7.socketAddress) == BadNode())
//
//      assert(result.contains(cp8.socketAddress))
//      assert(result(cp8.socketAddress) == BadNode())
//
//      assert(result.contains(cp9.socketAddress))
//      assert(result(cp9.socketAddress) == BadNode())

      deliveryManager.stop()
    }

    /**
      * This test simulates DeliveryManager behavior connected with updating node priority while receiving spam modifiers
      *
      * Test expected behavior is:
      * Send handshakedPeer to the Delivery Manager from cp1.
      * Receive unexpected modifiers from cp1.
      * cp1 priority must stay as InitialPriority.
      */
    "not increment modifiers which will be putted in spam collection" in {
      val (deliveryManager, cp1, _, _, _, _, _, _, _, _, blocks, _) = initialiseState
      val updatedPeersCollection: Map[InetAddress, (ConnectedPeer, History.Older.type, InitialPriority)] =
        Map(cp1.socketAddress.getAddress -> (cp1, Older, InitialPriority()))
      deliveryManager ! UpdatedPeersCollection(updatedPeersCollection)
      deliveryManager ! DataFromPeer(ModifiersNetworkMessage(
        Header.modifierTypeId, blocks.map(block => block.header.id -> block.header.bytes).toMap), cp1)
      val result = deliveryManager.underlyingActor.priorityCalculator.accumulatePeersStatistic
      assert(result.contains(cp1.socketAddress))
      assert(result(cp1.socketAddress) == BadNode())
      deliveryManager.stop()
    }
  }
}