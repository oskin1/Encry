package encry.utils

import cats.syntax.semigroup._
import cats.instances.long._
import cats.instances.map._
import org.encryfoundation.common.modifiers.state.box.Box.Amount
import org.encryfoundation.common.modifiers.state.box.TokenIssuingBox.TokenId
import org.encryfoundation.common.modifiers.state.box.{AssetBox, EncryBaseBox, TokenIssuingBox}
import org.encryfoundation.common.utils.Algos
import org.encryfoundation.prismlang.compiler.CompiledContract.ContractHash

object BalanceCalculator {

  def balanceSheet(bxs: Traversable[EncryBaseBox],
                   defaultTokenId: TokenId,
                   excludeTokenIssuance: Boolean = false): Map[(ContractHash, TokenId), Amount] =
    bxs.foldLeft(Map.empty[(ByteStr, ByteStr), Amount]) {
      case (cache, bx: AssetBox) =>
        val tokenId: ByteStr = ByteStr(bx.tokenIdOpt.getOrElse(defaultTokenId))
        val contractHash: ByteStr = ByteStr(bx.proposition.contractHash)
        cache.get(contractHash -> tokenId).map { amount =>
          cache.updated(contractHash -> tokenId, amount + bx.amount)
        }.getOrElse(cache.updated(contractHash -> tokenId, bx.amount))
      case (cache, bx: TokenIssuingBox) if !excludeTokenIssuance =>
        val contractHash: ByteStr = ByteStr(bx.proposition.contractHash)
        val tokenId: ByteStr = ByteStr(bx.tokenId)
        cache.get(contractHash -> tokenId).map { amount =>
          cache.updated(contractHash -> tokenId, amount + bx.amount)
        }.getOrElse(cache.updated(contractHash -> tokenId, bx.amount))
      case (cache, _) => cache
    }.map { case ((hash, id), am) => (hash.arr -> id.arr) -> am }

  def balanceSheet1(
    boxes: List[EncryBaseBox],
    defaultTokenId: TokenId,
    excludeTokenIssuing: Boolean = false
                   ): Map[ContractHash, Map[TokenId, Amount]] = {
    boxes.foldLeft(Map.empty[String, Map[String, Amount]]) {
      case (hashToValue, box: AssetBox) =>
        val tokenId = Algos.encode(box.tokenIdOpt.getOrElse(defaultTokenId))
        val toAdd = Map(Algos.encode(box.proposition.contractHash) -> Map(tokenId -> box.amount))
        hashToValue |+| toAdd
      case (hashToValue, box: TokenIssuingBox) =>
        val tokenId = Algos.encode(box.tokenId)
        val toAdd = Map(Algos.encode(box.proposition.contractHash) -> Map(tokenId -> box.amount))
        hashToValue |+| toAdd
      case (hashToValue, _) => hashToValue
    }.map {
      case (str, stringToAmount) => Algos.decode(str).get -> stringToAmount.map {
        case (str, amount) => Algos.decode(str).get -> amount
      }
    }
  }
}