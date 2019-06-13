package encry.network

import java.net.{InetAddress, InetSocketAddress}
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import encry.api.http.DataHolderForApi.UpdatingConnectedPeers
import encry.network.BlackList.{BanReason, ExpiredNumberOfConnections, SentPeersMessageWithoutRequest}
import encry.network.NetworkController.ReceivableMessages.{DataFromPeer, RegisterMessagesHandler}
import encry.network.PeerConnectionHandler._
import encry.network.PeersKeeper._
import encry.settings.EncryAppSettings
import encry.cli.commands.AddPeer.PeerFromCli
import encry.consensus.History.HistoryComparisonResult
import encry.network.ConnectedPeersList.PeerInfo
import encry.network.NodeViewSynchronizer.ReceivableMessages._
import encry.network.PeerConnectionHandler.ReceivableMessages.CloseConnection
import encry.network.PrioritiesCalculator.AccumulatedPeersStatistic
import encry.network.PrioritiesCalculator.PeersPriorityStatus.PeersPriorityStatus
import org.encryfoundation.common.network.BasicMessagesRepo._
import scala.concurrent.duration._

class PeersKeeper(settings: EncryAppSettings,
                  nodeViewSync: ActorRef,
                  dataHolder: ActorRef) extends Actor with StrictLogging {

  import context.dispatcher

  val connectWithOnlyKnownPeers: Boolean = settings.network.connectOnlyWithKnownPeers.getOrElse(true)

  val connectedPeers: ConnectedPeersList = new ConnectedPeersList(settings)

  val blackList: BlackList = new BlackList(settings)

  var availablePeers: Map[InetSocketAddress, Int] = settings.network.knownPeers.map(peer => peer -> 0).toMap

  var awaitingHandshakeConnections: Set[InetSocketAddress] = Set.empty

  var outgoingConnections: Set[InetSocketAddress] = Set.empty

  override def preStart(): Unit = {
    logger.info(s"Peers keeper started")
    nodeViewSync ! RegisterMessagesHandler(Seq(
      PeersNetworkMessage.NetworkMessageTypeID -> "PeersNetworkMessage",
      GetPeersNetworkMessage.NetworkMessageTypeID -> "GetPeersNetworkMessage"
    ), self)
    if (!connectWithOnlyKnownPeers) context.system.scheduler.schedule(2.seconds, settings.network.syncInterval)(
      self ! SendToNetwork(GetPeersNetworkMessage, SendToRandom)
    )
    context.system.scheduler.schedule(600.millis, settings.blackList.cleanupTime)(blackList.cleanupBlackList())
    context.system.scheduler.schedule(10.seconds, 10.seconds)(
      nodeViewSync ! UpdatedPeersCollection(connectedPeers.getPeersF((_, _) => true, getPeersForDMF).toMap)
    )
    context.system.scheduler.schedule(5.seconds, 5.seconds)(
      dataHolder ! UpdatingConnectedPeers(connectedPeers.getPeersF((_, _) => true, getConnectedPeersF).toSeq)
    )
  }

  override def receive: Receive = setupConnectionsLogic
    .orElse(networkMessagesProcessingLogic)
    .orElse(banPeersLogic)
    .orElse(additionalMessages)

  def setupConnectionsLogic: Receive = {
    case RequestPeerForConnection if connectedPeers.size < settings.network.maxConnections =>
      logger.info(s"Got request for new connection. Current number of connections is: ${connectedPeers.size}, " +
        s"so peer keeper allows to add one more connect. Current available peers are: " +
        s"${availablePeers.mkString(",")}. Current black list is: ${
          blackList.getBannedPeersAndReasons.mkString(",")
        }")
      availablePeers
        .filterNot(p => awaitingHandshakeConnections.contains(p._1) || connectedPeers.contains(p._1))
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

    case RequestPeerForConnection =>
      logger.info(s"Got request for a new connection but current number of connection is max: ${connectedPeers.size}.")

    case VerifyConnection(remote, remoteConnection) if connectedPeers.size < settings.network.maxConnections && !isLocal(remote) =>
      logger.info(s"Peers keeper got request for verifying the connection with remote: $remote.")
      val notConnectedYet: Boolean = !connectedPeers.contains(remote)
      val notBannedPeer: Boolean = !blackList.contains(remote.getAddress)
      if (notConnectedYet && notBannedPeer) {
        logger.info(s"Peer: $remote is available to setup connect with.")
        if (outgoingConnections.contains(remote)) {
          logger.info(s"Got outgoing connection.")
          outgoingConnections -= remote
          sender() ! ConnectionVerified(remote, remoteConnection, Outgoing)
        }
        else if (connectWithOnlyKnownPeers)
          logger.info(s"Got incoming connection but we can connect only with known peers.")
        else {
          logger.info(s"Got new incoming connection. Sending to network controller approvement for connect.")
          sender() ! ConnectionVerified(remote, remoteConnection, Incoming)
        }
      } else logger.info(s"Connection for requested peer: $remote is unavailable cause of:" +
        s" Didn't banned: $notBannedPeer, Didn't connected: $notConnectedYet.")

    case VerifyConnection(remote, _) =>
      logger.info(s"Peers keeper got request for verifying the connection but current number of max connection is " +
        s"bigger than possible or isLocal: ${isLocal(remote)}.")

    case HandshakedDone(connectedPeer) =>
      logger.info(s"Peers keeper got approvement about finishing a handshake." +
        s" Initializing new peer: ${connectedPeer.socketAddress}")
      connectedPeers.initializePeer(connectedPeer)
      logger.info(s"Remove  ${connectedPeer.socketAddress} from awaitingHandshakeConnections collection. Current is: " +
        s"${awaitingHandshakeConnections.mkString(",")}.")
      awaitingHandshakeConnections -= connectedPeer.socketAddress
      availablePeers = availablePeers.updated(connectedPeer.socketAddress, 0)
      logger.info(s"Adding new peer: ${connectedPeer.socketAddress} to available collection." +
        s" Current collection is: ${availablePeers.keys.mkString(",")}.")

    case ConnectionStopped(peer) =>
      logger.info(s"Connection stopped for: $peer.")
      connectedPeers.removePeer(peer)
      if (blackList.contains(peer.getAddress)) {
        availablePeers -= peer
        logger.info(s"Peer: $peer removed from availablePeers cause of it has been banned. " +
          s"Current is: ${availablePeers.mkString(",")}.")
      }

    case OutgoingConnectionFailed(peer) =>
      logger.info(s"Connection failed for: $peer.")
      outgoingConnections -= peer
      awaitingHandshakeConnections -= peer
      val connectionAttempts: Int = availablePeers.getOrElse(peer, 0) + 1
      if (connectionAttempts >= settings.network.maxNumberOfReConnections) {
        logger.info(s"Banning peer: $peer for ExpiredNumberOfConnections.")
        blackList.banPeer(ExpiredNumberOfConnections, peer.getAddress)
        availablePeers -= peer
      } else availablePeers = availablePeers.updated(peer, connectionAttempts)
  }

  def networkMessagesProcessingLogic: Receive = {
    case DataFromPeer(message, remote) => message match {
      case PeersNetworkMessage(peers) if !connectWithOnlyKnownPeers => peers
        .filterNot(p =>
          blackList.contains(p.getAddress) || connectedPeers.contains(p) || isLocal(p) || availablePeers.contains(p)
        )
        .foreach { p =>
          logger.info(s"Found new peer: $p. Adding it to the available peers collection.")
          availablePeers = availablePeers.updated(p, 0)
        }
        logger.info(s"New available peers collection are: ${availablePeers.keys.mkString(",")}.")

      case PeersNetworkMessage(_) =>
        logger.info(s"Got PeersNetworkMessage from $remote, but connectWithOnlyKnownPeers: $connectWithOnlyKnownPeers, " +
          s"so ignore this message and ban this peer.")
        self ! BanPeer(remote, SentPeersMessageWithoutRequest)

      case GetPeersNetworkMessage =>
        def getPeersForRemoteP(add: InetSocketAddress, info: PeerInfo): Boolean =
          if (remote.socketAddress.getAddress.isSiteLocalAddress) true
          else add.getAddress.isSiteLocalAddress && add != remote.socketAddress

        val peers: Seq[InetSocketAddress] = connectedPeers.getPeersF(getPeersForRemoteP, getPeersForRemoteF).toSeq
        logger.info(s"Got request for local known peers. Sending to: $remote peers: ${peers.mkString(",")}.")
        logger.info(s"Remote is side local: ${remote.socketAddress} : ${remote.socketAddress.getAddress.isSiteLocalAddress}")
        remote.handlerRef ! PeersNetworkMessage(peers)
    }
  }

  def additionalMessages: Receive = {
    case RequestPeersForFirstSyncInfo =>
      logger.info(s"Peers keeper got request for peers for first sync info. Starting scheduler for this logic.")
      context.system.scheduler.schedule(1.seconds, settings.network.syncInterval)(sendSyncInfo())

    case OtherNodeSyncingStatus(remote, comparison, _) => connectedPeers.updatePeerComparisonStatus(remote, comparison)

    case AccumulatedPeersStatistic(statistic) => connectedPeers.updatePeersPriorityStatus(statistic)

    case SendToNetwork(message, strategy) =>
      val peers: Seq[ConnectedPeer] = connectedPeers.getPeersF((_, _) => true, getConnectedPeersF).toSeq
      strategy.choose(peers).foreach { peer =>
        logger.info(s"Sending message: ${message.messageName} to: ${peer.socketAddress}.")
        peer.handlerRef ! message
      }

    case SendLocalSyncInfo => sendSyncInfo()

    case PeerFromCli(peer) =>
      if (!blackList.contains(peer.getAddress) && !availablePeers.contains(peer) && connectedPeers.contains(peer)) {
        outgoingConnections += peer
        sender() ! PeerForConnection(peer)
      }

    case msg => logger.info(s"Peers keeper got unhandled message: $msg.")
  }

  def banPeersLogic: Receive = {
    case BanPeer(peer, reason) =>
      logger.info(s"Banning peer: ${peer.socketAddress} for $reason.")
      blackList.banPeer(reason, peer.socketAddress.getAddress)
      peer.handlerRef ! CloseConnection
  }

  def isLocal(address: InetSocketAddress): Boolean = address == settings.network.bindAddress ||
    InetAddress.getLocalHost.getAddress.sameElements(address.getAddress.getAddress) ||
    InetAddress.getLoopbackAddress.getAddress.sameElements(address.getAddress.getAddress) ||
    settings.network.declaredAddress.contains(address)

  def sendSyncInfo(): Unit = {
    val peers: Seq[ConnectedPeer] = connectedPeers.getPeersF(findPeersForSyncInfoP, findPeersForSyncInfoF).toSeq
    if (peers.nonEmpty) nodeViewSync ! PeersForSyncInfo(peers)
  }

  def findPeersForSyncInfoP(add: InetSocketAddress, info: PeerInfo): Boolean =
    (System.currentTimeMillis() - info.lastUptime.time) > settings.network.syncInterval.toMillis

  def findPeersForSyncInfoF(add: InetSocketAddress, info: PeerInfo): ConnectedPeer = {
    connectedPeers.updateUptime(add)
    info.connectedPeer
  }

  def getConnectedPeersF(add: InetSocketAddress, info: PeerInfo): ConnectedPeer = info.connectedPeer

  def getPeersAndInfoF(add: InetSocketAddress, info: PeerInfo): (InetSocketAddress, PeerInfo) = add -> info

  def getPeersForRemoteF(add: InetSocketAddress, info: PeerInfo): InetSocketAddress = add

  def getPeersForDMF(address: InetSocketAddress, info: PeerInfo): (InetSocketAddress, (ConnectedPeer, HistoryComparisonResult, PeersPriorityStatus)) =
    address -> (info.connectedPeer, info.historyComparisonResult, info.peerPriorityStatus)
}

