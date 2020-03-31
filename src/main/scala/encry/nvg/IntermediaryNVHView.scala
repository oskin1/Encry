package encry.nvg

import akka.actor.{Actor, ActorRef, Props, Stash}
import akka.pattern._
import com.typesafe.scalalogging.StrictLogging
import encry.api.http.DataHolderForApi.BlockAndHeaderInfo
import encry.local.miner.Miner.CandidateEnvelope
import encry.network.Messages.MessageToNetwork.RequestFromLocal
import encry.nvg.IntermediaryNVHView.IntermediaryNVHViewActions.{RegisterNodeView, RegisterState}
import encry.nvg.IntermediaryNVHView.{ModifierToAppend, NodeViewStarted}
import encry.nvg.ModifiersValidator.ValidatedModifier
import encry.nvg.NVHHistory.{ModifierAppliedToHistory, NewWalletReader, ProgressInfoForState}
import encry.nvg.NVHState.StateAction
import encry.nvg.NodeViewHolder.ReceivableMessages.LocallyGeneratedModifier
import encry.nvg.NodeViewHolder.{GetDataFromCurrentView, SemanticallyFailedModification, SemanticallySuccessfulModifier, SyntacticallyFailedModification}
import encry.settings.EncryAppSettings
import encry.stats.StatsSender.StatsSenderMessage
import encry.utils.NetworkTimeProvider
import encry.view.NodeViewHolder.CurrentView
import encry.view.history.HistoryReader
import encry.view.state.UtxoStateReader
import encry.view.wallet.WalletReader
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.Block
import org.encryfoundation.common.utils.Algos

import scala.concurrent.Future

