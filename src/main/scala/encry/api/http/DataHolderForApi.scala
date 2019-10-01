package encry.api.http

import java.net.{InetAddress, InetSocketAddress}
import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.scalalogging.StrictLogging
import encry.EncryApp
import encry.EncryApp._
import encry.api.http.DataHolderForApi._
import akka.pattern._
import akka.util.Timeout
import encry.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, ChangedState, NodeViewChange, PeerFromCli, RemovePeerFromBlackList}
import encry.settings.EncryAppSettings
import encry.utils.NetworkTimeProvider
import encry.view.state.{UtxoState, UtxoStateReader}
import encry.local.miner.Miner.{DisableMining, EnableMining, MinerStatus, StartMining}
import encry.network.BlackList.{BanReason, BanTime, BanType}
import encry.network.PeerConnectionHandler.ConnectedPeer
import encry.view.actors.NodeViewHolder.CurrentView
import encry.view.actors.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import encry.view.history.History
import org.encryfoundation.common.modifiers.history.{Block, Header}

class DataHolderForApi(settings: EncryAppSettings,
                       ntp: NetworkTimeProvider) extends Actor with StrictLogging {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[NodeViewChange])
  }

  override def receive: Receive = workingCycle()

  def workingCycle(blackList: Seq[(InetAddress, (BanReason, BanTime, BanType))] = Seq.empty,
                   connectedPeers: Seq[ConnectedPeer] = Seq.empty,
                   history: Option[History] = None,
                   state: Option[UtxoStateReader] = None,
                   transactionsOnMinerActor: Int = 0,
                   minerStatus: MinerStatus = MinerStatus(isMining = false, None),
                   blockInfo: BlockAndHeaderInfo = BlockAndHeaderInfo(None, None),
                   allPeers: Seq[InetSocketAddress] = Seq.empty): Receive = {
    case UpdatingTransactionsNumberForApi(qty) =>
      context.become(workingCycle(blackList, connectedPeers, history, state, qty, minerStatus, blockInfo, allPeers))

    case BlockAndHeaderInfo(header, block) =>
      context.become(workingCycle(
      blackList,
      connectedPeers,
      history,
      state,
      transactionsOnMinerActor,
      minerStatus,
      BlockAndHeaderInfo(header, block),
      allPeers))

    case ChangedHistory(reader: History) =>
      context.become(workingCycle(
        blackList,
        connectedPeers,
        Some(reader),
        state,
        transactionsOnMinerActor,
        minerStatus,
        blockInfo,
        allPeers))

    case ChangedState(reader: UtxoStateReader)  =>
      context.become(workingCycle(
        blackList, connectedPeers, history, Some(reader), transactionsOnMinerActor, minerStatus, blockInfo, allPeers)
      )

    case UpdatingMinerStatus(status) =>
      context.become(workingCycle(blackList, connectedPeers, history, state, transactionsOnMinerActor, status, blockInfo, allPeers)
      )

    case UpdatingPeersInfo(allP, connectedP, bannedP) =>
      context.become(workingCycle(
        bannedP, connectedP, history, state, transactionsOnMinerActor, minerStatus, blockInfo, allP)
      )

    case PeerAdd(peer)                => context.system.eventStream.publish(PeerFromCli(peer))
    case RemovePeerFromBanList(peer)  => context.system.eventStream.publish(RemovePeerFromBlackList(peer))

    case GetConnectedPeers      => sender() ! connectedPeers
    case GetDataFromHistory     => history.foreach(sender() ! _)
    case GetMinerStatus         => sender() ! minerStatus
    case GetReaders             => sender() ! Readers(history, state)
    case GetTransactionsNumber  => sender() ! transactionsOnMinerActor
    case GetBlockInfo           => sender() ! blockInfo
    case GetAllPeers            => sender() ! allPeers
    case GetBannedPeers         => sender() ! blackList
    case StartMiner             => context.system.eventStream.publish(EnableMining)
                                   context.system.eventStream.publish(StartMining)
    case StopMiner              => context.system.eventStream.publish(DisableMining)
    case ShutdownNode           => EncryApp.forceStopApplication(errorMessage = "Stopped by cli command")
      //
    case GetDataFromPresentView(f) =>
      implicit val timeout: Timeout = Timeout(settings.restApi.timeout)
      (nodeViewHolder ? GetDataFromCurrentView(f)).pipeTo(sender)

    case GetAllInfo =>
      sender() ! (
        connectedPeers,
        minerStatus,
        Readers(history, state),
        transactionsOnMinerActor,
        blockInfo,
        allPeers
      )
    case _ =>

  }
}

object DataHolderForApi {

  final case class UpdatingBlackListForApi(blackList: Seq[InetAddress]) extends AnyVal

  final case class UpdatingConnectedListForApi(connectedPeers: Seq[InetSocketAddress]) extends AnyVal

  final case class UpdatingTransactionsNumberForApi(number: Int) extends AnyVal

  final case class UpdatingMinerStatus(minerStatus: MinerStatus) extends AnyVal

  final case class UpdatingPeersInfo(allPeers: Seq[InetSocketAddress],
                                     connectedPeers: Seq[ConnectedPeer],
                                     blackList: Seq[(InetAddress, (BanReason, BanTime, BanType))])

  final case class BlockAndHeaderInfo(header: Option[Header], block: Option[Block])

  final case class RemovePeerFromBanList(peer: InetSocketAddress)

  final case class PeerAdd(peer: InetSocketAddress)

  final case class GetDataFromPresentView[HIS, MS, VL, A](f: CurrentView[HIS, MS, VL] => A)

  case object StartMiner

  case object StopMiner

  case object ShutdownNode

  case object GetTransactionsNumber

  case object GetReaders

  case object GetConnectedPeers

  case object GetDataFromHistory

  case object GetMinerStatus

  case object GetAllPeers

  case object GetBlockInfo

  case object GetBannedPeers

  case object GetAllInfo

  final case class Readers(h: Option[History], s: Option[UtxoStateReader])

  def props(settings: EncryAppSettings,
            ntp: NetworkTimeProvider): Props = Props(new DataHolderForApi(settings, ntp))
}