package encry.view.mempool

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import com.google.common.base.Charsets
import com.google.common.hash.{BloomFilter, Funnels}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import encry.network.NodeViewSynchronizer.ReceivableMessages.SuccessfulTransaction
import encry.network.PeerConnectionHandler.ConnectedPeer
import encry.settings.EncryAppSettings
import encry.stats.StatsSender.MemoryPoolStatistic
import encry.utils.NetworkTimeProvider
import org.encryfoundation.common.modifiers.mempool.transaction.Transaction
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.common.utils.TaggedTypes.{ModifierId, ModifierTypeId}
import encry.view.mempool.MemoryPool._
import scala.collection.IndexedSeq
import scala.concurrent.duration._

class MemoryPool(settings: EncryAppSettings,
                 networkTimeProvider: NetworkTimeProvider,
                 minerReference: ActorRef,
                 influxReference: Option[ActorRef]) extends Actor with StrictLogging {

  import context.dispatcher

  var memoryPool: MemoryPoolStorage = MemoryPoolStorage.empty(settings, networkTimeProvider)

  var bloomFilterForTransactionsIds: BloomFilter[String] = initBloomFilter

  override def preStart(): Unit = {
    logger.debug(s"Starting MemoryPool. Initializing all schedulers...")
    context.system.scheduler.schedule(
      settings.node.bloomFilterCleanupInterval,
      settings.node.bloomFilterCleanupInterval, self, CleanupBloomFilter)
    context.system.scheduler.schedule(
      settings.node.mempoolCleanupInterval,
      settings.node.mempoolCleanupInterval, self, RemoveExpiredFromPool)
    context.system.scheduler.schedule(
      settings.node.mempoolTxSendingInterval,
      settings.node.mempoolTxSendingInterval, self, SendTransactionsToMiner)
    context.system.scheduler.schedule(
      5.seconds, 5.seconds
    )(influxReference.foreach(_ ! MemoryPoolStatistic(memoryPool.size)))
  }

  override def receive: Receive = {
    case NewTransactions(transactions) =>
      val (newMemoryPool: MemoryPoolStorage, validatedTransactions: Seq[Transaction]) =
        memoryPool.validateTransactions(transactions)
      memoryPool = newMemoryPool
      validatedTransactions.foreach(tx => context.system.eventStream.publish(SuccessfulTransaction(tx)))
      logger.info(s"MemoryPool got new transactions from remote. New pool size is ${memoryPool.size}." +
        s"Number of transactions for broadcast is ${validatedTransactions.size}.")

    case RemoveExpiredFromPool =>
      memoryPool = memoryPool.filter(memoryPool.isExpired)
      logger.info(s"MemoryPool got RemoveExpiredFromPool message. After cleaning pool size is: ${memoryPool.size}.")

    case InvMessageWithTransactionsIds(peer, transactions) =>
      val notYetRequestedTransactions: IndexedSeq[ModifierId] = notRequestedYet(transactions)
      if (notYetRequestedTransactions.nonEmpty) {
        sender ! RequestForTransactions(peer, Transaction.modifierTypeId, notYetRequestedTransactions)
        logger.info(s"MemoryPool got inv message with ${transactions.size} ids." +
          s" Not yet requested ids size is ${notYetRequestedTransactions.size}.")
      } else logger.info(s"MemoryPool got inv message with ${transactions.size} ids." +
        s" There are no not yet requested ids.")

    case CleanupBloomFilter =>
      bloomFilterForTransactionsIds = initBloomFilter

    case RolledBackTransactions(transactions) =>
      val (newMemoryPool: MemoryPoolStorage, validatedTransactions: Seq[Transaction]) =
        memoryPool.validateTransactions(transactions)
      memoryPool = newMemoryPool
      logger.info(s"MemoryPool got rolled back transactions. New pool size is ${memoryPool.size}." +
        s"Number of rolled back transactions is ${validatedTransactions.size}.")

    case SendTransactionsToMiner =>
      val (newMemoryPool: MemoryPoolStorage, transactionsForMiner: IndexedSeq[Transaction]) =
        memoryPool.getTransactionsForMiner
      memoryPool = newMemoryPool
      minerReference ! TransactionsForMiner(transactionsForMiner)
      logger.info(s"MemoryPool got SendTransactionsToMiner. Size of transactions for miner ${transactionsForMiner.size}." +
        s" New pool size is ${memoryPool.size}.")

    case RequestModifiersForTransactions(remote, ids) =>
      val modifiersIds: Seq[Transaction] = ids
        .map(Algos.encode)
        .collect { case id if memoryPool.contains(id) => memoryPool.get(id) }
        .flatten
      sender() ! RequestedModifiersForRemote(remote, modifiersIds)
      logger.info(s"MemoryPool got request modifiers message. Number of requested ids is ${ids.size}." +
        s" Number of sent transactions is ${modifiersIds.size}. Request was from $remote.")

    case message => logger.info(s"MemoryPool got unhandled message $message.")
  }

  def initBloomFilter: BloomFilter[String] = BloomFilter.create(
    Funnels.stringFunnel(Charsets.UTF_8), settings.node.bloomFilterCapacity, settings.node.bloomFilterFailureProbability
  )

  def notRequestedYet(ids: IndexedSeq[ModifierId]): IndexedSeq[ModifierId] = ids.collect {
    case id: ModifierId if !bloomFilterForTransactionsIds.mightContain(Algos.encode(id)) =>
      bloomFilterForTransactionsIds.put(Algos.encode(id))
      id
  }
}

object MemoryPool {

  final case class NewTransactions(tx: Seq[Transaction]) extends AnyVal

  final case class RolledBackTransactions(txs: IndexedSeq[Transaction]) extends AnyVal

  final case class TransactionsForMiner(txs: IndexedSeq[Transaction]) extends AnyVal

  final case class InvMessageWithTransactionsIds(peer: ConnectedPeer, transactions: IndexedSeq[ModifierId])

  final case class RequestModifiersForTransactions(peer: ConnectedPeer, txsIds: Seq[ModifierId])

  final case class RequestedModifiersForRemote(peer: ConnectedPeer, txs: Seq[Transaction])

  final case class RequestForTransactions(source: ConnectedPeer,
                                          modifierTypeId: ModifierTypeId,
                                          modifierIds: Seq[ModifierId])

  case object SendTransactionsToMiner

  case object RemoveExpiredFromPool

  case object CleanupBloomFilter

  def props(settings: EncryAppSettings, ntp: NetworkTimeProvider, minerRef: ActorRef, influx: Option[ActorRef]): Props =
    Props(new MemoryPool(settings, ntp, minerRef, influx))

  class MemoryPoolPriorityQueue(settings: ActorSystem.Settings, config: Config)
    extends UnboundedStablePriorityMailbox(
      PriorityGenerator {
        case RemoveExpiredFromPool | CleanupBloomFilter | SendTransactionsToMiner => 0
        case NewTransactions(_) => 1
        case InvMessageWithTransactionsIds(_, _) | RequestModifiersForTransactions(_, _) => 2
        case otherwise => 3
      })
}