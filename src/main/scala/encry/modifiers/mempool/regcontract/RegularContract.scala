package encry.modifiers.mempool.regcontract

import com.google.common.primitives.Ints
import encry.account.{Account, AccountSerializer}
import encry.modifiers.serialization.SerializationException
import org.encryfoundation.prismlang.compiler.{CompiledContract, CostEstimator}
import org.encryfoundation.prismlang.core.Ast.{Expr, Ident}
import org.encryfoundation.prismlang.core.{Ast, Types}
import org.encryfoundation.prismlang.lib.predefined.signature.CheckSig
import scorex.crypto.encode.Base16

import scala.util.{Failure, Success, Try}

sealed trait RegularContract {
  val typeId: Byte
  val contract: CompiledContract
  lazy val bytes: Array[Byte] = RegularContract.Serializer.toBytes(this)
}
object RegularContract {
  def calculateCost(contract: CompiledContract): CompiledContract =
    contract.copy(cost = CostEstimator.default.costOf(contract.script) + contract.args.map(_._2.dataCost).sum)
  object Serializer {
    def toBytes(obj: RegularContract): Array[Byte] = obj match {
      case OpenContract() => Array(OpenContract.TypeId)
      case HeightLockedContract(h) => HeightLockedContract.TypeId +: Ints.toByteArray(h)
      case AccountLockedContract(acc) => AccountLockedContract.TypeId +: acc.bytes
    }
    def parseBytes(bytes: Array[Byte]): Try[RegularContract] = bytes.head match {
      case OpenContract.TypeId => Success(OpenContract())
      case HeightLockedContract.TypeId =>
        if (bytes.lengthCompare(5) == 0) Success(HeightLockedContract(Ints.fromByteArray(bytes.tail)))
        else Failure(SerializationException(s"`HeightLockedContract` deserialization failed"))
      case AccountLockedContract.TypeId =>
        if (bytes.lengthCompare(Account.AddressLength + 1) == 0) AccountSerializer.parseBytes(bytes.tail).map(AccountLockedContract.apply)
        else Failure(SerializationException(s"`AccountLockedContract` deserialization failed"))
    }
  }
}

case class OpenContract() extends RegularContract {
  val typeId: Byte = OpenContract.TypeId
  val contract: CompiledContract = RegularContract.calculateCost(CompiledContract(List.empty, Ast.Expr.True, 0))
}
object OpenContract { val TypeId: Byte = 0 }

case class HeightLockedContract(height: Int) extends RegularContract {
  val typeId: Byte = HeightLockedContract.TypeId
  val contract: CompiledContract = RegularContract.calculateCost({
    CompiledContract(
      List("state" -> Types.EncryState),
      Expr.If(
        Expr.Compare(
          Expr.Attribute(
            Expr.Name(
              Ast.Ident("state"),
              Types.EncryState
            ),
            Ast.Ident("height"),
            Types.PInt
          ),
          List(Ast.CompOp.GtE),
          List(Expr.IntConst(height.toLong))
        ),
        Expr.True,
        Expr.False,
        Types.PBoolean
      ), 0
    )
  })
}
object HeightLockedContract { val TypeId: Byte = 1 }

case class AccountLockedContract(account: Account) extends RegularContract {
  val typeId: Byte = AccountLockedContract.TypeId
  val contract: CompiledContract = RegularContract.calculateCost({
    CompiledContract(
      List("tx" -> Types.EncryTransaction, "sig" -> Types.Signature25519),
      Expr.Call(
        Expr.Name(Ident("checkSig"), Types.PFunc(CheckSig.args.toList, Types.PBoolean)),
        List(
          Expr.Name(Ident("sig"), Types.Signature25519),
          Expr.Attribute(
            Expr.Name(Ident("tx"), Types.EncryTransaction),
            Ident("messageToSign"),
            Types.PCollection.ofByte
          ),
          Expr.Base16Str(Base16.encode(account.pubKey))
        ),
        Types.PBoolean
      ), 0
    )
  })
}
object AccountLockedContract { val TypeId: Byte = 2 }
