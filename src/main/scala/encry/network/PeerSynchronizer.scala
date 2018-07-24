package encry.network

import java.net.InetSocketAddress
import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import encry.EncryApp._
import encry.network.NetworkController.ReceivableMessages.{DataFromPeer, RegisterMessagesHandler, SendToNetwork}
import encry.network.message.{GetPeersSpec, Message, PeersSpec}
import encry.network.peer.PeerManager._
import encry.network.peer.PeerManager.ReceivableMessages.{AddOrUpdatePeer, RandomPeers}
import encry.utils.Logging
import scala.concurrent.duration._
import scala.language.postfixOps

class PeerSynchronizer extends Actor with Logging {

  implicit val timeout: Timeout = Timeout(settings.network.syncTimeout.getOrElse(5 seconds))

  override def preStart: Unit = {
    super.preStart()
    networkController ! RegisterMessagesHandler(Seq(GetPeersSpec, PeersSpec), self)
    val msg: Message[Unit] = Message[Unit](GetPeersSpec, Right(Unit), None)
    context.system.scheduler.schedule(2.seconds, settings.network.syncInterval)(networkController ! SendToNetwork(msg, SendToRandom))
  }

  override def receive: Receive = {
    case DataFromPeer(spec, peers: Seq[InetSocketAddress]@unchecked, remote)
      if spec.messageCode == PeersSpec.messageCode =>
      peers.filter(checkPossibilityToAddPeer).foreach(isa =>
        peerManager ! AddOrUpdatePeer(isa, None, Some(remote.direction)))
      log.debug(s"Get new peers: [${peers.mkString(",")}] from ${remote.socketAddress}")
    case DataFromPeer(spec, _, remote) if spec.messageCode == GetPeersSpec.messageCode =>
      (peerManager ? RandomPeers(3))
        .mapTo[Seq[InetSocketAddress]]
        .foreach { peer =>
          networkController ! SendToNetwork(Message(PeersSpec, Right(peer), None), SendToPeer(remote))
          log.debug(s"Send to ${remote.socketAddress} peers message which contains next peers: ${peer.mkString(",")}")
        }
    case nonsense: Any => logWarn(s"PeerSynchronizer: got something strange $nonsense")
  }
}