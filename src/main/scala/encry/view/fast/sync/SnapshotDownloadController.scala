package encry.view.fast.sync

import java.io.File

import SnapshotChunkProto.SnapshotChunkMessage
import SnapshotManifestProto.SnapshotManifestProtoMessage
import cats.syntax.either._
import cats.syntax.option._
import com.typesafe.scalalogging.StrictLogging
import encry.network.PeerConnectionHandler.ConnectedPeer
import encry.settings.EncryAppSettings
import encry.storage.levelDb.versionalLevelDB.LevelDbFactory
import encry.view.fast.sync.FastSyncExceptions.{
  ChunkValidationError,
  InvalidChunkBytes,
  InvalidManifestBytes,
  SnapshotDownloadControllerException
}
import encry.view.fast.sync.SnapshotHolder.{ SnapshotChunk, SnapshotChunkSerializer, SnapshotManifestSerializer }
import encry.view.history.History
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.common.network.BasicMessagesRepo.RequestChunkMessage
import org.encryfoundation.common.utils.Algos
import org.iq80.leveldb.{ DB, Options }
import cats.syntax.either._
import cats.syntax.option._
import encry.view.fast.sync.FastSyncExceptions.{
  ChunkValidationError,
  InvalidChunkBytes,
  InvalidManifestBytes,
  ProcessManifestHasChangedMessageException,
  SnapshotDownloadControllerException,
  UnexpectedChunkMessage
}
import org.encryfoundation.common.network.BasicMessagesRepo.{ NetworkMessage, RequestChunkMessage }

final case class SnapshotDownloadController(requiredManifestId: Array[Byte],
                                            requestedChunks: Set[ByteArrayWrapper],
                                            settings: EncryAppSettings,
                                            cp: Option[ConnectedPeer],
                                            requiredManifestHeight: Int,
                                            storage: DB)
    extends SnapshotDownloadControllerStorageAPI
    with StrictLogging {

  def processManifest(
    manifestProto: SnapshotManifestProtoMessage,
    remote: ConnectedPeer,
    history: History
  ): Either[SnapshotDownloadControllerException, SnapshotDownloadController] = {
    logger.info(s"Got new manifest from ${remote.socketAddress}.")
    Either.fromTry(SnapshotManifestSerializer.fromProto(manifestProto)) match {
      case Left(error) =>
        logger.info(s"Manifest was parsed with error ${error.getCause}.")
        InvalidManifestBytes(s"Manifest was parsed with error ${error.getCause}")
          .asLeft[SnapshotDownloadController]
      case Right(manifest) =>
        logger.info(s"Manifest ${Algos.encode(manifest.manifestId)} is correct.")
        insertMany(manifest.chunksKeys) match {
          case Left(error) =>
            logger.info(s"Error ${error.error} has occurred while processing new manifest.")
          case Right(_) =>
            logger.info(s"New chunks ids successfully inserted into db")
        }
        SnapshotDownloadController(
          requiredManifestId,
          Set.empty[ByteArrayWrapper],
          settings,
          remote.some,
          requiredManifestHeight,
          storage
        ).asRight[SnapshotDownloadControllerException]
    }
  }

  def processRequestedChunk(
    chunkMessage: SnapshotChunkMessage,
    remote: ConnectedPeer
  ): Either[ChunkValidationError, (SnapshotDownloadController, SnapshotChunk)] = {
    logger.debug(s"Got new chunk from ${remote.socketAddress}.")
    Either.fromTry(SnapshotChunkSerializer.fromProto(chunkMessage)) match {
      case Left(error) =>
        logger.info(s"Chunk was parsed with error ${error.getCause}.")
        InvalidChunkBytes(s"Chunk was parsed with error ${error.getCause}.")
          .asLeft[(SnapshotDownloadController, SnapshotChunk)]
      case Right(chunk) =>
        val chunkId: ByteArrayWrapper = ByteArrayWrapper(chunk.id)
        if (requestedChunks.contains(chunkId)) {
          logger.debug(s"Got valid chunk ${Algos.encode(chunk.id)}.")
          (this.copy(requestedChunks = requestedChunks - chunkId), chunk).asRight[InvalidChunkBytes]
        } else {
          logger.info(s"Got unexpected chunk ${Algos.encode(chunk.id)}.")
          UnexpectedChunkMessage(s"Got unexpected chunk ${Algos.encode(chunk.id)}.")
            .asLeft[(SnapshotDownloadController, SnapshotChunk)]
        }
    }
  }

  def chunksIdsToDownload
    : Either[FastSyncExceptions.FastSyncException, (SnapshotDownloadController, List[RequestChunkMessage])] =
    for {
      nextBatch                                       <- getNextForRequest
      serializedToDownload: List[RequestChunkMessage] = nextBatch.map(id => RequestChunkMessage(id))
    } yield this.copy(requestedChunks = nextBatch.map(ByteArrayWrapper(_)).toSet) -> serializedToDownload

  def isNotYetRequestedNonEmpty: Boolean = isBatchesListNonEmpty match {
    case Left(value) =>
      logger.info(s"Error ${value.error} has occurred")
      false
    case Right(value) => value
  }

  def checkManifestValidity(manifestId: Array[Byte], history: History): Boolean =
    history.getBestHeaderAtHeight { requiredManifestHeight }.exists { header =>
      Algos.hash(header.stateRoot ++ header.id).sameElements(manifestId)
    }

  def canNewManifestBeProcessed: Boolean = cp.isEmpty

  def canChunkBeProcessed(remote: ConnectedPeer): Boolean = cp.exists(_.socketAddress == remote.socketAddress)
}

object SnapshotDownloadController {

  def getChunksStorageDir(settings: EncryAppSettings): File = {
    val dir: File = new File(s"${settings.directory}/chunksStorage")
    dir.mkdirs()
    dir
  }

  def empty(settings: EncryAppSettings): SnapshotDownloadController = {
    val levelDBInit = LevelDbFactory.factory.open(getChunksStorageDir(settings), new Options)
    SnapshotDownloadController(Array.emptyByteArray, Set.empty, settings, none, 0, levelDBInit)
  }
}
