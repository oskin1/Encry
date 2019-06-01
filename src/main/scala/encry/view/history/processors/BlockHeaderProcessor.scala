package encry.view.history.processors

import com.google.common.primitives.Ints
import com.typesafe.scalalogging.StrictLogging
import encry.EncryApp.forceStopApplication
import encry.consensus.History.ProgressInfo
import encry.consensus._
import encry.modifiers.history._
import encry.settings.EncryAppSettings
import encry.storage.VersionalStorage.{StorageKey, StorageValue}
import encry.storage.levelDb.versionalLevelDB.VersionalLevelDBCompanion.VersionalLevelDbValue
import encry.utils.NetworkTimeProvider
import encry.view.history.storage.HistoryStorage
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.{ADProofs, Block, Header, Payload}
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{Difficulty, Height, ModifierId, ModifierTypeId}
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.encryfoundation.common.validation.{ModifierSemanticValidity, ModifierValidator, ValidationResult}
import scorex.crypto.hash.Digest32
import supertagged.@@

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.HashSet
import scala.util.Try

trait BlockHeaderProcessor extends StrictLogging { //scalastyle:ignore

  protected val settings: EncryAppSettings
  protected val timeProvider: NetworkTimeProvider
  private val difficultyController: PowLinearController.type = PowLinearController
  val powScheme: EquihashPowScheme = EquihashPowScheme(TestNetConstants.n, TestNetConstants.k)
  protected val BestHeaderKey: StorageKey =
    StorageKey @@ Array.fill(TestNetConstants.DigestLength)(Header.modifierTypeId.untag(ModifierTypeId))
  protected val BestBlockKey: StorageKey = StorageKey @@ Array.fill(TestNetConstants.DigestLength)(-1: Byte)
  protected val historyStorage: HistoryStorage
  lazy val blockDownloadProcessor: BlockDownloadProcessor = BlockDownloadProcessor(settings.node)
  private var isHeadersChainSyncedVar: Boolean = false

  /**
    * headersCacheIndexes & headersCache contains headers from (currentBestHeaderHeight - maxRollBackDepth) to
    * headersCacheIndexes contains info about heights & headers ids
    * headersCache contains all header with ids from headersCacheIndexes
    */

  var headersCacheIndexes: Map[Int, Seq[ModifierId]] = Map.empty

  var headersCache: HashSet[Header] = HashSet.empty

  /**
    * Header of best Header chain. Empty if no genesis block is applied yet.
    * Transactions and ADProofs for this Header may be missed, to get block from best full chain (in mode that support
    * it) call bestFullBlockOpt.
    */
  def bestHeaderOpt: Option[Header] = bestHeaderIdOpt.flatMap(typedModifierById[Header])

  def isHeadersChainSynced: Boolean = isHeadersChainSyncedVar

  def modifiersToDownload(howMany: Int, excluding: HashSet[ModifierId]): Seq[(ModifierTypeId, ModifierId)] = {
    @tailrec
    def continuation(height: Height, acc: Seq[(ModifierTypeId, ModifierId)]): Seq[(ModifierTypeId, ModifierId)] =
      if (acc.lengthCompare(howMany) >= 0) acc
      else {
        headerIdsAtHeight(height).headOption
          .flatMap(id => headersCache.find(_.id sameElements id).orElse(typedModifierById[Header](id))) match {
          case Some(bestHeaderAtThisHeight) =>
            logger.debug(s"requiredModifiersForHeader($bestHeaderAtThisHeight) ->" +
              s"${requiredModifiersForHeader(bestHeaderAtThisHeight).map(x => Algos.encode(x._2))}")
            val toDownload = requiredModifiersForHeader(bestHeaderAtThisHeight)

            val b = toDownload.filter(m => !excluding.exists(_ sameElements m._2))

            val c = b.filter(m => !contains(m._2)) //todo can we combine this 2 filter?

            continuation(Height @@ (height + 1), acc ++ c)
          case None => acc
        }
      }

    logger.debug(s"BEST BLOCK OPT ${bestBlockOpt.map(x => Algos.encode(x.id))}")
    bestBlockOpt match {
      case _ if !isHeadersChainSynced =>
        Seq.empty
      case Some(fb) =>
        continuation(Height @@ (fb.header.height + 1), Seq.empty)
      case None =>
        continuation(Height @@ blockDownloadProcessor.minimalBlockHeightVar, Seq.empty)
    }
  }

