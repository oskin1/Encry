package encry.view

import java.util.concurrent.ConcurrentHashMap

import com.typesafe.scalalogging.StrictLogging
import encry.EncryApp.settings
import encry.modifiers.EncryPersistentModifier
import encry.utils.CoreTaggedTypes.ModifierId
import encry.modifiers.history.Header
import encry.settings.Constants
import encry.validation.{MalformedModifierError, RecoverableModifierError}
import encry.view.history.EncryHistory
import org.encryfoundation.common.Algos
import scala.collection.immutable.SortedMap
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.{Failure, Success}

object ModifiersCache extends StrictLogging {

  private type Key = mutable.WrappedArray[Byte]

  //  private val cache1 =
  val cache: TrieMap[Key, EncryPersistentModifier] = TrieMap[Key, EncryPersistentModifier]()
  private var headersCollection: SortedMap[Int, List[ModifierId]] = SortedMap[Int, List[ModifierId]]()
  private var isChainSynced = false

  def setChainSynced(): Unit = isChainSynced = true

  def size: Int = cache.size

  def isEmpty: Boolean = size == 0

  def contains(key: Key): Boolean = cache.contains(key)

  def put(key: Key, value: EncryPersistentModifier, history: EncryHistory): Unit = if (!contains(key)) {
    logger.info(s"put ${Algos.encode(key.toArray)} to cache")
    cache.put(key, value)
    value match {
      case header: Header =>
        val possibleHeadersAtCurrentHeight: List[ModifierId] = headersCollection.getOrElse(header.height, List())
        val updatedHeadersAtCurrentHeight: List[ModifierId] = header.id :: possibleHeadersAtCurrentHeight
        headersCollection = headersCollection.updated(header.height, updatedHeadersAtCurrentHeight)
      case _ =>
    }
    if (size > settings.node.modifiersCacheSize) cache.find {
      case (_, value) => history.testApplicable(value) match {
        case Success(_) => false
        case Failure(_: RecoverableModifierError) => false
        case _ => true
      }
    }.map(mod => remove(mod._1))
  }

  def remove(key: Key): Option[EncryPersistentModifier] = {
    logger.info(s"Going to delete ${Algos.encode(key.toArray)}. Cache contains: ${cache.get(key).isDefined}.")
    cache.remove(key)
  }

  def popCandidate(history: EncryHistory): List[EncryPersistentModifier] = synchronized {
    findCandidateKey(history).flatMap(k => remove(k))
  }

  override def toString: String = cache.keys.map(key => Algos.encode(key.toArray)).mkString(",")

  def findCandidateKey(history: EncryHistory): List[Key] = {

    def isApplicable(key: Key): Boolean = cache.get(key).exists(modifier => history.testApplicable(modifier) match {
      case Failure(_: RecoverableModifierError) => false
      case Failure(_: MalformedModifierError) =>
        remove(key)
        false
      case Failure(_) => false
      case m => m.isSuccess
    })

    def getHeadersKeysAtHeight(height: Int): List[Key] = {
      headersCollection.get(height) match {
        case Some(headersIds) =>
          headersIds.map(new mutable.WrappedArray.ofByte(_)).collect {
            case headerKey if isApplicable(headerKey) => headerKey
          }
        case None =>
          logger.info(s"Can't find headers at height $height in cache")
          List.empty[Key]
      }
    }

    def exhaustiveSearch: List[Key] = List(cache.find { case (k, v) =>
      v match {
        case _: Header if history.bestHeaderOpt.exists(header => header.id sameElements v.parentId) => true
        case _ =>
          val isApplicableMod: Boolean = isApplicable(k)
          logger.info(s"Try to apply: ${Algos.encode(k.toArray)} and result is: $isApplicableMod")
          isApplicableMod
      }
    }).collect { case Some(v) => v._1 }

    val bestHeadersIds: List[Key] = {
      headersCollection.get(history.bestHeaderHeight + 1) match {
        case Some(value) =>
          headersCollection = headersCollection - (history.bestHeaderHeight + 1)
          logger.info(s"HeadersCollection size is: ${headersCollection.size}")
          value.map(cache.get(_)).collect {
            case Some(v: Header)
              if (
                (v.parentId sameElements history.bestHeaderOpt.map(_.id).getOrElse(Array.emptyByteArray)) ||
                  (history.bestHeaderHeight == Constants.Chain.PreGenesisHeight &&
                    (v.parentId sameElements Header.GenesisParentId)
                    )
                )
                && isApplicable(new mutable.WrappedArray.ofByte(v.id)) =>
              logger.info(s"Find new bestHeader in cache: ${Algos.encode(v.id)}")
              new mutable.WrappedArray.ofByte(v.id)
          }

        case None =>
          logger.info(s"No header in cache at height ${history.bestHeaderHeight + 1}. " +
            s"Trying to find in range [${history.bestHeaderHeight - Constants.Chain.MaxRollbackDepth}, ${history.bestHeaderHeight}]")
          (history.bestHeaderHeight - Constants.Chain.MaxRollbackDepth to history.bestHeaderHeight).flatMap(height =>
            getHeadersKeysAtHeight(height)
          ).toList
      }
    }
    if (bestHeadersIds.nonEmpty) bestHeadersIds
    else history.headerIdsAtHeight(history.bestBlockHeight + 1).headOption match {
      case Some(id) => history.modifierById(id) match {
        case Some(header: Header) if isApplicable(new mutable.WrappedArray.ofByte(header.payloadId)) =>
          List(new mutable.WrappedArray.ofByte(header.payloadId))
        case _ if !isChainSynced =>
          logger.info(s"ModsCache no applicable payload")
          List.empty[Key]
        case _ => exhaustiveSearch
      }
      case None if isChainSynced =>
        logger.info(s"No payloads for current history")
        exhaustiveSearch
      case None => logger.info(s"No payloads for current history")
        List.empty[Key]
    }
  }
}