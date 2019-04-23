package encry.network.DeliveryManagerTests

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import encry.local.miner.Miner.StartMining
import encry.modifiers.InstanceFactory
import encry.modifiers.history.Block
import encry.network.BasicMessagesRepo.Handshake
import encry.network.DeliveryManager
import encry.network.DeliveryManager.FullBlockChainIsSynced
import encry.network.NodeViewSynchronizer.ReceivableMessages.UpdatedHistory
import encry.network.PeerConnectionHandler.{ConnectedPeer, Incoming}
import encry.settings.EncryAppSettings
import encry.utils.CoreTaggedTypes.ModifierId
import encry.view.history.EncryHistory

import scala.collection.mutable
import scala.collection.mutable.WrappedArray

object DMUtils extends InstanceFactory {

  def initialiseDeliveryManager(isBlockChainSynced: Boolean,
                                isMining: Boolean,
                                settings: EncryAppSettings)
                               (implicit actorSystem: ActorSystem): (TestActorRef[DeliveryManager], EncryHistory) = {
    val history: EncryHistory = generateDummyHistory(settings)
    val deliveryManager: TestActorRef[DeliveryManager] =
      TestActorRef[DeliveryManager](DeliveryManager
        .props(None, TestProbe().ref, TestProbe().ref, settings, TestProbe().ref).withDispatcher("delivery-manager-dispatcher"))
    deliveryManager ! UpdatedHistory(history)
    if (isMining) deliveryManager ! StartMining
    if (isBlockChainSynced) deliveryManager ! FullBlockChainIsSynced
    (deliveryManager, history)
  }

  def generateBlocks(qty: Int, history: EncryHistory): (EncryHistory, List[Block]) =
    (0 until qty).foldLeft(history, List.empty[Block]) {
      case ((prevHistory, blocks), _) =>
        val block: Block = generateNextBlock(prevHistory)
        (prevHistory.append(block.header).get._1.append(block.payload).get._1.reportModifierIsValid(block), blocks :+ block)
    }

  def toKey(id: ModifierId): WrappedArray.ofByte = new mutable.WrappedArray.ofByte(id)

  def createPeer(port: Int,
                 host: String,
                 settings: EncryAppSettings)(implicit system: ActorSystem): (InetSocketAddress, ConnectedPeer) = {
    val address = new InetSocketAddress(host, port)
    val peer: ConnectedPeer = ConnectedPeer(address, TestProbe().ref, Incoming,
      Handshake(protocolToBytes(settings.network.appVersion), host, Some(address), System.currentTimeMillis()))
    (address, peer)
  }
}