  def modifiersToDownloadForNVH(howMany: Int): Seq[(ModifierTypeId, ModifierId)] = {
    @tailrec
    def continuation(height: Height, acc: Seq[(ModifierTypeId, ModifierId)]): Seq[(ModifierTypeId, ModifierId)] =
      if (acc.lengthCompare(howMany) >= 0) acc
      else {
        headerIdsAtHeight(height).headOption.flatMap(id =>
          headersCache.find(_.id sameElements id).orElse(typedModifierById[Header](id))) match {
          case Some(bestHeaderAtThisHeight) =>
            val toDownload = requiredModifiersForHeader(bestHeaderAtThisHeight)
              .filter(m => !contains(m._2))
            continuation(Height @@ (height + 1), acc ++ toDownload)
          case None => acc
        }
      }

    bestBlockOpt match {
      case _ if !isHeadersChainSynced => Seq.empty
      case Some(fb) => continuation(Height @@ (fb.header.height + 1), Seq.empty)
      case None => continuation(Height @@ blockDownloadProcessor.minimalBlockHeightVar, Seq.empty)
    }
  }

  /** Checks, whether it's time to download full chain and return toDownload modifiers */
  protected def toDownload(header: Header): Seq[(ModifierTypeId, ModifierId)] =
    if (!settings.node.verifyTransactions) Seq.empty // Regime that do not download and verify transaction
    else if (header.height >= blockDownloadProcessor.minimalBlockHeight)
      requiredModifiersForHeader(header) // Already synced and header is not too far back. Download required modifiers
    else if (!isHeadersChainSynced && isNewHeader(header)) {
      // Headers chain is synced after this header. Start downloading full blocks
      logger.info(s"Headers chain is synced after header ${header.encodedId} at height ${header.height}")
      isHeadersChainSyncedVar = true
      blockDownloadProcessor.updateBestBlock(header)
      Seq.empty
    } else Seq.empty

  private def requiredModifiersForHeader(h: Header): Seq[(ModifierTypeId, ModifierId)] =
    if (!settings.node.verifyTransactions) Seq.empty
    else if (settings.node.stateMode.isDigest)
      Seq((Payload.modifierTypeId, h.payloadId), (ADProofs.modifierTypeId, h.adProofsId))
    else Seq((Payload.modifierTypeId, h.payloadId))

  private def isNewHeader(header: Header): Boolean =
    timeProvider.estimatedTime - header.timestamp <
      TestNetConstants.DesiredBlockInterval.toMillis * TestNetConstants.NewHeaderTimeMultiplier

  def typedModifierById[T <: PersistentModifier](id: ModifierId): Option[T]

  def realDifficulty(h: Header): Difficulty = Difficulty !@@ powScheme.realDifficulty(h)

  protected def bestHeaderIdOpt: Option[ModifierId] = historyStorage.get(BestHeaderKey).map(ModifierId @@ _)

  def isSemanticallyValid(modifierId: ModifierId): ModifierSemanticValidity

  private def heightIdsKey(height: Int): StorageKey = StorageKey @@ Algos.hash(Ints.toByteArray(height)).untag(Digest32)

  protected def headerScoreKey(id: ModifierId): StorageKey =
    StorageKey @@ Algos.hash("score".getBytes(Algos.charset) ++ id).untag(Digest32)

  protected def headerHeightKey(id: ModifierId): StorageKey =
    StorageKey @@ Algos.hash("height".getBytes(Algos.charset) ++ id).untag(Digest32)

  protected def validityKey(id: Array[Byte]): StorageKey =
    StorageKey @@ Algos.hash("validity".getBytes(Algos.charset) ++ id).untag(Digest32)

  def contains(id: ModifierId): Boolean

  def bestBlockOpt: Option[Block]

  def bestBlockIdOpt: Option[ModifierId]

  def bestHeaderHeight: Int = bestHeaderOpt.map(_.height).getOrElse(TestNetConstants.PreGenesisHeight)

  def bestBlockHeight: Int = bestBlockOpt.map(_.header.height).getOrElse(TestNetConstants.PreGenesisHeight)

