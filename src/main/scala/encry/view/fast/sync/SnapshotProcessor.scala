package encry.view.fast.sync

import java.io.File

import SnapshotChunkProto.SnapshotChunkMessage
import encry.storage.VersionalStorage.{StorageKey, StorageValue, StorageVersion}
import encry.view.state.UtxoState
import org.encryfoundation.common.modifiers.history.Block
import com.typesafe.scalalogging.StrictLogging
import encry.settings.{EncryAppSettings, LevelDBSettings}
import encry.storage.VersionalStorage
import encry.storage.iodb.versionalIODB.IODBWrapper
import encry.storage.levelDb.versionalLevelDB.{LevelDbFactory, VLDBWrapper, VersionalLevelDBCompanion}
import encry.view.fast.sync.SnapshotHolder.{SnapshotChunk, SnapshotChunkSerializer, SnapshotManifest, SnapshotManifestSerializer}
import encry.view.state.avlTree.{AvlTree, InternalNode, LeafNode, Node, NodeSerilalizer, ShadowNode}
import org.encryfoundation.common.utils.Algos
import scorex.utils.Random
import encry.view.state.avlTree.utils.implicits.Instances._
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import org.iq80.leveldb.{DB, Options}
import scorex.crypto.hash.Digest32
import scala.language.postfixOps
import cats.syntax.either._
import com.google.common.primitives.Ints
import encry.view.fast.sync.ChunkValidator.ChunkValidationError
import encry.view.fast.sync.SnapshotProcessor.{ChunkApplyError, EmptyHeightKey, EmptyRootNodeError, ProcessNewBlockError, ProcessNewSnapshotError, UtxoCreationError}
import encry.view.history.History
import org.encryfoundation.common.utils.TaggedTypes.Height
import org.encryfoundation.common.utils.constants.Constants
import scala.collection.immutable.HashSet
import scala.util.{Failure, Success, Try}

