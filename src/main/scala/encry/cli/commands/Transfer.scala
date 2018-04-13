package encry.cli.commands

import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import encry.account.Address
import encry.cli.{Ast, Response}
import encry.modifiers.mempool.{EncryTransaction, TransactionFactory}
import encry.modifiers.state.box.AssetBox
import encry.modifiers.state.box.proposition.EncryProposition
import encry.settings.EncryAppSettings
import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import scorex.core.NodeViewHolder.ReceivableMessages.{GetDataFromCurrentView, LocallyGeneratedTransaction}
import scorex.core.utils.NetworkTimeProvider

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object Transfer extends Command {

  /**
    * Command "wallet -transfer=toAddress;Fee;Amount"
    * Example "wallet -transfer=3jSD9fwHEHJwHq99ARqhnNhqGXeKnkJMyX4FZjHV6L3PjbCmjG;100;100"
    *
    * @param nodeViewHolderRef
    * @param args
    * @return
    */
  override def execute(nodeViewHolderRef: ActorRef,
                       args: List[Ast.Param], settings: EncryAppSettings): Option[Response] = {
    implicit val timeout: Timeout = Timeout(settings.scorexSettings.restApi.timeout)
    Await.result((nodeViewHolderRef ?
      GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, Option[Response]] { view =>
        Try {
          lazy val timeProvider: NetworkTimeProvider = new NetworkTimeProvider(settings.scorexSettings.ntp)
          val secret = view.vault.keyManager.keys.head
          val recipient = Address @@ args.find(_.ident.ident == "addr").get.value.asInstanceOf[Ast.Str].s
          val fee = args.find(_.ident.ident == "fee").get.value.asInstanceOf[Ast.Num].i
          val amount = args.find(_.ident.ident == "amount").get.value.asInstanceOf[Ast.Num].i
          val timestamp = timeProvider.time()
          val boxes = view.vault.walletStorage.allBoxes.filter(_.isInstanceOf[AssetBox])
            .map(_.asInstanceOf[AssetBox]).foldLeft(Seq[AssetBox]()) { case (seq, box) =>
            if (seq.map(_.amount).sum < (amount + fee)) seq :+ box else seq
          }.toIndexedSeq

          val tx = TransactionFactory.defaultPaymentTransactionScratch(secret, fee, timestamp, boxes, recipient, amount)

          nodeViewHolderRef ! LocallyGeneratedTransaction[EncryProposition, EncryTransaction](tx)

          tx
        }.toOption.map(tx => Some(Response(tx.toString))).getOrElse(Some(Response("Operation failed. Malformed data.")))
      }).mapTo[Option[Response]], 5.second)
  }
}
