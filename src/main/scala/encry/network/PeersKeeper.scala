package encry.network

import java.net.{InetAddress, InetSocketAddress}
import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.StrictLogging
import encry.network.BlackList.{BanReason, SentPeersMessageWithoutRequest}
import encry.network.NetworkController.ReceivableMessages.{DataFromPeer, RegisterMessagesHandler}
import encry.network.PeerConnectionHandler._
import encry.network.PeersKeeper._
import encry.settings.EncryAppSettings
import encry.cli.commands.AddPeer.PeerFromCli
import encry.consensus.History.HistoryComparisonResult
import encry.network.NodeViewSynchronizer.ReceivableMessages._
import encry.network.PeerConnectionHandler.ReceivableMessages.CloseConnection
import encry.network.PrioritiesCalculator.AccumulatedPeersStatistic
import encry.network.PrioritiesCalculator.PeersPriorityStatus.PeersPriorityStatus
import org.encryfoundation.common.network.BasicMessagesRepo._
import scala.concurrent.duration._
import scala.util.Random

class PeersKeeper(settings: EncryAppSettings, nodeViewSync: ActorRef) extends Actor with StrictLogging {

  import context.dispatcher

  val connectWithOnlyKnownPeers: Boolean = settings.network.connectOnlyWithKnownPeers.getOrElse(true)

  val connectedPeers: ConnectedPeersList = new ConnectedPeersList(settings)

  val blackList: BlackList = new BlackList(settings)

  var availablePeers: Set[InetSocketAddress] = settings.network.knownPeers.toSet

  var connectionInProgress: Set[InetSocketAddress] = Set.empty

  var outgoingConnections: Set[InetSocketAddress] = Set.empty

  override def preStart(): Unit = {
    nodeViewSync ! RegisterMessagesHandler(Seq(
      PeersNetworkMessage.NetworkMessageTypeID -> "PeersNetworkMessage",
      GetPeersNetworkMessage.NetworkMessageTypeID -> "GetPeersNetworkMessage"
    ), self)
    if (!connectWithOnlyKnownPeers) context.system.scheduler.schedule(2.seconds, settings.network.syncInterval)(
      self ! SendToNetwork(GetPeersNetworkMessage, SendToRandom)
    )
    context.system.scheduler.schedule(5.seconds, settings.blackList.cleanupTime)(blackList.cleanupBlackList())
    context.system.scheduler.schedule(settings.network.syncInterval, settings.network.syncInterval)(
      self ! SendLocalSyncInfo
    )
    context.system.scheduler.schedule(10.seconds, 5.seconds)(
      nodeViewSync ! UpdatedPeersCollection(connectedPeers.getPeersForDeliveryManager)
    )
  }

  override def receive: Receive = setupConnectionsLogic
    .orElse(networkMessagesProcessingLogic)
    .orElse(banPeersLogic)
    .orElse {
      case OtherNodeSyncingStatus(remote, comparison, _) => connectedPeers.updatePeerComparisonStatus(remote, comparison)
      case AccumulatedPeersStatistic(statistic) => connectedPeers.updatePeersPriorityStatus(statistic)
      case SendToNetwork(message, strategy) =>
        strategy.choose(connectedPeers.getAllConnectedPeers).foreach { peer =>
          logger.info(s"Sending message: ${message.messageName} to: ${peer.socketAddress}.")
          peer.handlerRef ! message
        }
      case SendLocalSyncInfo =>
        val peersForConnect: Seq[ConnectedPeer] = connectedPeers.getPeersForSyncInfo
        logger.info(s"Time to send sync info from PK! Current peers are: ${peersForConnect.mkString(",")}")
        if (peersForConnect.nonEmpty) nodeViewSync ! PeersForSyncInfo(peersForConnect)
      case GetConnectedPeers => sender() ! connectedPeers.getAllConnectedPeers
      case GetInfoAboutConnectedPeers => sender() ! connectedPeers.getPeers
      case PeerFromCli(peer) =>
        if (!blackList.contains(peer.getAddress) && !availablePeers.contains(peer) && connectedPeers.contains(peer)) {
          outgoingConnections += peer
          sender() ! PeerForConnection(peer)
        }

      case msg => logger.info(s"Peers keeper got unhandled message: $msg.")
    }

