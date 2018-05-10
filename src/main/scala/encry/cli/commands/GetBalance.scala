package encry.cli.commands
import akka.actor.ActorRef
import akka.pattern._
import akka.util.Timeout
import encry.cli.Response
import encry.settings.{Algos, EncryAppSettings}
import encry.utils.BalanceCalculator
import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object GetBalance extends Command {

  override def execute(nodeViewHolderRef: ActorRef,
                       args: Command.Args, settings: EncryAppSettings): Future[Option[Response]] = {
    implicit val timeout: Timeout = Timeout(settings.scorexSettings.restApi.timeout)
    (nodeViewHolderRef ?
      GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, Option[Response]] { view =>
        Option(Response(
          view.vault.getBalances.foldLeft("")((str, tokenInfo) => str.concat(s"TokenID(${Algos.encode(tokenInfo._1)}) : ${tokenInfo._2}\n"))
        ))
      }).mapTo[Option[Response]]
  }

  override def testExecute(nodeViewHolderRef: ActorRef,
                       args: Command.Args, settings: EncryAppSettings): Unit = {
    nodeViewHolderRef !
      GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, Option[Response]] { view =>
        Option(Response(
          view.vault.getBalances.foldLeft("")((str, tokenInfo) => str.concat(s"TokenID(${Algos.encode(tokenInfo._1)}) : ${tokenInfo._2}\n"))
        ))
      }
  }
}
