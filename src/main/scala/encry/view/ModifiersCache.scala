package encry.view

import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import encry.settings.EncryAppSettings
import encry.view.ModifiersCache._
import encry.view.history.EncryHistory
import encry.view.history.processors.ValidationError.{FatalValidationError, NonFatalValidationError}
import org.encryfoundation.common.modifiers.PersistentModifier
import org.encryfoundation.common.modifiers.history.Header
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.common.utils.constants.TestNetConstants
import scala.collection.immutable.SortedMap
import scala.collection.mutable


final case class ModifiersCache(modifiersCache: Map[Key, PersistentModifier],
                                headersCache: SortedMap[Int, List[ModifierId]],
                                settings: EncryAppSettings) extends StrictLogging {

  val size: Int = modifiersCache.size

  private var isChainSynced = false

  def setChainSynced(): Unit = isChainSynced = true

  def contains(key: Key): Boolean = modifiersCache.contains(key)

  def put(modifier: PersistentModifier, history: EncryHistory): ModifiersCache =
    if (!contains(key(modifier.id))) {
      logger.debug(s"Modifier of type ${modifier.modifierTypeId} with id ${modifier.encodedId} placed into modifiersCache.")
      val updatedCache: Map[Key, PersistentModifier] = modifiersCache.updated(key(modifier.id), modifier)
      val updatedHeadersCache: SortedMap[Int, List[ModifierId]] = modifier match {
        case header: Header =>
          headersCache
            .updated(header.height, header.id :: headersCache.getOrElse(header.height, List.empty[ModifierId]))
        case _ => headersCache
      }
      val filteredModifiersCache: Map[Key, PersistentModifier] =
        if (updatedCache.size > settings.node.modifiersCacheSize)
          updatedCache.find(elem => isModifiersUnApplicable(history, elem._2)) match {
            case Some(value) => updatedCache - value._1
            case None => updatedCache
          }
        else updatedCache
      ModifiersCache(filteredModifiersCache, updatedHeadersCache, settings)
    } else this

  private def getHeadersIdsAtHeight(height: Int, h: EncryHistory): (Map[Key, PersistentModifier], List[Key]) =
    headersCache.get(height) match {
      case Some(headersIds) => headersIds
        .view
        .map(key)
        .foldLeft(modifiersCache, List.empty[Key]) { case ((updatedCache, applicable), modifierId) => modifiersCache
          .get(modifierId)
          .map(kds => h.testApplicable(kds))
          .map {
            case Left(_: FatalValidationError) => (updatedCache - modifierId, applicable)
            case Left(_) => (updatedCache, applicable)
            case Right(_) => (updatedCache, applicable :+ modifierId)
          }
          .getOrElse((modifiersCache, List.empty[Key]))
        }
      case _ =>
        logger.debug(s"Can't find headers at height $height in cache")
        (modifiersCache, List.empty[Key])
    }

  def remove(key: Key): (Map[Key, PersistentModifier], Option[PersistentModifier]) = {
    logger.debug(s"Going to delete ${Algos.encode(key.toArray)}. Cache contains: ${modifiersCache.get(key).isDefined}.")
    val modCache = modifiersCache - key
    (modCache, modifiersCache.get(key))
  }

  def popCandidate(history: EncryHistory): (List[PersistentModifier], ModifiersCache) = {
    val a: (List[Key], ModifiersCache) = findCandidateKey(history)
    val b: (Map[Key, PersistentModifier], List[PersistentModifier]) =
      a._1.foldLeft(modifiersCache, List.empty[PersistentModifier]) { case ((cache, modifiers), modsKey) =>
        cache.get(modsKey) match {
          case Some(value) => (cache - modsKey) -> (modifiers :+ value)
          case None => (cache - modsKey) -> modifiers
        }
      }
    b._2 -> ModifiersCache(b._1, headersCache, settings)
  }

  def findCandidateKey(history: EncryHistory): (List[Key], ModifiersCache) = {

    def isApplicable(modifierId: Key): Boolean = modifiersCache.get(modifierId).exists(mod => history.testApplicable(mod) match {
      case Left(_: FatalValidationError) => false
      case Right(_) => true
      case Left(_) => false
    })

    def exhaustiveSearch: List[Key] = modifiersCache
      .find { case (key, value) =>
        value match {
          case _: Header if history.bestHeaderOpt.exists(header => header.id.sameElements(value.parentId)) => true
          case _ =>
            val isApplicableMod: Boolean = isApplicable(key)
            logger.debug(s"Try to apply: ${Algos.encode(key.toArray)} and result is: $isApplicableMod")
            isApplicableMod
        }
      }
      .toList
      .collect { case (k, _) => k }

    val bestHeadersIds: (List[Key], ModifiersCache) = headersCache
      .get(history.bestHeaderHeight + 1) match {
      case Some(elems) =>
        val updatedHeadersCollection: SortedMap[Int, List[ModifierId]] =
          headersCache - (history.bestHeaderHeight + 1)
        logger.debug(s"HeadersCollection size is: ${headersCache.size}")
        logger.debug(s"Drop height ${history.bestHeaderHeight + 1} in HeadersCollection")
        val resultedIds: (Map[Key, PersistentModifier], List[Key]) = elems
          .map(k => modifiersCache.get(key(k)))
           .foldLeft(modifiersCache, List.empty[Key]) { case ((mCache, keys), modifier) =>
             modifier match {
               case Some(value) if
               ((value.parentId sameElements history.bestHeaderOpt.map(_.id).getOrElse(Array.emptyByteArray)) ||
                 (history.bestHeaderHeight == TestNetConstants.PreGenesisHeight &&
                   (value.parentId sameElements Header.GenesisParentId)) ||
                 history.modifierById(value.parentId).nonEmpty) && isApplicable(key(modifier.get.id)) =>
                 val newModCache = if (!isApplicable(key(value.id))) remove(key(value.id))._1
                 else modifiersCache
             newModCache -> (keys :+ key(value.id))
               case _ => mCache -> keys
          }
    }
        val updatedCache: Map[Key, PersistentModifier] =
          resultedIds._1 -- elems.map(id => key(id)).filterNot(resultedIds._2.contains)
        (resultedIds._2, ModifiersCache(updatedCache, updatedHeadersCollection, settings))
      case None =>
        logger.debug(s"No header in cache at height ${history.bestHeaderHeight + 1}. " +
          s"Trying to find in range [${history.bestHeaderHeight - TestNetConstants.MaxRollbackDepth}," +
          s" ${history.bestHeaderHeight}]")
        val modifiers: List[Key] =
          (history.bestHeaderHeight - TestNetConstants.MaxRollbackDepth to history.bestHeaderHeight)
            .flatMap(height => getHeadersIdsAtHeight(height, history)._2)
            .toList
        (modifiers, ModifiersCache(modifiersCache, headersCache, settings))
    }

    if (bestHeadersIds._1.nonEmpty) (bestHeadersIds._1, bestHeadersIds._2)
    else history
      .headerIdsAtHeight(history.bestBlockHeight + 1)
      .headOption match {
      case Some(id) => history.modifierById(id) match {
        case Some(header: Header) if isApplicable(key(header.payloadId)) =>
          (List(key(header.payloadId)), bestHeadersIds._2)
        case _ => (exhaustiveSearch, bestHeadersIds._2)

      }
      case None if isChainSynced =>
        logger.debug(s"No payloads for current history")
        (exhaustiveSearch, bestHeadersIds._2)
      case None => logger.debug(s"No payloads for current history")
        (List.empty[Key], this)
    }
  }

  override def toString: String = modifiersCache.keys.map(key => Algos.encode(key.toArray)).mkString(",")

  private def isModifiersUnApplicable(h: EncryHistory, m: PersistentModifier): Boolean = h.testApplicable(m) match {
    case Right(_) | Left(_: NonFatalValidationError) => false
    case _ => true
  }

}


object ModifiersCache {

  type Key = mutable.WrappedArray[Byte]

  def key(id: ModifierId): mutable.WrappedArray.ofByte = new mutable.WrappedArray.ofByte(id)

  def apply(settings: EncryAppSettings): ModifiersCache =
    ModifiersCache(Map.empty[Key, PersistentModifier], SortedMap.empty[Int, List[ModifierId]], settings)

}