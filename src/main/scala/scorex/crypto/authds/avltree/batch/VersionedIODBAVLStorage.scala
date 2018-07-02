package scorex.crypto.authds.avltree.batch

import com.google.common.primitives.Ints
import encry.settings.Algos
import io.iohk.iodb.{ByteArrayWrapper, Store}
import scorex.crypto.authds.avltree.batch.VersionedIODBAVLStorage.{InternalNodePrefix, LeafPrefix}
import scorex.crypto.authds.{ADDigest, ADKey, ADValue, Balance}
import scorex.crypto.hash
import scorex.crypto.hash.{CryptographicHash, Digest}
import scorex.utils.ScryptoLogging

import scala.util.{Failure, Try}

case class NodeParameters(keySize: Int, valueSize: Option[Int], labelSize: Int)

class VersionedIODBAVLStorage[D <: Digest](store: Store, nodeParameters: NodeParameters)(implicit val hf: CryptographicHash[D])
  extends VersionedAVLStorage[D] with ScryptoLogging {

  val TopNodeKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(nodeParameters.labelSize)(123: Byte))
  val TopNodeHeight: ByteArrayWrapper = ByteArrayWrapper(Array.fill(nodeParameters.labelSize)(124: Byte))

  override def update(prover: BatchAVLProver[D, _]): Try[Unit] = update(prover, Seq())

  override def rollback(version: ADDigest): Try[(ProverNodes[D], Int)] = Try {
    store.rollback(ByteArrayWrapper(version))
    val top: ProverNodes[D] = VersionedIODBAVLStorage.fetch[D](ADKey @@ store.get(TopNodeKey).get.data)(hf, store, nodeParameters)
    val topHeight: Int = Ints.fromByteArray(store.get(TopNodeHeight).get.data)
    top -> topHeight
  }.recoverWith { case e =>
    log.warn(s"Failed to recover tree for digest ${Algos.encode(version)}:", e)
    Failure(e)
  }

  override def version: Option[ADDigest] = store.lastVersionID.map(d => ADDigest @@ d.data)

  def rollbackVersions: Iterable[ADDigest] = store.rollbackVersions().map(d => ADDigest @@ d.data)

  override def update[K <: Array[Byte], V <: Array[Byte]](prover: BatchAVLProver[D, _], additionalData: Seq[(K, V)]): Try[Unit] = Try {
    val digestWrapper: Store.K = ByteArrayWrapper(prover.digest)
    val indexes: Seq[(Store.K, Store.K)] = Seq(TopNodeKey -> nodeKey(prover.topNode),
      TopNodeHeight -> ByteArrayWrapper(Ints.toByteArray(prover.rootNodeHeight)))
    val toRemove: List[Store.K] = prover.removedNodes().map(rn => ByteArrayWrapper(rn.label))
    val toUpdate: Seq[(Store.K, Store.K)] = indexes ++ serializedVisitedNodes(prover.topNode, isTop = true)
    val toUpdateWrapped: Seq[(Store.K, Store.K)] = additionalData.map { case (k, v) => ByteArrayWrapper(k) -> ByteArrayWrapper(v) }
    val toUpdateWithWrapped: Seq[(ByteArrayWrapper, ByteArrayWrapper)] = toUpdate ++ toUpdateWrapped
    val toRemoveMerged: List[ByteArrayWrapper] = toRemove.filterNot(toUpdate.map(_._1).intersect(toRemove).contains)
    log.info(s"Update storage to version $digestWrapper: ${toUpdateWithWrapped.size} elements to insert," +
      s" ${toRemove.size} elements to remove")
    store.update(digestWrapper, toRemoveMerged, toUpdateWithWrapped)
  }.recoverWith { case e =>
    log.warn("Failed to update tree", e)
    Failure(e)
  }

  // Should always serialize top node. It may not be new if it is the creation of the tree
  private def serializedVisitedNodes(node: ProverNodes[D], isTop: Boolean): Seq[(ByteArrayWrapper, ByteArrayWrapper)] =
    if (node.isNew || isTop) {
      val pair: (ByteArrayWrapper, ByteArrayWrapper) = (nodeKey(node), ByteArrayWrapper(toBytes(node)))
      node match {
        case n: InternalProverNode[D] =>
          pair +: (serializedVisitedNodes(n.left, isTop = false) ++ serializedVisitedNodes(n.right, isTop = false))
        case _: ProverLeaf[D] => Seq(pair)
      }
    } else Seq()

  private def nodeKey(node: ProverNodes[D]): ByteArrayWrapper = ByteArrayWrapper(node.label)

  private def toBytes(node: ProverNodes[D]): Array[Byte] = node match {
    case n: InternalProverNode[D] => InternalNodePrefix +: n.balance +: (n.key ++ n.left.label ++ n.right.label)
    case n: ProverLeaf[D] if nodeParameters.valueSize.isDefined => LeafPrefix +: (n.key ++ n.value ++ n.nextLeafKey)
    case n: ProverLeaf[D] => LeafPrefix +: (n.key ++ Ints.toByteArray(n.value.length) ++ n.value ++ n.nextLeafKey)
  }
}