  protected def process(h: Header): ProgressInfo[PersistentModifier] = getHeaderInfoUpdate(h) match {
    case Some(dataToUpdate) =>
      historyStorage.bulkInsert(h.id, dataToUpdate._1, Seq(dataToUpdate._2))
      bestHeaderIdOpt match {
        case Some(bestHeaderId) =>
          val toProcess: Seq[Header] =
            if (settings.node.verifyTransactions || !(bestHeaderId sameElements h.id)) Seq.empty else Seq(h)
          ProgressInfo(None, Seq.empty, toProcess, toDownload(h))
        case None =>
          logger.error("Should always have best header after header application")
          forceStopApplication()
      }
    case None => ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)
  }

  private def addHeaderToCacheIfNecessary(h: Header): Unit =
    if (h.height >= bestHeaderHeight - TestNetConstants.MaxRollbackDepth) {
      logger.debug(s"Should add ${Algos.encode(h.id)} to header cache")
      val newHeadersIdsAtHeaderHeight = headersCacheIndexes.getOrElse(h.height, Seq.empty[ModifierId]) :+ h.id
      headersCacheIndexes = headersCacheIndexes + (h.height -> newHeadersIdsAtHeaderHeight)
      headersCache = headersCache + h
      // cleanup cache if necessary
      if (headersCacheIndexes.size > TestNetConstants.MaxRollbackDepth) {
        headersCacheIndexes.get(bestHeaderHeight - TestNetConstants.MaxRollbackDepth).foreach { headersIds =>
          val wrappedIds = headersIds.map(ByteArrayWrapper.apply)
          logger.debug(s"Cleanup header cache from headers: ${headersIds.map(Algos.encode).mkString(",")}")
          headersCache = headersCache.filterNot(header => wrappedIds.contains(ByteArrayWrapper(header.id)))
        }
        headersCacheIndexes = headersCacheIndexes - (bestHeaderHeight - TestNetConstants.MaxRollbackDepth)
      }
      logger.debug(s"headersCache size: ${headersCache.size}")
      logger.debug(s"headersCacheIndexes size: ${headersCacheIndexes.size}")
    }

  private def getHeaderInfoUpdate(header: Header): Option[(Seq[(StorageKey, StorageValue)], PersistentModifier)] = {
    addHeaderToCacheIfNecessary(header)
    if (header.isGenesis) {
      logger.info(s"Initialize header chain with genesis header ${header.encodedId}")
      Option(Seq(
        BestHeaderKey -> StorageValue @@ header.id.untag(ModifierId),
        heightIdsKey(TestNetConstants.GenesisHeight) -> StorageValue @@ header.id.untag(ModifierId),
        headerHeightKey(header.id) -> StorageValue @@ Ints.toByteArray(TestNetConstants.GenesisHeight),
        headerScoreKey(header.id) -> StorageValue @@ header.difficulty.toByteArray) -> header)
    } else {
      scoreOf(header.parentId).map { parentScore =>
        val score: BigInt @@ Difficulty.Tag =
          Difficulty @@ (parentScore + ConsensusSchemeReaders.consensusScheme.realDifficulty(header))
        val bestRow: Seq[(StorageKey, StorageValue)] =
          if ((header.height > bestHeaderHeight) ||
            (header.height == bestHeaderHeight && score > bestHeadersChainScore)) {
            Seq(BestHeaderKey -> StorageValue @@ header.id.untag(ModifierId))
          } else Seq.empty
        val scoreRow: (StorageKey, StorageValue) =
          headerScoreKey(header.id) -> StorageValue @@ score.toByteArray
        val heightRow: (StorageKey, StorageValue) =
          headerHeightKey(header.id) -> StorageValue @@ Ints.toByteArray(header.height)
        val headerIdsRow: Seq[(StorageKey, StorageValue)] =
          if ((header.height > bestHeaderHeight) ||
            (header.height == bestHeaderHeight && score > bestHeadersChainScore)) {
            val row = bestBlockHeaderIdsRow(header, score)
            row
          }
          else orphanedBlockHeaderIdsRow(header, score)
        (Seq(scoreRow, heightRow) ++ bestRow ++ headerIdsRow, header)
      }
    }
  }


  /** Update header ids to ensure, that this block id and ids of all parent blocks are in the first position of
    * header ids at this height */
  private def bestBlockHeaderIdsRow(h: Header, score: Difficulty): Seq[(StorageKey, StorageValue)] = {
    val prevHeight = bestHeaderHeight
    logger.info(s"New best header ${h.encodedId} with score: $score." +
      s" New height: ${h.height}, old height: $prevHeight")
    val self: (StorageKey, StorageValue) =
      heightIdsKey(h.height) -> StorageValue @@ (Seq(h.id) ++
        headerIdsAtHeight(h.height).filterNot(_ sameElements h.id)
        ).flatten.toArray
    val parentHeaderOpt: Option[Header] =
      headersCache.find(_.id sameElements h.parentId).orElse(typedModifierById[Header](h.parentId))
    val forkHeaders: Seq[Header] = parentHeaderOpt.toSeq
      .flatMap(parent => headerChainBack(h.height, parent, h => isInBestChain(h)).headers)
      .filter(h => !isInBestChain(h))
    val forkIds: Seq[(StorageKey, StorageValue)] = forkHeaders.map { header =>
      val otherIds: Seq[ModifierId] = headerIdsAtHeight(header.height).filterNot(_ sameElements header.id)
      heightIdsKey(header.height) -> StorageValue @@ (Seq(header.id) ++ otherIds).flatten.toArray
    }
    forkIds :+ self
  }

  /** Row to storage, that put this orphaned block id to the end of header ids at this height */
  private def orphanedBlockHeaderIdsRow(h: Header, score: Difficulty): Seq[(StorageKey, StorageValue)] = {
    logger.info(s"New orphaned header ${h.encodedId} at height ${h.height} with score $score")
    Seq(heightIdsKey(h.height) -> StorageValue @@ (headerIdsAtHeight(h.height) :+ h.id).flatten.toArray)
  }

  protected def validate(header: Header): Try[Unit] = HeaderValidator.validate(header).toTry

  def isInBestChain(id: ModifierId): Boolean = heightOf(id).flatMap(h => bestHeaderIdAtHeight(h))
    .exists(_ sameElements id)

  def isInBestChain(h: Header): Boolean = bestHeaderIdAtHeight(h.height).exists(_ sameElements h.id)

  private def bestHeaderIdAtHeight(h: Int): Option[ModifierId] = headerIdsAtHeight(h).headOption

  private def bestHeadersChainScore: BigInt = scoreOf(bestHeaderIdOpt.get).get

  protected def scoreOf(id: ModifierId): Option[BigInt] = historyStorage.get(headerScoreKey(id)).map(d => BigInt(d))

  def heightOf(id: ModifierId): Option[Height] = historyStorage.get(headerHeightKey(id))
    .map(d => Height @@ Ints.fromByteArray(d))

  /**
    * @param height - block height
    * @return ids of headers on chosen height.
    *         Seq.empty we don't have any headers on this height
    *         single id if no forks on this height
    *         multiple ids if there are forks at chosen height.
    *         First id is always from the best headers chain.
    */
  def headerIdsAtHeight(height: Int): Seq[ModifierId] =
    headersCacheIndexes.getOrElse(height, historyStorage.store
      .get(heightIdsKey(height))
      .map(elem => elem.untag(VersionalLevelDbValue).grouped(32).map(ModifierId @@ _).toSeq)
      .getOrElse(Seq.empty[ModifierId]))

  /**
    * @param limit       - maximum length of resulting HeaderChain
    * @param startHeader - header to start
    * @param until       - stop condition
    * @return at most limit header back in history starting from startHeader and when condition until is not satisfied
    *         Note now it includes one header satisfying until condition!
    */
  protected def headerChainBack(limit: Int, startHeader: Header, until: Header => Boolean): HeaderChain = {
    @tailrec
    def loop(header: Header, acc: Seq[Header]): Seq[Header] = {
      if (acc.length == limit || until(header)) acc
      else headersCache.find(_.id sameElements header.parentId).orElse(typedModifierById[Header](header.parentId)) match {
        case Some(parent: Header) => loop(parent, acc :+ parent)
        case None if acc.contains(header) => acc
        case _ => acc :+ header
      }
    }

    if (bestHeaderIdOpt.isEmpty || (limit == 0)) HeaderChain(Seq())
    else HeaderChain(loop(startHeader, Seq(startHeader)).reverse)
  }

  /**
    * Find first header with the best height <= $height which id satisfies condition $p
    *
    * @param height - start height
    * @param p      - condition to satisfy
    * @return found header
    */
  @tailrec
  protected final def loopHeightDown(height: Int, p: ModifierId => Boolean): Option[Header] = {
    headerIdsAtHeight(height).find(id => p(id))
      .flatMap(id => headersCache.find(_.id sameElements id).orElse(typedModifierById[Header](id))) match {
      case Some(header) => Some(header)
      case None if height > TestNetConstants.GenesisHeight => loopHeightDown(height - 1, p)
      case None => None
    }
  }

  def requiredDifficultyAfter(parent: Header): Difficulty = {
    val parentHeight: Int = parent.height
    val requiredHeights: Seq[Height] =
      difficultyController.getHeightsForRetargetingAt(Height @@ (parentHeight + 1))
        .ensuring(_.last == parentHeight, "Incorrect heights sequence!")
    val chain: HeaderChain = headerChainBack(requiredHeights.max - requiredHeights.min + 1, parent, (_: Header) => false)
    val requiredHeaders: immutable.IndexedSeq[(Int, Header)] = (requiredHeights.min to requiredHeights.max)
      .zip(chain.headers).filter(p => requiredHeights.contains(p._1))
    assert(requiredHeights.length == requiredHeaders.length,
      s"Missed headers: $requiredHeights != ${requiredHeaders.map(_._1)}")
    difficultyController.getDifficulty(requiredHeaders)
  }

  object HeaderValidator extends ModifierValidator {

    def validate(header: Header): ValidationResult =
      if (header.isGenesis) validateGenesisBlockHeader(header)
      else headersCache.find(_.id sameElements header.parentId).orElse(typedModifierById[Header](header.parentId)).map { parent =>
        validateChildBlockHeader(header, parent)
      } getOrElse error(s"Parent header with id ${Algos.encode(header.parentId)} is not defined")

    private def validateGenesisBlockHeader(header: Header): ValidationResult =
      accumulateErrors
        .validateEqualIds(header.parentId, Header.GenesisParentId) { detail =>
          fatal(s"Genesis block should have genesis parent id. $detail")
        }
        .validate(bestHeaderIdOpt.isEmpty) {
          fatal("Trying to append genesis block to non-empty history")
        }
        .validate(header.height == TestNetConstants.GenesisHeight) {
          fatal(s"Height of genesis block $header is incorrect")
        }
        .result

    private def validateChildBlockHeader(header: Header, parent: Header): ValidationResult = {
      failFast
        .validate(header.timestamp - timeProvider.estimatedTime <= TestNetConstants.MaxTimeDrift) {
          error(s"Header timestamp ${header.timestamp} is too far in future from now " +
            s"${timeProvider.estimatedTime}")
        }
        .validate(header.timestamp > parent.timestamp) {
          fatal(s"Header timestamp ${header.timestamp} is not greater than parents ${parent.timestamp}")
        }
        .validate(header.height == parent.height + 1) {
          fatal(s"Header height ${header.height} is not greater by 1 than parents ${parent.height}")
        }
        .validateNot(historyStorage.containsObject(header.id)) {
          fatal("Header is already in history")
        }
        .validate(realDifficulty(header) >= header.requiredDifficulty) {
          fatal(s"Block difficulty ${realDifficulty(header)} is less than required " +
            s"${header.requiredDifficulty}")
        }
        .validate(header.difficulty >= requiredDifficultyAfter(parent)) {
          fatal(s"Incorrect required difficulty in header: " +
            s"${Algos.encode(header.id)} on height ${header.height}")
        }
        .validate(heightOf(header.parentId).exists(h => bestHeaderHeight - h < TestNetConstants.MaxRollbackDepth)) {
          fatal(s"Trying to apply too old block difficulty at height ${heightOf(header.parentId)}")
        }
        .validate(powScheme.verify(header)) {
          fatal(s"Wrong proof-of-work solution for $header")
        }
        .validateSemantics(isSemanticallyValid(header.parentId)) {
          fatal("Parent header is marked as semantically invalid")
        }.result
    }
  }
}