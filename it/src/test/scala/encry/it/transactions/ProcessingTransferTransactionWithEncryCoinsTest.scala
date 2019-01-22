package encry.it.transactions

import TransactionGenerator.CreateTransaction
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import encry.consensus.EncrySupplyController
import encry.it.configs.Configs
import encry.it.docker.{Docker, Node}
import encry.it.util.KeyHelper._
import encry.modifiers.history.Block
import encry.modifiers.mempool.Transaction
import encry.modifiers.state.box.{AssetBox, EncryBaseBox}
import encry.settings.Constants.IntrinsicTokenId
import encry.view.history.History.Height
import org.encryfoundation.common.Algos
import org.encryfoundation.common.crypto.{PrivateKey25519, PublicKey25519}
import org.encryfoundation.common.transaction.EncryAddress.Address
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{AsyncFunSuite, Matchers}
import scorex.crypto.signatures.Curve25519
import scorex.utils.Random
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ProcessingTransferTransactionWithEncryCoinsTest extends AsyncFunSuite
  with Matchers
  with ScalaFutures
  with StrictLogging {

  test("Create and send monetary transaction. Check balance.") {

    val amount: Int               = scala.util.Random.nextInt(2000)
    val fee: Long                 = scala.util.Random.nextInt(500)
    val firstHeightToWait: Int    = 5
    val secondHeightToWait: Int   = 8
    val mnemonicKey: String       = "index another island accuse valid aerobic little absurd bunker keep insect scissors"
    val privKey: PrivateKey25519  = createPrivKey(Some(mnemonicKey))
    val recipientAddress: Address = PublicKey25519(Curve25519.createKeyPair(Random.randomBytes())._2).address.address
    val waitTime: FiniteDuration  = 30.minutes

    val supplyAtHeight: Long = (0 to secondHeightToWait).foldLeft(0: Long) {
      case (supply, i) => supply + EncrySupplyController.supplyAt(Height @@ i)
    }

    val docker: Docker   = Docker()
    val config: Config   = Configs.mining(true)
      .withFallback(Configs.offlineGeneration(true))
      .withFallback(Configs.nodeName("node1"))
    val nodes: List[Node] = docker.startNodes(Seq(config))

    Await.result(nodes.head.waitForHeadersHeight(firstHeightToWait), waitTime)

    val boxes: Seq[EncryBaseBox] = Await.result(nodes.head.outputs, waitTime)
    val oneBox: AssetBox         = boxes.collect { case ab: AssetBox => ab }.head
    val transaction: Transaction = CreateTransaction.defaultPaymentTransaction(
      privKey,
      fee,
      System.currentTimeMillis(),
      IndexedSeq(oneBox).map(_ -> None),
      recipientAddress,
      amount
    )

    Await.result(nodes.head.sendTransaction(transaction), waitTime)
    Await.result(nodes.head.waitForHeadersHeight(secondHeightToWait), waitTime)

    val checkBalance: Boolean = Await.result(nodes.head.balances, waitTime)
      .find(_._1 == Algos.encode(IntrinsicTokenId))
      .map(_._2 == supplyAtHeight - amount)
      .get

    val headersAtHeight: List[String] = (firstHeightToWait + 1 to secondHeightToWait)
      .foldLeft(List[String]()) { case (list, blockHeight) =>
        val headers: Future[List[String]] = nodes.head.getHeadersIdAtHeight(blockHeight)
        val result: List[String]          = Await.result(headers, waitTime)
        list ::: result
      }

    Await.result(nodes.head.getBlock(headersAtHeight.head), waitTime)

    val lastBlocks: Future[Seq[Block]] = Future.sequence(headersAtHeight.map { h => nodes.head.getBlock(h) })

    lastBlocks.map { blocks =>
      val txsNum: Int = blocks.map(_.payload.transactions.size).sum
      docker.close()
      val transactionFromChain: Transaction = blocks.flatMap(_.payload.transactions.init).head
      transactionFromChain.id shouldEqual transaction.id
      true shouldEqual (txsNum > secondHeightToWait - firstHeightToWait)
      txsNum shouldEqual (secondHeightToWait - firstHeightToWait + 1)
      checkBalance shouldBe true
    }
  }
}