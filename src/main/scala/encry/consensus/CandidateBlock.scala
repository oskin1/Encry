package encry.consensus

import encry.crypto.PublicKey25519
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.EncryBaseTransaction
import encry.modifiers.state.box.proof.Signature25519
import encry.settings.Algos
import io.circe.Encoder
import io.circe.syntax._
import scorex.core.block.Block.{Timestamp, Version}
import scorex.crypto.authds.{ADDigest, SerializedAdProof}

case class CandidateBlock(parentOpt: Option[EncryBlockHeader],
                          adProofBytes: SerializedAdProof,
                          stateRoot: ADDigest,
                          version: Version,
                          transactions: Seq[EncryBaseTransaction],
                          timestamp: Timestamp,
                          nBits: NBits) {

  override def toString: String = s"<CandidateBlock timestamp=$timestamp txQty=${transactions.size} " +
    s"parentId=${parentOpt.map(_.encodedId).getOrElse("None")}>"
}

object CandidateBlock {

  implicit val jsonEncoder: Encoder[CandidateBlock] = (b: CandidateBlock) => Map(
    "parentId" -> b.parentOpt.map(p => Algos.encode(p.id)).getOrElse("None").asJson,
    "stateRoot" -> Algos.encode(b.stateRoot).asJson,
    "adProofBytes" -> Algos.encode(b.adProofBytes).asJson,
    "timestamp" -> b.timestamp.asJson,
    "transactions" -> b.transactions.map(_.asJson).asJson,
    "transactionsQty" -> b.transactions.length.asJson,
  ).asJson
}
