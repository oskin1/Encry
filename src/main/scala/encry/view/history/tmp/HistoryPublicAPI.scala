package encry.view.history.tmp

import com.typesafe.scalalogging.StrictLogging
import encry.consensus.HistoryConsensus.{ Equal, Fork, HistoryComparisonResult, Older, Unknown, Younger }
import encry.consensus.PowLinearController
import encry.modifiers.history.HeaderChain
import encry.view.history.ValidationError.HistoryApiError
import org.encryfoundation.common.modifiers.history.Header
import org.encryfoundation.common.network.SyncInfo
import org.encryfoundation.common.utils.TaggedTypes.{ Difficulty, Height, ModifierId }

import scala.annotation.tailrec
import scala.collection.immutable

trait HistoryPublicAPI extends HistoryReader with StrictLogging {

  final def lastHeaders(count: Int): HeaderChain =
    getBestHeader
      .map(headerChainBack(count, _, _ => false))
      .getOrElse(HeaderChain.empty)

  final def getHeaderIds(count: Int, offset: Int = 0): List[ModifierId] =
    (offset until (count + offset))
      .flatMap(getBestHeaderIdAtHeight)
      .toList

  protected[history] final def headerChainBack(
    limit: Int,
    startHeader: Header,
    until: Header => Boolean
  ): HeaderChain = {
    @tailrec def loop(
      header: Header,
      acc: List[Header]
    ): List[Header] =
      if (acc.length == limit || until(header)) acc
      else
        getHeaderById(header.parentId) match {
          case Some(parent: Header)         => loop(parent, acc :+ parent)
          case None if acc.contains(header) => acc
          case _                            => acc :+ header
        }

    if (getBestHeaderId.isEmpty || (limit == 0)) HeaderChain(List.empty)
    else HeaderChain(loop(startHeader, List(startHeader)).reverse)
  }

  final def requiredDifficultyAfter(parent: Header): Either[HistoryApiError, Difficulty] = {
    val requiredHeights: IndexedSeq[Height] = PowLinearController
      .getHeightsForRetargetingAt(
        Height @@ (parent.height + 1),
        settings.constants.EpochLength,
        settings.constants.RetargetingEpochsQty
      )
      .toIndexedSeq
    for {
      _ <- Either.cond(
            requiredHeights.lastOption.contains(parent.height),
            (),
            HistoryApiError("Incorrect required heights sequence in requiredDifficultyAfter function.")
          )
      chain: HeaderChain = headerChainBack(requiredHeights.max - requiredHeights.min + 1, parent, (_: Header) => false)
      requiredHeaders: immutable.IndexedSeq[(Int, Header)] = (requiredHeights.min to requiredHeights.max)
        .zip(chain.headers)
        .filter(p => requiredHeights.contains(p._1))
      _ <- Either.cond(
            requiredHeights.length == requiredHeaders.length,
            (),
            HistoryApiError(s"Missed headers: $requiredHeights != ${requiredHeaders.map(_._1)}.")
          )
    } yield
      PowLinearController.getDifficulty(
        requiredHeaders,
        settings.constants.EpochLength,
        settings.constants.DesiredBlockInterval,
        settings.constants.InitialDifficulty
      )
  }

  final def continuationIds(info: SyncInfo, size: Int): List[ModifierId] =
    if (getBestHeaderId.isEmpty) info.startingPoints.map(_._2).toList
    else if (info.lastHeaderIds.isEmpty) {
      val heightFrom: Int = Math.min(getBestHeaderHeight, size - 1)
      (for {
        startId     <- headerIdsAtHeight(heightFrom).headOption
        startHeader <- getHeaderById(startId)
      } yield headerChainBack(size, startHeader, _ => false)) match {
        case Some(value) if value.headers.exists(_.height == settings.constants.GenesisHeight) =>
          value.headers.map(_.id).toList
        case _ => List.empty
      }
    } else {
      val ids: Seq[ModifierId] = info.lastHeaderIds
      (for {
        lastHeaderInOurBestChain <- ids.view.reverse.find(isInBestChain)
        theirHeight              <- heightOf(lastHeaderInOurBestChain)
        heightFrom               = Math.min(getBestHeaderHeight, theirHeight + size)
        startId                  <- getBestHeaderIdAtHeight(heightFrom)
        startHeader              <- getHeaderById(startId)
      } yield
        headerChainBack(size, startHeader, h => h.parentId sameElements lastHeaderInOurBestChain).headers
          .map(_.id)) match {
        case Some(value) => value.toList
        case None        => List.empty
      }
    }

  final def getChainToHeader(
    fromHeaderOpt: Option[Header],
    toHeader: Header
  ): (Option[ModifierId], HeaderChain) =
    fromHeaderOpt match {
      case Some(h1) =>
        val (prevChain, newChain) = commonBlockThenSuffixes(h1, toHeader)
        (prevChain.headOption.map(_.id), newChain.tail)
      case None => (None, headerChainBack(toHeader.height + 1, toHeader, _ => false))
    }

  protected[history] final def commonBlockThenSuffixes(
    header1: Header,
    header2: Header
  ): (HeaderChain, HeaderChain) = {
    val heightDelta: Int = Math.max(header1.height - header2.height, 0)

    @tailrec def loop(
      numberBack: Int,
      otherChain: HeaderChain
    ): (HeaderChain, HeaderChain) = {
      val chains: (HeaderChain, HeaderChain) = commonBlockThenSuffixes(otherChain, header1, numberBack + heightDelta)
      if (chains._1.head == chains._2.head) chains
      else {
        val biggerOther: HeaderChain = headerChainBack(numberBack, otherChain.head, _ => false) ++ otherChain.tail
        if (!otherChain.head.isGenesis) loop(biggerOther.length, biggerOther)
        else throw new Exception(s"Common point not found for headers $header1 and $header2")
      }
    }

    def commonBlockThenSuffixes(
      otherChain: HeaderChain,
      startHeader: Header,
      limit: Int
    ): (HeaderChain, HeaderChain) = {
      def until(h: Header): Boolean = otherChain.exists(_.id sameElements h.id)

      val currentChain: HeaderChain = headerChainBack(limit, startHeader, until)
      (currentChain, otherChain.takeAfter(currentChain.head))
    }

    loop(2, HeaderChain(Seq(header2)))
  }

  def compare(si: SyncInfo): HistoryComparisonResult = getLastSyncInfo.lastHeaderIds.lastOption match {
    //Our best header is the same as other history best header
    case Some(id) if si.lastHeaderIds.lastOption.exists(_ sameElements id) => Equal
    //Our best header is in other history best chain, but not at the last position
    case Some(id) if si.lastHeaderIds.exists(_ sameElements id) => Older
    /* Other history is empty, or our history contains last id from other history */
    case Some(_) if si.lastHeaderIds.isEmpty || si.lastHeaderIds.lastOption.exists(isHeaderDefined) => Younger
    case Some(_)                                                                                    =>
      //Our history contains some ids from other history
      if (si.lastHeaderIds.exists(isHeaderDefined)) Fork
      //Unknown comparison result
      else Unknown
    //Both nodes do not keep any blocks
    case None if si.lastHeaderIds.isEmpty => Equal
    //Our history is empty, other contain some headers
    case None => Older
  }
}