final case class SnapshotProcessor(settings: EncryAppSettings, storage: VersionalStorage)
    extends StrictLogging
    with SnapshotProcessorStorageAPI
    with AutoCloseable {

  var applicableChunks: HashSet[ByteArrayWrapper] = HashSet.empty[ByteArrayWrapper]

  def setRSnapshotData(rootNodeId: Array[Byte], utxoHeight: Height): SnapshotProcessor = {
    storage.insert(
      StorageVersion @@ Random.randomBytes(),
      AvlTree.rootNodeKey -> StorageValue @@ rootNodeId ::
        UtxoState.bestHeightKey -> StorageValue @@ Ints.toByteArray(utxoHeight) :: Nil
    )
    this
  }

  private def flatten(node: Node[StorageKey, StorageValue]): List[Node[StorageKey, StorageValue]] = node match {
    case shadowNode: ShadowNode[StorageKey, StorageValue] => shadowNode :: Nil
    case leaf: LeafNode[StorageKey, StorageValue] => leaf :: Nil
    case internalNode: InternalNode[StorageKey, StorageValue] =>
      internalNode ::
        internalNode.leftChild.map(flatten).getOrElse(List.empty[Node[StorageKey, StorageValue]]) :::
        internalNode.rightChild.map(flatten).getOrElse(List.empty[Node[StorageKey, StorageValue]])
  }

  def applyChunk(chunk: SnapshotChunk)
                (vSerializer: Serializer[StorageValue]): Either[ChunkApplyError, SnapshotProcessor] = {
    val kSerializer: Serializer[StorageKey] = implicitly[Serializer[StorageKey]]
    val nodes: List[Node[StorageKey, StorageValue]] = flatten(chunk.node)
    val toApplicable = nodes.collect{case node: ShadowNode[StorageKey, StorageValue] => node}
    val toStorage = nodes.collect{
      case leaf: LeafNode[StorageKey, StorageValue] => leaf
      case internal: InternalNode[StorageKey, StorageValue] => internal
    }
    val nodesToInsert: List[(StorageKey, StorageValue)] = toStorage.flatMap { node =>
      val fullData: (StorageKey, StorageValue) =
        StorageKey @@ Algos.hash(kSerializer.toBytes(node.key).reverse) -> StorageValue @@ vSerializer.toBytes(node.value)
      val shadowData: (StorageKey, StorageValue) =
        StorageKey @@ node.hash -> StorageValue @@ NodeSerilalizer.toBytes(ShadowNode.childsToShadowNode(node)) //todo probably probably probably
        fullData :: shadowData :: Nil
    }
    Try {
      storage.insert(StorageVersion @@ Random.randomBytes(), nodesToInsert, List.empty)
    } match {
      case Success(_) =>
        applicableChunks =
          (applicableChunks -- toStorage.map(node => ByteArrayWrapper(node.hash))) ++
            toApplicable.map(node => ByteArrayWrapper(node.hash))
        this.asRight[ChunkApplyError]
      case Failure(exception) => ChunkApplyError(exception.getMessage).asLeft[SnapshotProcessor]
    }
  }

  def validateChunk(chunk: SnapshotChunk): Either[ChunkValidationError, SnapshotChunk] = for {
      _ <- ChunkValidator.checkForIdConsistent(chunk)
      _ <- ChunkValidator.checkForApplicableChunk(chunk, applicableChunks)
    } yield chunk

  def getUtxo: Either[UtxoCreationError, UtxoState] =
    for {
      rootNode <- getRootNode
      height <- getHeight
      avlTree = new AvlTree[StorageKey, StorageValue](rootNode, storage)
    } yield UtxoState(avlTree, height, settings.constants)

  private def getHeight: Either[EmptyHeightKey, Height] =
    storage.get(UtxoState.bestHeightKey)
      .fold(EmptyHeightKey("bestHeightKey is empty").asLeft[Height])(bytes => (Height @@ Ints.fromByteArray(bytes)).asRight[EmptyHeightKey])

  private def getRootNodeId: Either[EmptyRootNodeError, StorageKey] =
    storage.get(AvlTree.rootNodeKey) match {
      case Some(id) => (StorageKey !@@ id).asRight[EmptyRootNodeError]
      case None => EmptyRootNodeError("Key root node doesn't exist!").asLeft[StorageKey]
    }

  private def getRootNode: Either[EmptyRootNodeError, Node[StorageKey, StorageValue]] = for {
    rootNodeId <- getRootNodeId
    node <- storage.get(rootNodeId).fold(
      EmptyRootNodeError(s"Node with id ${Algos.encode(rootNodeId)} doesn't exist")
        .asLeft[Node[StorageKey, StorageValue]]
    )(nodeBytes =>
      NodeSerilalizer.fromBytes[StorageKey, StorageValue](nodeBytes).asRight[EmptyRootNodeError]
    )
  } yield node

  def processNewSnapshot(state: UtxoState, block: Block): Either[ProcessNewSnapshotError, SnapshotProcessor] = {
    val potentialManifestId: Digest32 = Algos.hash(state.tree.rootHash ++ block.id)
    logger.info(
      s"Started processing new snapshot with block ${block.encodedId} at height ${block.header.height}" +
        s" and manifest id ${Algos.encode(potentialManifestId)}."
    )
    val manifestIds: Seq[Array[Byte]] = potentialManifestsIds
    if (!potentialManifestsIds.exists(_.sameElements(potentialManifestId))) {
      logger.info(s"Need to create new best potential snapshot.")
      createNewSnapshot(state, block, manifestIds)
    } else {
      logger.info(s"This snapshot already exists.")
      this.asRight[ProcessNewSnapshotError]
    }
  }

  def processNewBlock(block: Block, history: History): Either[ProcessNewBlockError, SnapshotProcessor] = {
    val condition: Int =
      (block.header.height - settings.levelDB.maxVersions) % settings.snapshotSettings.newSnapshotCreationHeight
    logger.info(s"condition = $condition")
    if (condition == 0) {
      logger.info(s"Start updating actual manifest to new one at height " +
        s"${block.header.height} with block id ${block.encodedId}.")
      updateActualSnapshot(history, block.header.height - settings.levelDB.maxVersions)
    } else {
      logger.info(s"Doesn't need to update actual manifest.")
      this.asRight[ProcessNewBlockError]
    }
  }

  def getChunkById(chunkId: Array[Byte]): Option[SnapshotChunkMessage] =
    storage.get(StorageKey @@ chunkId).flatMap(e => Try(SnapshotChunkMessage.parseFrom(e)).toOption)

  private def createNewSnapshot(
    state: UtxoState,
    block: Block,
    manifestIds: Seq[Array[Byte]]
  ): Either[ProcessNewSnapshotError, SnapshotProcessor] = {
    //todo add only exists chunks
    val newChunks: List[SnapshotChunk] =
      AvlTree.getChunks(state.tree.rootNode, currentChunkHeight = 1, state.tree.storage)
    val manifest: SnapshotManifest =
        SnapshotManifest(
          Algos.hash(state.tree.rootHash ++ block.id),
          NodeSerilalizer.toProto(newChunks.head.node), newChunks.map(_.id)
        )
    val snapshotToDB: List[(StorageKey, StorageValue)] = newChunks.map { elem =>
      val bytes: Array[Byte] = SnapshotChunkSerializer.toProto(elem).toByteArray
      StorageKey @@ elem.id -> StorageValue @@ bytes
    }
    val manifestToDB: (StorageKey, StorageValue) =
      StorageKey @@ manifest.manifestId -> StorageValue @@ SnapshotManifestSerializer
        .toProto(manifest)
        .toByteArray
    val updateList: (StorageKey, StorageValue) =
      PotentialManifestsIdsKey -> StorageValue @@ (manifest.manifestId :: manifestIds.toList).flatten.toArray
    val toApply: List[(StorageKey, StorageValue)] = manifestToDB :: updateList :: snapshotToDB
    logger.info(s"A new snapshot created successfully. Insertion started.")
    Either.fromTry(Try(storage.insert(StorageVersion @@ Random.randomBytes(), toApply, List.empty))) match {
      case Left(value) =>
        logger.info(value.getMessage)
        ProcessNewSnapshotError(value.getMessage).asLeft[SnapshotProcessor]
      case Right(_) =>
        this.asRight[ProcessNewSnapshotError]
    }
  }

  private def updateActualSnapshot(history: History, height: Int): Either[ProcessNewBlockError, SnapshotProcessor] =
    Either.fromOption(
      history.getBestHeaderAtHeight(height).map { header =>
        val id: Digest32 = Algos.hash(header.stateRoot ++ header.id)
        logger.info(
          s"Block id at height $height is ${header.encodedId}. State root is ${Algos.encode(header.stateRoot)}" +
            s" Expected manifest id is ${Algos.encode(id)}")
        id
      },
      ProcessNewBlockError(s"There is no best header at height $height")
    ) match {
      case Left(error) =>
        logger.info(error.toString)
        error.asLeft[SnapshotProcessor]
      case Right(bestManifestId) =>
        logger.info(s"Expected manifest id at height $height is ${Algos.encode(bestManifestId)}")
        val manifestIdsToRemove: Seq[Array[Byte]]    = potentialManifestsIds.filterNot(_.sameElements(bestManifestId))
        val manifestsToRemove: Seq[SnapshotManifest] = manifestIdsToRemove.flatMap(l => manifestById(StorageKey @@ l))
        val chunksToRemove: Set[ByteArrayWrapper] =
          manifestsToRemove.flatMap(_.chunksKeys.map(ByteArrayWrapper(_))).toSet
        val newActualManifest: Option[SnapshotManifest] = manifestById(StorageKey @@ bestManifestId)
        val excludedIds: Set[ByteArrayWrapper] =
          newActualManifest.toList.flatMap(_.chunksKeys.map(ByteArrayWrapper(_))).toSet
        val resultedChunksToRemove: List[Array[Byte]] = chunksToRemove.diff(excludedIds).map(_.data).toList
        val toDelete: List[Array[Byte]] =
          PotentialManifestsIdsKey :: manifestIdsToRemove.toList ::: resultedChunksToRemove
        val toApply: (StorageKey, StorageValue) = ActualManifestKey -> StorageValue @@ bestManifestId
        Either.fromTry(
          Try(storage.insert(StorageVersion @@ Random.randomBytes(), List(toApply), toDelete.map(StorageKey @@ _)))
        ) match {
          case Left(error) =>
            logger.info(error.getMessage)
            ProcessNewBlockError(error.getMessage).asLeft[SnapshotProcessor]
          case Right(_) =>
            this.asRight[ProcessNewBlockError]
        }
    }

  override def close(): Unit = storage.close()
}