  def setupConnectionsLogic: Receive = {
    case RequestPeerForConnection if connectedPeers.size < settings.network.maxConnections =>
      logger.info(s"Got request for new connection. Current number of connections is: ${connectedPeers.size}, " +
        s"so peer keeper allows to add one more connect. Current avalible peers are: ${availablePeers.mkString(",")}.")
      Random.shuffle(availablePeers.filterNot(p => connectionInProgress.contains(p) || connectedPeers.contains(p)))
        .headOption
        .foreach { peer =>
          logger.info(s"Selected peer: $peer. Sending 'PeerForConnection' message to network controller. " +
            s"Adding new outgoing connection to outgoingConnections collection. Current collection is: " +
            s"${outgoingConnections.mkString(",")}.")
          sender() ! PeerForConnection(peer)
          outgoingConnections += peer
          //availablePeers -= peer
          connectionInProgress += peer
        }

    case RequestPeerForConnection =>
      logger.info(s"Got request for a new connection but current number of connection is max: ${connectedPeers.size}.")

    case RequestForStableConnection(remote, remoteConnection) if connectedPeers.size < settings.network.maxConnections && !isLocal(remote) =>
      logger.info(s"Peers keeper got request for a stable connection with remote: $remote.")
      val notConnectedYet: Boolean = !connectedPeers.contains(remote)
      val notBannedPeer: Boolean = !blackList.contains(remote.getAddress)
      if (notConnectedYet && notBannedPeer) {
        logger.info(s"Peer: $remote is available to setup stable connect with.")
        (if (outgoingConnections.contains(remote)) Outgoing else Incoming) match {
          case _@Incoming if connectWithOnlyKnownPeers =>
            logger.info(s"Got incoming connection but we can connect only with known peers.")
          case in@Incoming =>
            logger.info(s"Got new incoming connection. Sending to network controller approvement for connect.")
            sender() ! CreateStableConnection(remote, remoteConnection, in)
          case out@Outgoing =>
            logger.info(s"Got outgoing connection.")
            outgoingConnections -= remote
            sender() ! CreateStableConnection(remote, remoteConnection, out)
        }
        logger.info(s"Adding new peer: $remote to connectionInProgress." +
          s" Current in progress is: ${connectionInProgress.mkString(",")}")
      } else logger.info(s"Connection for requested peer: $remote is unavailable cause of:" +
        s" Is banned: $notBannedPeer, Is connected: $notConnectedYet.")

    case RequestForStableConnection(remote, _) =>
      logger.info(s"Peers keeper got request for a stable connection but current number of max connection is " +
        s"bigger than possible or isLocal: ${isLocal(remote)}.")

    case StableConnectionSetup(connectedPeer) =>
      logger.info(s"Peers keeper got approvement about stable connection. Initializing new peer: ${connectedPeer.socketAddress}")
      connectedPeers.initializePeer(connectedPeer)
      logger.info(s"Remove  ${connectedPeer.socketAddress} from connectionInProgress collection. Current is: " +
        s"${connectionInProgress.mkString(",")}.")
      connectionInProgress -= connectedPeer.socketAddress

    case ConnectionStopped(peer) =>
      logger.info(s"Connection stopped for: $peer.")
      connectedPeers.removePeer(peer)
      if (blackList.contains(peer.getAddress)) {
        availablePeers -= peer
        logger.info(s"New available peer removed from availablePeers. Current is: ${availablePeers.mkString(",")}.")
      }

    case OutgoingConnectionFailed(peer) =>
      logger.info(s"Connection failed for: $peer.")
      outgoingConnections -= peer
      connectionInProgress -= peer
      //availablePeers += peer
  }

  def networkMessagesProcessingLogic: Receive = {
    case DataFromPeer(message, remote) => message match {
      case PeersNetworkMessage(peers) if !connectWithOnlyKnownPeers => peers
        .filterNot(p => blackList.contains(p.getAddress) || connectedPeers.contains(p) || isLocal(p))
        .foreach { p =>
          logger.info(s"Found new peer: $p. Adding it to the available peers collection.")
          availablePeers += p
        }

      case PeersNetworkMessage(_) =>
        logger.info(s"Got PeersNetworkMessage from $remote, but connectWithOnlyKnownPeers: $connectWithOnlyKnownPeers, " +
          s"so ignore this message and ban this peer.")
        self ! BanPeer(remote, SentPeersMessageWithoutRequest)

      case GetPeersNetworkMessage =>
        val peers: Seq[InetSocketAddress] = connectedPeers.getAll.filter(address =>
          if (remote.socketAddress.getAddress.isSiteLocalAddress) true
          else address.getAddress.isSiteLocalAddress && address != remote.socketAddress
        )
        logger.info(s"Got request for local known peers. Sending to: $remote peers: ${peers.mkString(",")}.")
        remote.handlerRef ! PeersNetworkMessage(peers)
    }
  }

  def banPeersLogic: Receive = {
    case BanPeer(peer, reason) =>
      logger.info(s"Banning peer: ${peer.socketAddress} for $reason.")
      blackList.banPeer(reason, peer.socketAddress.getAddress)
      peer.handlerRef ! CloseConnection
  }

  def isLocal(address: InetSocketAddress): Boolean =
    address == settings.network.bindAddress ||
      InetAddress.getLocalHost.getAddress.sameElements(address.getAddress.getAddress) ||
      InetAddress.getLoopbackAddress.getAddress.sameElements(address.getAddress.getAddress) ||
      settings.network.declaredAddress.contains(address)

}

object PeersKeeper {

  final case class RequestForStableConnection(peer: InetSocketAddress,
                                              remoteConnection: ActorRef)

  final case class CreateStableConnection(peer: InetSocketAddress,
                                          remoteConnection: ActorRef,
                                          ct: ConnectionType)

  final case class OutgoingConnectionFailed(peer: InetSocketAddress)

  final case class StableConnectionSetup(peer: ConnectedPeer)

  final case class ConnectionStopped(peer: InetSocketAddress)

  case object RequestPeerForConnection

  final case class PeerForConnection(peer: InetSocketAddress)

  final case class SendToNetwork(message: NetworkMessage,
                                 sendingStrategy: SendingStrategy)

  final case class PeersForSyncInfo(peers: Seq[ConnectedPeer])

  final case class UpdatedPeersCollection(peers: Map[InetAddress, (ConnectedPeer, HistoryComparisonResult, PeersPriorityStatus)])

  case object AwaitingOlderPeer

  final case class BanPeer(peer: ConnectedPeer, reason: BanReason)


  case object GetConnectedPeers

  case object GetKnownPeers

  case object GetInfoAboutConnectedPeers

  def props(settings: EncryAppSettings, nodeViewSync: ActorRef): Props = Props(new PeersKeeper(settings, nodeViewSync))
}