object PeersKeeper {

  final case class VerifyConnection(peer: InetSocketAddress,
                                    remoteConnection: ActorRef)

  final case class ConnectionVerified(peer: InetSocketAddress,
                                      remoteConnection: ActorRef,
                                      ct: ConnectionType)

  final case class OutgoingConnectionFailed(peer: InetSocketAddress)

  final case class HandshakedDone(peer: ConnectedPeer)

  final case class ConnectionStopped(peer: InetSocketAddress)

  case object RequestPeerForConnection

  final case class PeerForConnection(peer: InetSocketAddress)

  final case class SendToNetwork(message: NetworkMessage,
                                 sendingStrategy: SendingStrategy)

  case object RequestPeersForFirstSyncInfo

  final case class PeersForSyncInfo(peers: Seq[ConnectedPeer])

  final case class UpdatedPeersCollection(peers: Map[InetSocketAddress, (ConnectedPeer, HistoryComparisonResult, PeersPriorityStatus)])


  final case class BanPeer(peer: ConnectedPeer, reason: BanReason)


  case object GetKnownPeers

  case object GetInfoAboutConnectedPeers

  def props(settings: EncryAppSettings,
            nodeViewSync: ActorRef,
            dataHolder: ActorRef): Props = Props(new PeersKeeper(settings, nodeViewSync, dataHolder))

  class PeersKeeperPriorityQueue(settings: ActorSystem.Settings, config: Config)
    extends UnboundedStablePriorityMailbox(
      PriorityGenerator {
        case OtherNodeSyncingStatus(_, _, _) => 0
        case AccumulatedPeersStatistic(_) => 1
        case BanPeer(_, _) => 1
        case VerifyConnection(_, _) => 2
        case HandshakedDone(_) => 2
        case ConnectionStopped(_) => 2
        case OutgoingConnectionFailed(_) => 2
        case PoisonPill => 4
        case otherwise => 3
      })

}