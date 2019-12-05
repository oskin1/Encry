package encry.view.fast.sync

import SnapshotChunkProto.SnapshotChunkMessage
import SnapshotManifestProto.SnapshotManifestProtoMessage
import akka.actor.{ Actor, ActorRef, Cancellable, Props }
import cats.syntax.either._
import cats.syntax.option._
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.StrictLogging
import encry.network.BlackList.BanReason._
import encry.network.BlackList.BanReason._
import encry.network.Broadcast
import encry.network.NetworkController.ReceivableMessages.{ DataFromPeer, RegisterMessagesHandler }
import encry.network.NodeViewSynchronizer.ReceivableMessages.{ ChangedHistory, SemanticallySuccessfulModifier }
import encry.network.PeersKeeper.{ BanPeer, SendToNetwork }
import encry.network.{ Broadcast, PeerConnectionHandler }
import encry.settings.EncryAppSettings
import encry.storage.VersionalStorage.{ StorageKey, StorageValue }
import encry.view.fast.sync.FastSyncExceptions.{ ApplicableChunkIsAbsent, FastSyncException }
import encry.view.fast.sync.SnapshotHolder._
import encry.view.history.History
import encry.storage.VersionalStorage.{ StorageKey, StorageValue }
import encry.view.fast.sync.FastSyncExceptions.{ ApplicableChunkIsAbsent, FastSyncException, UnexpectedChunkMessage }
import encry.view.fast.sync.SnapshotHolder._
import encry.view.history.History
import encry.view.state.UtxoState
import encry.view.state.avlTree.{ Node, NodeSerilalizer }
import org.encryfoundation.common.modifiers.history.Block
import org.encryfoundation.common.network.BasicMessagesRepo._
import org.encryfoundation.common.utils.Algos

import scala.util.Try