object SnapshotProcessor extends StrictLogging {

  sealed trait SnapshotProcessorError
  final case class ProcessNewSnapshotError(str: String) extends SnapshotProcessorError
  final case class ProcessNewBlockError(str: String)    extends SnapshotProcessorError

  final case class ChunkApplyError(msg: String)

  sealed trait UtxoCreationError
  final case class EmptyRootNodeError(msg: String) extends UtxoCreationError
  final case class EmptyHeightKey(msg: String) extends UtxoCreationError

  def initialize(settings: EncryAppSettings): SnapshotProcessor =
    create(settings, getDir(settings))

  def getDir(settings: EncryAppSettings): File = new File(s"${settings.directory}/snapshots")

  def create(settings: EncryAppSettings, snapshotsDir: File): SnapshotProcessor = {
    snapshotsDir.mkdirs()
    val storage: VersionalStorage = settings.storage.snapshotHolder match {
      case VersionalStorage.IODB =>
        logger.info("Init snapshots holder with iodb storage")
        IODBWrapper(new LSMStore(snapshotsDir, keepVersions = settings.constants.DefaultKeepVersions))
      case VersionalStorage.LevelDB =>
        logger.info("Init snapshots holder with levelDB storage")
        val levelDBInit: DB = LevelDbFactory.factory.open(snapshotsDir, new Options)
        VLDBWrapper(VersionalLevelDBCompanion(levelDBInit, LevelDBSettings(300), keySize = 32))
    }
    new SnapshotProcessor(settings, storage)
  }
}