object VersionedIODBAVLStorage {

  val InternalNodePrefix: Byte = 0.toByte
  val LeafPrefix: Byte = 1.toByte

  def fetch[D <: hash.Digest](key: ADKey)(implicit hf: CryptographicHash[D],
                                          store: Store,
                                          nodeParameters: NodeParameters): ProverNodes[D] = {
    val bytes: Array[Byte] = store(ByteArrayWrapper(key)).data
    val keySize: Int = nodeParameters.keySize
    val labelSize: Int = nodeParameters.labelSize
    bytes.head match {
      case InternalNodePrefix =>
        val balance: Balance = Balance @@ bytes.slice(1, 2).head
        val key: ADKey = ADKey @@ bytes.slice(2, 2 + keySize)
        val leftKey: ADKey = ADKey @@ bytes.slice(2 + keySize, 2 + keySize + labelSize)
        val rightKey: ADKey = ADKey @@ bytes.slice(2 + keySize + labelSize, 2 + keySize + (2 * labelSize))
        val n: ProxyInternalProverNode[D] = new ProxyInternalProverNode[D](key, leftKey, rightKey, balance)
        n.isNew = false
        n
      case LeafPrefix =>
        val key: ADKey = ADKey @@ bytes.slice(1, 1 + keySize)
        val (value: ADValue, nextLeafKey: ADKey) = if (nodeParameters.valueSize.isDefined) {
          val valueSize: Int = nodeParameters.valueSize.get
          val value: ADValue = ADValue @@ bytes.slice(1 + keySize, 1 + keySize + valueSize)
          val nextLeafKey: ADKey = ADKey @@ bytes.slice(1 + keySize + valueSize, 1 + (2 * keySize) + valueSize)
          value -> nextLeafKey
        } else {
          val valueSize: Int = Ints.fromByteArray(bytes.slice(1 + keySize, 1 + keySize + 4))
          val value: ADValue = ADValue @@ bytes.slice(1 + keySize + 4, 1 + keySize + 4 + valueSize)
          val nextLeafKey: ADKey = ADKey @@ bytes.slice(1 + keySize + 4 + valueSize, 1 + (2 * keySize) + 4 + valueSize)
          value -> nextLeafKey
        }
        val l: ProverLeaf[D] = new ProverLeaf[D](key, value, nextLeafKey)
        l.isNew = false
        l
    }
  }

  class ProxyInternalProverNode[D <: Digest](protected var pk: ADKey,
                                             val lkey: ADKey,
                                             val rkey: ADKey,
                                             protected var pb: Balance = Balance @@ 0.toByte)
                                            (implicit val phf: CryptographicHash[D],
                                             store: Store,
                                             nodeParameters: NodeParameters)
    extends InternalProverNode(k = pk, l = null, r = null, b = pb)(phf) {
    override def left: ProverNodes[D] = {
      if (l == null) l = VersionedIODBAVLStorage.fetch[D](lkey)
      l
    }
    override def right: ProverNodes[D] = {
      if (r == null) r = VersionedIODBAVLStorage.fetch[D](rkey)
      r
    }
  }
}