class IntermediaryNVHView(settings: EncryAppSettings, ntp: NetworkTimeProvider, influx: Option[ActorRef])
    extends Actor
    with StrictLogging
    with Stash {

  import context.dispatcher

  var historyReader: HistoryReader = HistoryReader.empty

  var walletReader: WalletReader = WalletReader.empty

  val historyRef: ActorRef = context.actorOf(NVHHistory.props(ntp, settings))

  var isModifierProcessingInProgress: Boolean = false

  var toApply = Set.empty[ByteArrayWrapper]

  override def receive: Receive = awaitingViewActors()

  def awaitingViewActors(history: Option[ActorRef] = None,
                         state: Option[ActorRef] = None,
                         stateReader: Option[UtxoStateReader] = None): Receive = {
    case RegisterNodeView(reader, wallet) if state.isEmpty =>
      walletReader = wallet
      historyReader = reader
      logger.info(s"NodeViewParent actor got init history. Going to init state actor.")
      context.become(awaitingViewActors(Some(sender()), state), discardOld = true)
      context.actorOf(NVHState.restoreProps(settings, reader, influx))
    case RegisterNodeView(reader, wallet) =>
      walletReader = wallet
      historyReader = reader
      context.become(viewReceive(sender(), state.get, stateReader.get))
    case RegisterState(reader) =>
      context.become(viewReceive(history.get, sender(), reader), discardOld = true)
      context.system.eventStream.publish(new NodeViewStarted {})
    case RegisterNodeView =>
      context.become(viewReceive(history.get, sender(), stateReader.get))
    case msg => println(s"Receive strange: $msg on inter nvh from ${sender()}")
  }

  def viewReceive(history: ActorRef, state: ActorRef, stateReader: UtxoStateReader): Receive = {

    case RegisterState(reader) => context.become(viewReceive(history, sender(), reader))
    case reader: UtxoStateReader =>
      logger.info("Update reader at inter nvh view")
      context.become(viewReceive(history, state, reader))

    case GetDataFromCurrentView(f: (CurrentView[HistoryReader, UtxoStateReader, WalletReader] => CandidateEnvelope)) =>
      logger.info("Receive GetDataFromCurrentView on nvh")
      f(CurrentView(historyReader, stateReader, walletReader)) match {
        case res: Future[_] =>
          res.pipeTo(sender)
        case res: CandidateEnvelope =>
          sender ! res
        case res =>
          sender ! res
      }
//      f(CurrentView(historyReader, stateReader, walletReader)) match {
//        case resultFuture: Future[_] => resultFuture.pipeTo(sender)
//        case result                  => sender ! result
//      }

    case LocallyGeneratedModifier(modifier: Block) =>
      logger.info(s"Self mined block: ${modifier}")
      ModifiersCache.put(
        NodeViewHolder.toKey(modifier.id),
        modifier.header,
        historyReader,
        settings,
        isLocallyGenerated = true
      )
      ModifiersCache.put(
        NodeViewHolder.toKey(modifier.payload.id),
        modifier.payload,
        historyReader,
        settings,
        isLocallyGenerated = true
      )
      if (!isModifierProcessingInProgress) getNextModifier()

    case ValidatedModifier(modifier: PersistentModifier) =>
      logger.info(s"Receive modifier (${Algos.encode(modifier.id)}) at inter nvh view")
      val isInHistory: Boolean = historyReader.isModifierDefined(modifier.id)
      val isInCache: Boolean   = ModifiersCache.contains(NodeViewHolder.toKey(modifier.id))
      if (isInHistory || isInCache)
        logger.info(
          s"Modifier ${modifier.encodedId} can't be placed into the cache cause: " +
            s"contains in cache: $isInCache, contains in history: $isInHistory."
        )
      else
        ModifiersCache.put(
          NodeViewHolder.toKey(modifier.id),
          modifier,
          historyReader,
          settings,
          isLocallyGenerated = false
        )
      logger.info(s"isModifierProcessingInProgress: ${isModifierProcessingInProgress}")
      if (!isModifierProcessingInProgress) getNextModifier()
    case ModifierAppliedToHistory => isModifierProcessingInProgress = false; getNextModifier()
    case msg: ProgressInfoForState
        if msg.pi.chainSwitchingNeeded && msg.pi.branchPoint.exists(
          point => !stateReader.version.sameElements(point)
        ) =>
      context.become(
        viewReceive(
          history,
          state,
          stateReader
        )
      )
    case msg: ProgressInfoForState =>
      toApply = msg.pi.toApply.map(mod => ByteArrayWrapper(mod.id)).toSet
      msg.pi.toApply.foreach(mod => state ! StateAction.ApplyModifier(mod, msg.saveRootNodeFlag, msg.isFullChainSynced))
    case msg: StateAction.ApplyFailed => historyRef ! msg
    case NewWalletReader(reader)      => walletReader = reader
    case msg: StateAction.ModifierApplied =>
      historyRef ! msg
    case msg: SyntacticallyFailedModification => context.parent ! msg
    case msg: StatsSenderMessage              => context.parent ! msg
    case msg: RequestFromLocal                => context.parent ! msg
    case msg: SemanticallyFailedModification  => context.parent ! msg
    case msg: SemanticallySuccessfulModifier  => context.parent ! msg
    case msg: BlockAndHeaderInfo              => context.parent ! msg
    case msg: HistoryReader                   => historyReader = msg; context.parent ! msg
  }

  def awaitingHistoryBranchPoint(history: ActorRef): Receive = ???

  def getNextModifier(): Unit =
    ModifiersCache
      .popCandidate(historyReader, settings)
      .foreach {
        case (mod: PersistentModifier, isLocallyGenerated) =>
          isModifierProcessingInProgress = true
          logger.info(s"Got new modifiers in compute application function: ${mod.encodedId}.")
          historyRef ! ModifierToAppend(mod, isLocallyGenerated)
      }

}

object IntermediaryNVHView {

  sealed trait IntermediaryNVHViewActions
  object IntermediaryNVHViewActions {
    case class RegisterNodeView(historyReader: HistoryReader, walletReader: WalletReader) extends IntermediaryNVHViewActions
    case class RegisterState(stateReader: UtxoStateReader)   extends IntermediaryNVHViewActions
  }

  final case class ModifierToAppend(modifier: PersistentModifier, isLocallyGenerated: Boolean)
  case object InitGenesisHistory
  trait NodeViewStarted

  def props(settings: EncryAppSettings, ntp: NetworkTimeProvider, influxRef: Option[ActorRef]): Props =
    Props(new IntermediaryNVHView(settings, ntp, influxRef))
}