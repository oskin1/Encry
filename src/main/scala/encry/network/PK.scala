package encry.network

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.StrictLogging
import encry.network.BlackList.{BanReason, BanTime, BanType}
import encry.network.MessageBuilder.{GetPeerInfo, GetPeers}
import encry.network.PeerConnectionHandler.ReceivableMessages.CloseConnection
import encry.network.PeerConnectionHandler.{Incoming, Outgoing}
import encry.network.PeersKeeper.{BanPeer, BanPeerFromAPI, PeerForConnection, RequestPeerForConnection}
import encry.network.PeersKeeper.ConnectionStatusMessages.{ConnectionStopped, ConnectionVerified, HandshakedDone, NewConnection}
import encry.settings.{BlackListSettings, NetworkSettings}

import scala.util.{Random, Try}

class PK(networkSettings: NetworkSettings,
         blacklistSettings: BlackListSettings) extends Actor with StrictLogging {

  val connectWithOnlyKnownPeers: Boolean = networkSettings.connectOnlyWithKnownPeers.getOrElse(true)

  var connectedPeers: ConnectedPeersCollection = ConnectedPeersCollection()

  var blackList: BlackList = BlackList(blacklistSettings)

  var knownPeers: Set[InetAddress] = networkSettings.knownPeers
    .collect { case peer: InetSocketAddress if !isSelf(peer) => peer.getAddress }.toSet

  var outgoingConnections: Set[InetSocketAddress] = Set.empty

  var awaitingHandshakeConnections: Set[InetSocketAddress] = Set.empty

  var peersForConnection: Map[InetSocketAddress, Int] = networkSettings.knownPeers
    .collect { case peer: InetSocketAddress if !isSelf(peer) => peer -> 0 }.toMap

  override def receive: Receive = banPeersLogic orElse {
    case RequestPeerForConnection if connectedPeers.size < networkSettings.maxConnections =>
      def mapReason(address: InetAddress, r: BanReason, t: BanTime, bt: BanType): (InetAddress, BanReason) = address -> r
      logger.info(s"Got request for new connection. Current number of connections is: ${connectedPeers.size}, " +
        s"so peer keeper allows to add one more connection. Current available peers are: " +
        s"${peersForConnection.mkString(",")}. Current black list is: ${
          blackList.collect((_, _, _, _) => true, mapReason).mkString(",")
        }. Current known peers: ${knownPeers.mkString(",")}.")
      logger.info(s"awaitingHandshakeConnections ${awaitingHandshakeConnections.mkString(",")}")
      logger.info(s"connectedPeers.getAll ${connectedPeers.getAll.mkString(",")}")
      val peers = peersForConnection
        .filterNot(p => awaitingHandshakeConnections.contains(p._1) || connectedPeers.contains(p._1))
      logger.info(s"peers size: ${peers.size}")
      Random.shuffle(peers.toSeq)
        .headOption
        .foreach { case (peer, _) =>
          outgoingConnections += peer
          logger.info(s"Selected peer: $peer. Sending 'PeerForConnection' message to network controller. " +
            s"Adding new outgoing connection to outgoingConnections collection. Current collection is: " +
            s"${outgoingConnections.mkString(",")}.")
          sender() ! PeerForConnection(peer)
          awaitingHandshakeConnections += peer
          logger.info(s"Adding new peer: $peer to awaitingHandshakeConnections." +
            s" Current is: ${awaitingHandshakeConnections.mkString(",")}")
        }
    case NewConnection(remote, remoteConnection) if connectedPeers.size < networkSettings.maxConnections && !isSelf(remote) =>
      logger.info(s"Peers keeper got request for verifying the connection with remote: $remote. " +
        s"Remote InetSocketAddress is: $remote. Remote InetAddress is ${remote.getAddress}. " +
        s"Current known peers: ${knownPeers.mkString(",")}")
      val notConnectedYet: Boolean = !connectedPeers.contains(remote)
      val notBannedPeer: Boolean = !blackList.contains(remote.getAddress)
      if (notConnectedYet && notBannedPeer) {
        logger.info(s"Peer: $remote is available to setup connect with.")
        if (outgoingConnections.contains(remote)) {
          logger.info(s"Got outgoing connection.")
          outgoingConnections -= remote
          sender() ! ConnectionVerified(remote, remoteConnection, Outgoing)
        }
        else if (connectWithOnlyKnownPeers && knownPeers.contains(remote.getAddress)) {
          logger.info(s"connectWithOnlyKnownPeers - true, but connected peer is contained in known peers collection.")
          sender() ! ConnectionVerified(remote, remoteConnection, Incoming)
        }
        else if (connectWithOnlyKnownPeers)
          logger.info(s"Got incoming connection but we can connect only with known peers.")
        else {
          logger.info(s"Got new incoming connection. Sending to network controller approvement for connect.")
          sender() ! ConnectionVerified(remote, remoteConnection, Incoming)
        }
      } else logger.info(s"Connection for requested peer: $remote is unavailable cause of:" +
        s" Didn't banned: $notBannedPeer, Didn't connected: $notConnectedYet.")

    case NewConnection(remote, _) =>
      logger.info(s"Peers keeper got request for verifying the connection but current number of max connection is " +
        s"bigger than possible or isSelf: ${isSelf(remote)}.")
    case HandshakedDone(connectedPeer) =>
      logger.info(s"Peers keeper got approvement about finishing a handshake." +
        s" Initializing new peer: ${connectedPeer.socketAddress}")
      connectedPeers = connectedPeers.initializePeer(connectedPeer)
      logger.info(s"Remove  ${connectedPeer.socketAddress} from awaitingHandshakeConnections collection. Current is: " +
        s"${awaitingHandshakeConnections.mkString(",")}.")
      awaitingHandshakeConnections -= connectedPeer.socketAddress
      peersForConnection = peersForConnection.updated(connectedPeer.socketAddress, 0)
      logger.info(s"Adding new peer: ${connectedPeer.socketAddress} to available collection." +
        s" Current collection is: ${peersForConnection.keys.mkString(",")}.")

    case ConnectionStopped(peer) =>
      logger.info(s"Connection stopped for: $peer.")
      awaitingHandshakeConnections -= peer
      connectedPeers = connectedPeers.removePeer(peer)
      if (blackList.contains(peer.getAddress)) {
        peersForConnection -= peer
        logger.info(s"Peer: $peer removed from availablePeers cause of it has been banned. " +
          s"Current is: ${peersForConnection.mkString(",")}.")
      }
    case GetPeers => sender() ! connectedPeers.getAll.map(_._2.connectedPeer)
    case GetPeerInfo(peerIp) => connectedPeers.getAll.find(_._1 == peerIp).map {
      case (_, info) => sender() ! info.connectedPeer
    }
  }

  def banPeersLogic: Receive = {
    case BanPeer(peer, reason) =>
      logger.info(s"Banning peer: ${peer} for $reason.")
      blackList = blackList.banPeer(reason, peer.getAddress)
      connectedPeers.getAll.find(_._1 == peer).map(_._2.connectedPeer.handlerRef ! CloseConnection)

    case BanPeerFromAPI(peer, reason) =>
      logger.info(s"Got msg from API... Removing peer: $peer, reason: $reason")
      blackList = blackList.banPeer(reason, peer.getAddress)
  }

  def isSelf(address: InetSocketAddress): Boolean = Try(address == networkSettings.bindAddress ||
    networkSettings.declaredAddress.contains(address) ||
    InetAddress.getLocalHost.getAddress.sameElements(address.getAddress.getAddress) ||
    InetAddress.getLoopbackAddress.getAddress.sameElements(address.getAddress.getAddress)).getOrElse(true)
}

object PK {
  def props(networkSettings: NetworkSettings,
            blacklistSettings: BlackListSettings): Props = Props(new PK(networkSettings, blacklistSettings))
}