class SnapshotHolder(settings: EncryAppSettings,
                     networkController: ActorRef,
                     nodeViewHolder: ActorRef,
                     nodeViewSynchronizer: ActorRef)
    extends Actor
    with StrictLogging {

  import context.dispatcher

  //todo 1. Add connection agreement (case while peer reconnects with other handler.ref)

  var snapshotProcessor: SnapshotProcessor                   = SnapshotProcessor.initialize(settings)
  var snapshotDownloadController: SnapshotDownloadController = SnapshotDownloadController.empty(settings)
  var connectionsHandler: IncomingConnectionsHandler         = IncomingConnectionsHandler.empty(settings)

  override def preStart(): Unit = {
    if (settings.snapshotSettings.newSnapshotCreationHeight <= settings.levelDB.maxVersions ||
        (!settings.snapshotSettings.enableFastSynchronization && !settings.snapshotSettings.enableSnapshotCreation)) {
      logger.info(s"Stop self(~_~)SnapshotHolder(~_~)")
      context.stop(self)
    }
    context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier])
    logger.info(s"SnapshotHolder started.")
    networkController ! RegisterMessagesHandler(
      Seq(
        RequestManifestMessage.NetworkMessageTypeID  -> "RequestManifest",
        ResponseManifestMessage.NetworkMessageTypeID -> "ResponseManifestMessage",
        RequestChunkMessage.NetworkMessageTypeID     -> "RequestChunkMessage",
        ResponseChunkMessage.NetworkMessageTypeID    -> "ResponseChunkMessage"
      ),
      self
    )
  }

  override def receive: Receive = awaitingHistory

  def awaitingHistory: Receive = {
    case ChangedHistory(history) =>
      if (settings.snapshotSettings.enableFastSynchronization && !history.isBestBlockDefined &&
          !settings.node.offlineGeneration) {
        logger.info(s"Start in fast sync regime")
        context.become(fastSyncMod(history, none, reRequestsNumber = 0).orElse(commonMessages))
      } else {
        logger.info(s"Start in snapshot processing regime")
        context.system.scheduler
          .scheduleOnce(settings.snapshotSettings.updateRequestsPerTime)(self ! DropProcessedCount)
        context.become(workMod(history).orElse(commonMessages))
      }
    case nonsense => logger.info(s"Snapshot holder got $nonsense while history awaiting")
  }

  def fastSyncMod(
    history: History,
    responseTimeout: Option[Cancellable],
    reRequestsNumber: Int
  ): Receive = {
    case DataFromPeer(message, remote) =>
      logger.debug(s"Snapshot holder got from ${remote.socketAddress} message ${message.NetworkMessageTypeID}.")
      message match {
        case ResponseManifestMessage(manifest) =>
          logger.info(s"Got new manifest message ${Algos.encode(manifest.manifestId.toByteArray)} while processing chunks.")
        case ResponseChunkMessage(chunk) if snapshotDownloadController.canChunkBeProcessed(remote) =>
          (for {
            controllerAndChunk  <- snapshotDownloadController.processRequestedChunk(chunk, remote)
            (controller, chunk) = controllerAndChunk
            validChunk          <- snapshotProcessor.validateChunkId(chunk)
            processor           = snapshotProcessor.updateCache(validChunk)
            newProcessor <- processor.processNextApplicableChunk(processor).leftFlatMap {
                             case e: ApplicableChunkIsAbsent => e.processor.asRight[FastSyncException]
                             case t                          => t.asLeft[SnapshotProcessor]
                           }
          } yield (newProcessor, controller)) match {
            case Left(err: UnexpectedChunkMessage) =>
              logger.info(s"Error during received chunk processing has occurred: ${err.error}")
            case Left(error) =>
              nodeViewSynchronizer ! BanPeer(remote, InvalidChunkMessage(error.error))
              restartFastSync(history)
            case Right((processor, controller))
                if controller.requestedChunks.isEmpty && !controller.isNotYetRequestedNonEmpty && processor.chunksCache.nonEmpty =>
              nodeViewSynchronizer ! BanPeer(remote, InvalidChunkMessage("For request is empty, buffer is nonEmpty"))
              restartFastSync(history)
            case Right((processor, controller))
                if controller.requestedChunks.isEmpty && !controller.isNotYetRequestedNonEmpty =>
              processor.assembleUTXOState match {
                case Right(state) =>
                  logger.info(s"Tree is valid on Snapshot holder!")
                  (nodeViewHolder ! FastSyncFinished(state)).asRight[FastSyncException]
                case _ =>
                  nodeViewSynchronizer ! BanPeer(remote, InvalidStateAfterFastSync("State after fast sync is invalid"))
                  restartFastSync(history).asLeft[Unit]
              }
            case Right((processor, controller)) =>
              snapshotDownloadController = controller
              snapshotProcessor = processor
              if (snapshotDownloadController.requestedChunks.isEmpty) self ! RequestNextChunks
          }

        case ResponseChunkMessage(_) =>
          logger.info(s"Received chunk from unexpected peer ${remote.socketAddress}")
        //todo add ban only after several unrequested chunks

        case _ =>
      }

    case RequestNextChunks =>
      responseTimeout.foreach(_.cancel())
      (for {
        size             <- snapshotDownloadController.currentNonRequestedBatchesSize
        _                = logger.info(s"Current notYetRequested queue $size.")
        controllerAndIds <- snapshotDownloadController.chunksIdsToDownload
      } yield controllerAndIds) match {
        case Left(err) =>
          logger.info(s"Error has occurred: ${err.error}")

        case Right(controllerAndIds) =>
          snapshotDownloadController = controllerAndIds._1
          controllerAndIds._2.foreach { msg =>
            snapshotDownloadController.cp.foreach { peer: PeerConnectionHandler.ConnectedPeer =>
              peer.handlerRef ! msg
            }
          }
          val timer: Option[Cancellable] =
            context.system.scheduler.scheduleOnce(settings.snapshotSettings.responseTimeout)(self ! CheckDelivery).some
          context.become(
            fastSyncMod(history, processHeaderSyncedMsg, timer, reRequestsNumber = 0).orElse(commonMessages)
          )
      }

    case RequiredManifestHeightAndId(height, manifestId) =>
      logger.info(
        s"Snapshot holder while header sync got message RequiredManifestHeight with height $height." +
          s"New required manifest id is ${Algos.encode(manifestId)}."
      )
      snapshotDownloadController =
        snapshotDownloadController.copy(requiredManifestHeight = height, requiredManifestId = manifestId)
      restartFastSync(history)
      self ! BroadcastManifestRequestMessage
      context.become(awaitManifestMod(none, history).orElse(commonMessages))

    case CheckDelivery if reRequestsNumber < settings.snapshotSettings.reRequestAttempts =>
      snapshotDownloadController.requestedChunks.map { id =>
        RequestChunkMessage(id.data)
      }.foreach { msg =>
        snapshotDownloadController.cp.foreach(peer => peer.handlerRef ! msg)
      }
      val timer: Option[Cancellable] =
        context.system.scheduler.scheduleOnce(settings.snapshotSettings.responseTimeout)(self ! CheckDelivery).some
      context.become(fastSyncMod(history, timer, reRequestsNumber + 1).orElse(commonMessages))

    case CheckDelivery =>
      snapshotDownloadController.cp.foreach { peer =>
        logger.info(s"Ban peer ${peer.socketAddress} for ExpiredNumberOfReRequestAttempts.")
        nodeViewSynchronizer ! BanPeer(peer, ExpiredNumberOfReRequestAttempts)
        restartFastSync(history)
      }

    case FastSyncDone =>
      if (settings.snapshotSettings.enableSnapshotCreation) {
        snapshotProcessor = SnapshotProcessor.recreate(settings)
        logger.info(s"Snapshot holder context.become to snapshot processing")
        context.system.scheduler
          .scheduleOnce(settings.snapshotSettings.updateRequestsPerTime)(self ! DropProcessedCount)
        context.become(workMod(history).orElse(commonMessages))
      } else {
        logger.info(s"Stop processing snapshots")
        context.stop(self)
      }
  }

  def awaitManifestMod(scheduler: Option[Cancellable], history: History): Receive = {
    case BroadcastManifestRequestMessage =>
      logger.info(
        s"Snapshot holder got HeaderChainIsSynced. Broadcasts request for new manifest with id " +
          s"${Algos.encode(snapshotDownloadController.requiredManifestId)}"
      )
      nodeViewSynchronizer ! SendToNetwork(RequestManifestMessage(snapshotDownloadController.requiredManifestId),
                                           Broadcast)
      val newScheduler = context.system.scheduler.scheduleOnce(settings.snapshotSettings.manifestReAskTimeout) {
        logger.info(s"Trigger scheduler for re-request manifest")
        self ! BroadcastManifestRequestMessage
      }
      logger.info(s"Start awaiting manifest network message.")
      context.become(awaitManifestMod(newScheduler.some, history).orElse(commonMessages))

    case DataFromPeer(message, remote) =>
      message match {
        case ResponseManifestMessage(manifest) =>
          val isValidManifest: Boolean =
            snapshotDownloadController.checkManifestValidity(manifest.manifestId.toByteArray, history)
          val canBeProcessed: Boolean = snapshotDownloadController.canNewManifestBeProcessed
          if (isValidManifest && canBeProcessed) {
            (for {
              controller <- snapshotDownloadController.processManifest(manifest, remote, history)
              processor  <- snapshotProcessor.initializeApplicableChunksCache(
                history,
                snapshotDownloadController.requiredManifestHeight
              )
            } yield (controller, processor)) match {
              case Left(error) =>
                nodeViewSynchronizer ! BanPeer(remote, InvalidResponseManifestMessage(error.error))
              case Right((controller, processor)) =>
                logger.debug(s"Request manifest message successfully processed.")
                scheduler.foreach(_.cancel())
                snapshotDownloadController = controller
                snapshotProcessor = processor
                self ! RequestNextChunks
                logger.debug("Manifest processed")
                context.become(fastSyncMod(history, none, 0))
            }
          } else if (!isValidManifest) {
            logger.info(s"Got manifest with invalid id ${Algos.encode(manifest.manifestId.toByteArray)}")
            nodeViewSynchronizer ! BanPeer(
              remote,
              InvalidResponseManifestMessage(s"Invalid manifest id ${Algos.encode(manifest.manifestId.toByteArray)}")
            )
          } else logger.info(s"Doesn't need to process new manifest.")
        case _ =>
      }

    case msg @ RequiredManifestHeightAndId(_, _) =>
      self ! msg
      scheduler.foreach(_.cancel())
      logger.info(s"Got RequiredManifestHeightAndId while awaitManifestMod")
      context.become(fastSyncMod(history, none, 0))
  }

  def workMod(history: History): Receive = {
    case TreeChunks(chunks, id) =>
      //todo add collection with potentialManifestsIds to NVH
      val manifestIds: Seq[Array[Byte]] = snapshotProcessor.potentialManifestsIds
      if (!manifestIds.exists(_.sameElements(id))) {
        snapshotProcessor.createNewSnapshot(id, manifestIds, chunks)
      } else logger.info(s"Doesn't need to create snapshot")

    case SemanticallySuccessfulModifier(block: Block) if history.isFullChainSynced =>
      logger.info(s"Snapshot holder got semantically successful modifier message. Started processing it.")
      val condition: Int =
        (block.header.height - settings.levelDB.maxVersions) % settings.snapshotSettings.newSnapshotCreationHeight
      logger.info(s"condition = $condition")
      if (condition == 0) snapshotProcessor.processNewBlock(block, history) match {
        case Left(_) =>
        case Right(newProcessor) =>
          snapshotProcessor = newProcessor
          connectionsHandler = IncomingConnectionsHandler.empty(settings)
      }

    case DataFromPeer(message, remote) =>
      message match {
        case RequestManifestMessage(requiredManifestId)
            if connectionsHandler.canBeProcessed(snapshotProcessor, remote, requiredManifestId) =>
          snapshotProcessor.actualManifest.foreach { m =>
            logger.info(s"Sent to remote actual manifest with id ${Algos.encode(requiredManifestId)}")
            remote.handlerRef ! ResponseManifestMessage(SnapshotManifestSerializer.toProto(m))
            connectionsHandler = connectionsHandler.addNewConnect(remote, m.chunksKeys.size)
          }
        case RequestManifestMessage(manifest) =>
          logger.debug(s"Got request for manifest with ${Algos.encode(manifest)}")
        case RequestChunkMessage(chunkId) if connectionsHandler.canProcessResponse(remote) =>
          logger.debug(s"Got RequestChunkMessage. Current handledRequests ${connectionsHandler.handledRequests}.")
          val chunkFromDB: Option[SnapshotChunkMessage] = snapshotProcessor.getChunkById(chunkId)
          chunkFromDB.foreach { chunk =>
            logger.debug(s"Sent tp $remote chunk $chunk.")
            val networkMessage: NetworkMessage = ResponseChunkMessage(chunk)
            remote.handlerRef ! networkMessage
          }
          connectionsHandler = connectionsHandler.processRequest(remote)
        case RequestChunkMessage(_) if connectionsHandler.liveConnections.exists {
              case (peer, (lastRequests, _)) => peer.socketAddress == remote.socketAddress && lastRequests <= 0
            } =>
          logger.info(s"Ban peer $remote.")
          nodeViewSynchronizer ! BanPeer(remote, ExpiredNumberOfRequests)
          connectionsHandler = connectionsHandler.removeConnection(remote)
        case RequestChunkMessage(_) =>
        case _                      =>
      }
    case DropProcessedCount =>
      connectionsHandler = connectionsHandler.iterationProcessing
      context.system.scheduler.scheduleOnce(settings.snapshotSettings.updateRequestsPerTime)(self ! DropProcessedCount)
  }

  def commonMessages: Receive = {
    case HeaderChainIsSynced               =>
    case SemanticallySuccessfulModifier(_) =>
    case nonsense                          => logger.info(s"Snapshot holder got strange message $nonsense.")
  }

  def restartFastSync(history: History): Unit = {
    logger.info(s"Restart fast sync!")
    val newController: SnapshotDownloadController = SnapshotDownloadController
      .empty(settings)
      .copy(
        requiredManifestHeight = snapshotDownloadController.requiredManifestHeight,
        requiredManifestId = snapshotDownloadController.requiredManifestId
      )
    snapshotDownloadController = newController
    snapshotProcessor = snapshotProcessor.reInitStorage
    context.become(fastSyncMod(history, none, reRequestsNumber = 0).orElse(commonMessages))
  }
}

object SnapshotHolder {

  final case object BroadcastManifestRequestMessage

  final case class FastSyncFinished(state: UtxoState) extends AnyVal

  final case class TreeChunks(list: List[SnapshotChunk], id: Array[Byte])

  case object DropProcessedCount

  final case class RequiredManifestHeightAndId(height: Int, manifestId: Array[Byte])

  final case class UpdateSnapshot(bestBlock: Block, state: UtxoState)

  case object FastSyncDone

  case object CheckDelivery

  case object RequestNextChunks

  case object HeaderChainIsSynced

  import encry.view.state.avlTree.utils.implicits.Instances._

  final case class SnapshotManifest(manifestId: Array[Byte], chunksKeys: List[Array[Byte]])

  final case class SnapshotChunk(node: Node[StorageKey, StorageValue], id: Array[Byte])

  object SnapshotManifestSerializer {

    def toProto(manifest: SnapshotManifest): SnapshotManifestProtoMessage =
      SnapshotManifestProtoMessage()
        .withManifestId(ByteString.copyFrom(manifest.manifestId))
        .withChunksIds(manifest.chunksKeys.map(ByteString.copyFrom))

    def fromProto(manifest: SnapshotManifestProtoMessage): Try[SnapshotManifest] = Try(
      SnapshotManifest(
        manifest.manifestId.toByteArray,
        manifest.chunksIds.map(_.toByteArray).toList
      )
    )
  }

  object SnapshotChunkSerializer extends StrictLogging {

    def toProto(chunk: SnapshotChunk): SnapshotChunkMessage =
      SnapshotChunkMessage()
        .withChunk(NodeSerilalizer.toProto(chunk.node))
        .withId(ByteString.copyFrom(chunk.id))

    def fromProto[K, V](chunk: SnapshotChunkMessage): Try[SnapshotChunk] = Try(
      SnapshotChunk(NodeSerilalizer.fromProto(chunk.chunk.get), chunk.id.toByteArray)
    )
  }

  def props(settings: EncryAppSettings,
            networkController: ActorRef,
            nodeViewHolderRef: ActorRef,
            nodeViewSynchronizer: ActorRef): Props = Props(
    new SnapshotHolder(settings, networkController, nodeViewHolderRef, nodeViewSynchronizer)
  )

}
