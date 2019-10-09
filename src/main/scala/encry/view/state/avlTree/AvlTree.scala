package encry.view.state.avlTree

import cats.syntax.order._
import cats.{Monoid, Order}
import com.typesafe.scalalogging.StrictLogging
import encry.storage.VersionalStorage
import encry.storage.VersionalStorage.{StorageKey, StorageValue, StorageVersion}
import encry.view.fastSync.SnapshotHolder._
import encry.view.fastSync.SnapshotHolder.SnapshotChunk
import encry.view.state.avlTree.AvlTree.Direction
import encry.view.state.avlTree.AvlTree.Directions.{EMPTY, LEFT, RIGHT}
import encry.view.state.avlTree.utils.implicits.{Hashable, Serializer}
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.common.modifiers.history.Block
import org.encryfoundation.common.utils.Algos
import scorex.utils.Random
import scala.util.Try

final case class AvlTree[K: Hashable : Order, V](rootNode: Node[K, V], storage: VersionalStorage) extends AutoCloseable with StrictLogging { //scalastyle:ignore

  implicit def nodeOrder(implicit ord: Order[K]): Order[Node[K, V]] = new Order[Node[K, V]] {
    override def compare(x: Node[K, V], y: Node[K, V]): Int = ord.compare(x.key, y.key)
  }

  def root: K = rootNode.key

  val rootHash: Array[Byte] = rootNode.hash

  def insertAndDeleteMany(version: StorageVersion,
                          toInsert: List[(K, V)],
                          toDelete: List[K])
                         (implicit kSer: Serializer[K],
                          vSer: Serializer[V],
                          kM: Monoid[K],
                          vM: Monoid[V]): AvlTree[K, V] = {
    val rootAfterDelete = toDelete.foldLeft(NodeWithOpInfo(rootNode)) {
      case (prevRoot, toDelete) =>
        deleteKey(toDelete, prevRoot)
    }
    val newRoot = toInsert.foldLeft(rootAfterDelete) {
      case (prevRoot, (keyToInsert, valueToInsert)) =>
        val res = insert(keyToInsert, valueToInsert, prevRoot)
        res
    }
    val deletedNodes = newRoot.opInfo.deletedNodes
    val insertedNodes = newRoot.opInfo.insertedNodes
    val shadowedRoot = ShadowNode.childsToShadowNode(newRoot.node)
    storage.insert(version,
      toInsert.map { case (key, value) => StorageKey @@ Algos.hash(Algos.hash(kSer.toBytes(key))) -> StorageValue @@ vSer.toBytes(value) } ++
        insertedNodes.map { case (key, node) =>
          StorageKey @@ key.data -> StorageValue @@ NodeSerilalizer.toBytes(ShadowNode.childsToShadowNode(node))
        }.toList :+
        (AvlTree.rootNodeKey -> StorageValue @@ shadowedRoot.hash),
      deletedNodes.map(key => StorageKey @@ key.data)
    )
    AvlTree(shadowedRoot, storage)
  }

  def getOperationsRootHash(toInsert: List[(K, V)],
                            toDelete: List[K])
                           (implicit kSer: Serializer[K],
                            vSer: Serializer[V],
                            kM: Monoid[K],
                            vM: Monoid[V]): Array[Byte] = {
    val rootAfterDelete = toDelete.foldLeft(NodeWithOpInfo(rootNode)) {
      case (prevRoot, toDelete) =>
        deleteKey(toDelete, prevRoot)
    }
    val newRoot = toInsert.foldLeft(rootAfterDelete) {
      case (prevRoot, (keyToInsert, valueToInsert)) =>
        insert(keyToInsert, valueToInsert, prevRoot)
    }
    newRoot.node.hash
  }

  def get(k: K)(implicit kSer: Serializer[K], vSer: Serializer[V]): Option[V] = storage.get(StorageKey !@@ Algos.hash(kSer.toBytes(k))).map(vSer.fromBytes)

  def contains(k: K)(implicit kSer: Serializer[K]): Boolean = storage.get(StorageKey !@@ Algos.hash(Algos.hash(kSer.toBytes(k)))).isDefined

  def getInTree(k: K): Option[V] = getK(k, rootNode)

  def containsInTree(k: K): Boolean = find(k).isDefined

  def find(k: K): Option[(K, V)] = getK(k, rootNode).map { value =>
    (k, value)
  }

  private def getK(key: K, node: Node[K, V]): Option[V] = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      getK(key, restoredNode)
    case internalNode: InternalNode[K, V] =>
      if (internalNode.key === key) Some(internalNode.value)
      else if (internalNode.key > key) internalNode.leftChild.flatMap(child => getK(key, child))
      else internalNode.rightChild.flatMap(child => getK(key, child))
    case leafNode: LeafNode[K, V] => if (leafNode.key === key) Some(leafNode.value) else None
  }

  def deleteKey(key: K,
                nodeWithOpInfo: NodeWithOpInfo[K, V])(implicit m: Monoid[K], v: Monoid[V]): NodeWithOpInfo[K, V] = {
    val delResult = delete(nodeWithOpInfo.node, key, nodeWithOpInfo.opInfo)
    NodeWithOpInfo(delResult._1.get, delResult._2)
  }

  private def delete(node: Node[K, V],
                     key: K,
                     prevOpsInfo: OperationInfo[K, V] = OperationInfo.empty[K, V])
                    (implicit m: Monoid[K], v: Monoid[V]): (Option[Node[K, V]], OperationInfo[K, V]) = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      delete(restoredNode, key, prevOpsInfo)
    case leafNode: LeafNode[K, V] =>
      if (leafNode.key === key) (None, prevOpsInfo.updateDeleted(ByteArrayWrapper(leafNode.hash)))
      else (Some(leafNode), prevOpsInfo)
    case internalNode: InternalNode[K, V] =>
      if (internalNode.key > key) {
        val (newLeftChild, updatedNodesInfo) = internalNode.leftChild.map(node => delete(node, key, prevOpsInfo))
          .getOrElse((Option.empty[Node[K, V]], OperationInfo.empty[K, V]))
        //if (updatedNodesInfo.containsTest) println("here deleted!")
        val childUpdated = internalNode.updateChilds(newLeftChild = newLeftChild, prevOpsInfo = updatedNodesInfo)
        val newNode = childUpdated.selfInspection
        val balancedRoot = balance(newNode)
        (Some(balancedRoot.node), balancedRoot.opInfo)
      } else if (internalNode.key < key) {
        val (newRightChild, rightChildInfo) = internalNode.rightChild.map(node => delete(node, key, prevOpsInfo))
          .getOrElse((Option.empty[Node[K, V]], prevOpsInfo))
        val childUpdated = internalNode.updateChilds(newRightChild = newRightChild, prevOpsInfo = rightChildInfo)
        val newNode = childUpdated.selfInspection
        val balancedRoot = balance(newNode)
        (Some(balancedRoot.node), balancedRoot.opInfo)
      } else {
        val theClosestValue = findTheClosestValue(internalNode, internalNode.key)
        val hash = implicitly[Hashable[K]]
        val newNode = theClosestValue match {
          case ((newKey, newValue), LEFT) =>
            val (newLeftChild, leftChildInfo) = internalNode.leftChild.map(node => delete(node, key, prevOpsInfo))
              .getOrElse((Option.empty[Node[K, V]], OperationInfo.empty[K, V]))
            val newNode = internalNode.copy(key = newKey, value = newValue, hash = hash.hash(newKey))
              .updateChilds(newLeftChild = newLeftChild, prevOpsInfo = leftChildInfo)
            val newNodeInfo = List(ByteArrayWrapper(newNode.node.hash) -> newNode.node)
            val newNodes = newLeftChild.map(leftNode => newNodeInfo :+ ByteArrayWrapper(leftNode.hash) -> leftNode).getOrElse(newNodeInfo)
            val res = NodeWithOpInfo(newNode.node, newNode.opInfo.updateInserted(newNodes))
            res
          case ((newKey, newValue), RIGHT) =>
            val (newRightChild, rightChildInfo) = internalNode.rightChild.map(node => delete(node, key, prevOpsInfo))
              .getOrElse((Option.empty[Node[K, V]], OperationInfo.empty[K, V]))
            val newNode = internalNode.copy(key = newKey, value = newValue, hash = hash.hash(newKey))
              .updateChilds(newRightChild = newRightChild, prevOpsInfo = rightChildInfo)
            val newNodeInfo = List(ByteArrayWrapper(newNode.node.hash) -> newNode.node)
            val newNodes = newRightChild.map(rightNode => newNodeInfo :+ ByteArrayWrapper(rightNode.hash) -> rightNode).getOrElse(newNodeInfo)
            NodeWithOpInfo(newNode.node, newNode.opInfo.updateInserted(newNodes))
          case ((_, _), EMPTY) => NodeWithOpInfo(internalNode, prevOpsInfo)
        }
        val balancedNode = balance(newNode)
        (Some(balancedNode.node), balancedNode.opInfo)
      }
  }

  private def findTheClosestValue(node: Node[K, V], key: K)
                                 (implicit m: Monoid[K], v: Monoid[V]): ((K, V), Direction) = {
    val h = implicitly[Order[Node[K, V]]]
    node match {
      case shadowNode: ShadowNode[K, V] =>
        val restoredNode = shadowNode.restoreFullNode(storage)
        findTheClosestValue(restoredNode, key)
      case leafNode: LeafNode[K, V] => leafNode.key -> leafNode.value -> EMPTY
      case internalNode: InternalNode[K, V] =>
        val onLeft = internalNode.leftChild.flatMap { node =>
          val rightPath = getRightPath(node)
          rightPath.headOption.map(head =>
            rightPath.foldLeft[Node[K, V]](head) {
              case (bestNode, nodeToCompr) => h.max(bestNode, nodeToCompr)
            } -> LEFT
          )
        }
        onLeft.orElse {
          val onRight = internalNode.rightChild.flatMap { node =>
            val leftPath = getLeftPath(node)
            leftPath.headOption.map(head =>
              leftPath.foldLeft[Node[K, V]](head) {
                case (bestNode, nodeToCompr) => h.min(bestNode, nodeToCompr)
              } -> RIGHT)
          }
          onRight
        }.map(node => node._1.key -> node._1.value -> node._2).get
    }
  }

  //[K: Monoid : Serializer : Hashable : Order, V: Monoid : Serializer]
  def rollbackTo(to: StorageVersion)(implicit kMonoid: Monoid[K], kSer: Serializer[K],
                                     vMonoid: Monoid[V], vSer: Serializer[V]): Try[AvlTree[K, V]] = Try {
    storage.rollbackTo(to)
    val rootNode = NodeSerilalizer.fromBytes[K, V](storage.get(StorageKey !@@ storage.get(AvlTree.rootNodeKey).get).get)
    AvlTree[K, V](rootNode, storage)
  }

  private def getRightPath(node: Node[K, V]): List[Node[K, V]] = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      getRightPath(restoredNode)
    case leafNode: LeafNode[K, V] => List(leafNode)
    case internalNode: InternalNode[K, V] => internalNode +: internalNode.rightChild.map(getRightPath).getOrElse(List.empty)
  }

  private def getLeftPath(node: Node[K, V]): List[Node[K, V]] = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      getLeftPath(restoredNode)
    case leafNode: LeafNode[K, V] => List(leafNode)
    case internalNode: InternalNode[K, V] => internalNode +: internalNode.leftChild.map(getLeftPath).getOrElse(List.empty)
  }

  private def insert(newKey: K,
                     newValue: V,
                     nodeWithOpInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] = {
    nodeWithOpInfo.node match {
      case shadowNode: ShadowNode[K, V] =>
        val restoredNode = shadowNode.restoreFullNode(storage)
        insert(newKey, newValue, NodeWithOpInfo(restoredNode, nodeWithOpInfo.opInfo))
      case _: EmptyNode[K, V] =>
        val newLeaf = LeafNode[K, V](newKey, newValue)
        NodeWithOpInfo(newLeaf, nodeWithOpInfo.opInfo.updateInserted(ByteArrayWrapper(newLeaf.hash) -> newLeaf))
      case leafNode: LeafNode[K, V] =>
        if (leafNode.key === newKey) NodeWithOpInfo(leafNode.copy(value = newValue), nodeWithOpInfo.opInfo)
        else {
          val newInternalNode = InternalNode[K, V](leafNode.key, leafNode.value, height = 1, balance = 0)
          insert(
            newKey,
            newValue,
            NodeWithOpInfo(
              newInternalNode,
              nodeWithOpInfo.opInfo.update(ByteArrayWrapper(newInternalNode.hash) -> newInternalNode, ByteArrayWrapper(leafNode.hash))
            )
          )
        }
      case internalNode: InternalNode[K, V] =>
        if (internalNode.key > newKey) {
          val newLeftChild = internalNode.leftChild.map(previousLeftChild =>
            insert(newKey, newValue, nodeWithOpInfo.copy(node = previousLeftChild))
          ).getOrElse {
            val newLeaf = LeafNode(newKey, newValue)
            NodeWithOpInfo(newLeaf, nodeWithOpInfo.opInfo.updateInserted(ByteArrayWrapper(newLeaf.hash) -> newLeaf))
          }
          val newNode = internalNode.updateChilds(newLeftChild = Some(newLeftChild.node), prevOpsInfo = newLeftChild.opInfo)
          balance(newNode.copy(opInfo = newNode.opInfo.update(ByteArrayWrapper(newNode.node.hash) -> newNode.node, ByteArrayWrapper(internalNode.hash))))
        } else {
          val newRightChild = internalNode.rightChild.map(previousRightChild =>
            insert(newKey, newValue, nodeWithOpInfo.copy(node = previousRightChild))
          ).getOrElse {
            val newLeaf = LeafNode(newKey, newValue)
            NodeWithOpInfo(newLeaf, nodeWithOpInfo.opInfo.updateInserted(ByteArrayWrapper(newLeaf.hash) -> newLeaf))
          }
          val newNode =
            internalNode.updateChilds(newRightChild = Some(newRightChild.node), prevOpsInfo = newRightChild.opInfo)
          balance(newNode.copy(opInfo = newNode.opInfo.update(ByteArrayWrapper(newNode.node.hash) -> newNode.node, ByteArrayWrapper(internalNode.hash))))
        }
    }
  }

  private def balance(nodeWithOpsInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] = {
    nodeWithOpsInfo.node match {
      case shadowNode: ShadowNode[K, V] =>
        val restoredNode = shadowNode.restoreFullNode(storage)
        balance(nodeWithOpsInfo.copy(node = restoredNode))
      case internalNode: InternalNode[K, V] =>
        val newAdditionalInfo = (
          Math.abs(internalNode.balance),
          internalNode.leftChild.exists(leftChild =>
            (leftChild.height - internalNode.rightChild.map(_.height).getOrElse(-1) == 2) &&
              rightSubTreeHeight(leftChild) > leftSubTreeHeight(leftChild)), //lr
          internalNode.leftChild.exists(leftChild =>
            (leftChild.height - internalNode.rightChild.map(_.height).getOrElse(-1) == 2) &&
              rightSubTreeHeight(leftChild) <= leftSubTreeHeight(leftChild)), //r
          internalNode.rightChild.exists(rightChild =>
            ({
              rightChild.height - internalNode.leftChild.map(_.height).getOrElse(-1) == 2
            }) && {
              leftSubTreeHeight(rightChild) > rightSubTreeHeight(rightChild)
            }) //rl
        )
        newAdditionalInfo match {
          case (_, true, _, _) =>
            lrRotation(nodeWithOpsInfo)
          case (_, _, _, true) =>
            rlRotation(nodeWithOpsInfo)
          case (_, _, true, _) =>
            rightRotation(nodeWithOpsInfo)
          case (2, _, _, _) =>
            leftRotation(nodeWithOpsInfo)
          case _ => nodeWithOpsInfo
        }
      case leafNode: LeafNode[K, V] => nodeWithOpsInfo
    }
  }

  private def rightSubTreeHeight(node: Node[K, V]): Int = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      rightSubTreeHeight(restoredNode)
    case internalNode: InternalNode[K, V] => internalNode.rightChild.map(_.height).getOrElse(-1)
    case _ => -1
  }

  private def leftSubTreeHeight(node: Node[K, V]): Int = node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      leftSubTreeHeight(restoredNode)
    case internalNode: InternalNode[K, V] => internalNode.leftChild.map(_.height).getOrElse(-1)
    case _ => -1
  }

  private def rightRotation(nodeWithOpInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] = nodeWithOpInfo.node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      rightRotation(NodeWithOpInfo(restoredNode, nodeWithOpInfo.opInfo))
    case leafNode: LeafNode[K, V] => nodeWithOpInfo
    case internalNode: InternalNode[K, V] =>
      val newRoot = internalNode.leftChild.get match {
        case LeafNode(key, value) => InternalNode(key, value, 0, 0)
        case internalNode: InternalNode[K, V] => internalNode
        case shadowNode: ShadowNode[K, V] =>
          shadowNode.restoreFullNode(storage) match {
            case LeafNode(key, value) => InternalNode(key, value, 0, 0)
            case internalNode: InternalNode[K, V] => internalNode
          }
      }
      val (newLeftChildForPrevRoot, leftChildUpdateInfo) = {
        val rightNodeWithOpsInfo = newRoot.rightChild.map(child => child.selfInspection(nodeWithOpInfo.opInfo))
        (rightNodeWithOpsInfo.map(_.node), rightNodeWithOpsInfo.map(_.opInfo).getOrElse(nodeWithOpInfo.opInfo))
      }
      val prevRootWithUpdatedChildren =
        internalNode.updateChilds(newLeftChild = newLeftChildForPrevRoot, prevOpsInfo = leftChildUpdateInfo)
      val prevRoot = prevRootWithUpdatedChildren.selfInspection
      val newUpdatedRoot =
        newRoot.updateChilds(newRightChild = Some(prevRoot.node), prevOpsInfo = prevRoot.opInfo)
      val listToDel = List(ByteArrayWrapper(internalNode.hash), ByteArrayWrapper(internalNode.leftChild.get.hash))
      NodeWithOpInfo(newUpdatedRoot.node,
        newUpdatedRoot.opInfo.update(
          List(ByteArrayWrapper(prevRoot.node.hash) -> prevRoot.node, ByteArrayWrapper(newUpdatedRoot.node.hash) -> newUpdatedRoot.node),
          List(ByteArrayWrapper(internalNode.hash), ByteArrayWrapper(internalNode.leftChild.get.hash))
        )
      )
  }

  private def leftRotation(nodeWithOpsInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] = nodeWithOpsInfo.node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      leftRotation(NodeWithOpInfo(restoredNode, nodeWithOpsInfo.opInfo))
    case leafNode: LeafNode[K, V] => nodeWithOpsInfo
    case internalNode: InternalNode[K, V] =>
      val newRoot = internalNode.rightChild.get match {
        case LeafNode(key, value) => InternalNode(key, value, 0, 0)
        case internalNode: InternalNode[K, V] => internalNode
        case shadowNode: ShadowNode[K, V] =>
          shadowNode.restoreFullNode(storage) match {
            case LeafNode(key, value) => InternalNode(key, value, 0, 0)
            case internalNode: InternalNode[K, V] => internalNode
          }
      }
      val (newRightChildForPrevRoot, rightChildInfo) = {
        val leftNodeWithOpsInfo = newRoot.leftChild.map(child => child.selfInspection(nodeWithOpsInfo.opInfo))
        (leftNodeWithOpsInfo.map(_.node), leftNodeWithOpsInfo.map(_.opInfo).getOrElse(nodeWithOpsInfo.opInfo))
      }
      val prevRootWithUpdatedChildren =
        internalNode.updateChilds(newRightChild = newRightChildForPrevRoot, prevOpsInfo = rightChildInfo)
      val prevRoot = prevRootWithUpdatedChildren.selfInspection
      val newUpdatedRoot = newRoot.updateChilds(newLeftChild = Some(prevRoot.node), prevOpsInfo = prevRoot.opInfo)
      val listToDel = List(ByteArrayWrapper(internalNode.hash), ByteArrayWrapper(internalNode.rightChild.get.hash))
      NodeWithOpInfo(newUpdatedRoot.node, newUpdatedRoot.opInfo.update(
        List(ByteArrayWrapper(prevRoot.node.hash) -> prevRoot.node, ByteArrayWrapper(newUpdatedRoot.node.hash) -> newUpdatedRoot.node),
        List(ByteArrayWrapper(internalNode.hash), ByteArrayWrapper(internalNode.rightChild.get.hash))
      ))
  }

  private def rlRotation(nodeWithOpInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] = nodeWithOpInfo.node match {
    case shadowNode: ShadowNode[K, V] =>
      val restoredNode = shadowNode.restoreFullNode(storage)
      rlRotation(NodeWithOpInfo(restoredNode, nodeWithOpInfo.opInfo))
    case leafNode: LeafNode[K, V] => nodeWithOpInfo
    case internalNode: InternalNode[K, V] =>
      val rotatedRightChild = rightRotation(NodeWithOpInfo(internalNode.rightChild.get, nodeWithOpInfo.opInfo))
      val updatedNode =
        internalNode.updateChilds(newRightChild = Some(rotatedRightChild.node), prevOpsInfo = rotatedRightChild.opInfo)
      leftRotation(updatedNode)
  }

  private def lrRotation(nodeWithOpsInfo: NodeWithOpInfo[K, V]): NodeWithOpInfo[K, V] =
    nodeWithOpsInfo.node match {
      case shadowNode: ShadowNode[K, V] =>
        val restoredNode = shadowNode.restoreFullNode(storage)
        lrRotation(NodeWithOpInfo(restoredNode, nodeWithOpsInfo.opInfo))
      case leafNode: LeafNode[K, V] => nodeWithOpsInfo
      case internalNode: InternalNode[K, V] =>
        val rotatedLeftChild = leftRotation(NodeWithOpInfo(internalNode.leftChild.get, nodeWithOpsInfo.opInfo))
        val updatedNode =
          internalNode.updateChilds(newLeftChild = Some(rotatedLeftChild.node), prevOpsInfo = rotatedLeftChild.opInfo)
        rightRotation(updatedNode)
    }

  private def insertionInFastSyncMod(nodes: List[Node[K, V]], isRoot: Boolean = false)(implicit kSerializer: Serializer[K],
                                                                                       vSerializer: Serializer[V],
                                                                                       kMonoid: Monoid[K],
                                                                                       vMonoid: Monoid[V]): AvlTree[K, V] = {
    val nodesToInsert: List[(StorageKey, StorageValue)] = nodes.flatMap { node =>
      val fullData: (StorageKey, StorageValue) =
        StorageKey @@ Algos.hash(Algos.hash(kSerializer.toBytes(node.key))) -> StorageValue @@ vSerializer.toBytes(node.value)
      val shadowData: (StorageKey, StorageValue) =
        StorageKey @@ node.hash -> StorageValue @@ NodeSerilalizer.toBytes(ShadowNode.childsToShadowNode(node)) //todo probably probably probably
      if (isRoot)
        fullData :: shadowData :: (AvlTree.rootNodeKey -> StorageValue @@ ShadowNode.childsToShadowNode(node).hash) :: Nil
      else
        fullData :: shadowData :: Nil
    }
    storage.insert(StorageVersion @@ Random.randomBytes(), nodesToInsert, List.empty)
    this
  }

  def initializeSnapshotData(bestBlock: Block)(implicit kSerializer: Serializer[K],
                                               vSerializer: Serializer[V]): (SnapshotManifest, List[SnapshotChunk]) = {
    val rawSubtrees: List[SnapshotChunk] = createSubtrees(List(rootNode), List.empty).reverse
    val newManifest: SnapshotManifest = rootNode match {
      case i: InternalNode[K, V] =>
        SnapshotManifest(
          bestBlock.id, rootHash, NodeSerilalizer.toProto(i), rawSubtrees.size, bestBlock.header.height
        )
      case s: ShadowNode[K, V] =>
        SnapshotManifest(
          bestBlock.id, rootHash, NodeSerilalizer.toProto(s.restoreFullNode(storage)), rawSubtrees.size, bestBlock.header.height
        )
    }
    val updS: List[SnapshotChunk] = if (rawSubtrees.nonEmpty) {
      val firstElem = rawSubtrees.headOption
      val newRaw: List[SnapshotChunk] = rawSubtrees.drop(1)
      firstElem.map { ch => ch.copy(nodesList = ch.nodesList.drop(1)) }.fold(newRaw)(_ :: newRaw)
    } else rawSubtrees
    val subtrees: List[SnapshotChunk] = updS.map(_.copy(manifestId = newManifest.ManifestId))
    newManifest -> subtrees
  }

  def assembleTree(initState: AvlTree[K, V], chunks: List[SnapshotChunk])(implicit kSerializer: Serializer[K],
                                                                          vSerializer: Serializer[V],
                                                                          kMonoid: Monoid[K],
                                                                          vMonoid: Monoid[V]): AvlTree[K, V] =
    chunks.foldLeft(initState) { case (state, chunk) =>
      state.insertionInFastSyncMod(chunk.nodesList.map(NodeSerilalizer.fromProto[K, V](_)))
    }

  @scala.annotation.tailrec
  private def createSubtrees(nodesToProcess: List[Node[K, V]], subtreeChunks: List[SnapshotChunk])
                            (implicit kSerializer: Serializer[K], vSerializer: Serializer[V]): List[SnapshotChunk] =
    if (nodesToProcess.nonEmpty) nodesToProcess.head match {
      case _: EmptyNode[K, V] => List.empty[SnapshotChunk]
      case s: ShadowNode[K, V] =>
        val restoredNode: Node[K, V] = s.restoreFullNode(storage)
        val removedExcess: List[Node[K, V]] = nodesToProcess.drop(1)
        val newToProcess: List[Node[K, V]] = restoredNode :: removedExcess
        createSubtrees(newToProcess, subtreeChunks)
      case i: InternalNode[K, V] =>
        val leftChild: Option[(List[Node[K, V]], Node[K, V])] = getLeftChild(i)
        val rightChild: Option[(List[Node[K, V]], Node[K, V])] = getRightChild(i)
        val (next: List[Node[K, V]], current: List[Node[K, V]]) = (leftChild :: rightChild :: Nil)
          .foldLeft(List.empty[Node[K, V]], List.empty[Node[K, V]]) {
            case ((nextNodes, thisNodes), Some((nextChildren, thisNode))) =>
              (nextChildren ::: nextNodes, thisNode :: thisNodes)
            case (result, _) => result
          }
        val subtree: SnapshotChunk = makeSubtree(i :: current)
        val updatedNodeToProcess: List[Node[K, V]] = nodesToProcess.drop(1)
        createSubtrees(updatedNodeToProcess ::: next, subtree :: subtreeChunks)
      case l: LeafNode[K, V] =>
        val subtree: SnapshotChunk = makeSubtree(List(l))
        createSubtrees(nodesToProcess.drop(1), subtree :: subtreeChunks)
    } else subtreeChunks

  @scala.annotation.tailrec
  private def handleChild(child: Node[K, V]): (List[Node[K, V]], Node[K, V]) = child match {
    case s: ShadowNode[K, V] => handleChild(s.restoreFullNode(storage))
    case i: InternalNode[K, V] => List(i.rightChild, i.leftChild).flatten -> i
    case l: LeafNode[K, V] => List.empty -> l
  }

  private def getLeftChild(node: InternalNode[K, V]): Option[(List[Node[K, V]], Node[K, V])] = node.leftChild match {
    case Some(child) => Some(handleChild(child))
    case n@None => n
  }

  private def getRightChild(node: InternalNode[K, V]): Option[(List[Node[K, V]], Node[K, V])] = node.rightChild match {
    case Some(child) => Some(handleChild(child))
    case n@None => n
  }

  private def makeSubtree(nodes: List[Node[K, V]])(implicit kSerializer: Serializer[K],
                                                   vSerializer: Serializer[V]): SnapshotChunk =
    SnapshotChunk(nodes.map(NodeSerilalizer.toProto(_)))

  override def close(): Unit = storage.close()

  override def toString: String = rootNode.toString
}

object AvlTree {

  def restore[K: Monoid : Serializer : Hashable : Order, V: Monoid : Serializer](storage: VersionalStorage): Try[AvlTree[K, V]] = Try {
    val rootNode = NodeSerilalizer.fromBytes[K, V](storage.get(AvlTree.rootNodeKey).get)
    AvlTree(rootNode, storage)
  }

  val rootNodeKey: StorageKey = StorageKey !@@ Algos.hash("root_node")

  sealed trait Direction

  object Directions {

    case object LEFT extends Direction

    case object RIGHT extends Direction

    case object EMPTY extends Direction

  }

  def apply[K: Serializer : Monoid : Order : Hashable, V: Serializer : Monoid](manifest: SnapshotManifest,
                                                                               storage: VersionalStorage): AvlTree[K, V] = {
    val root: Node[K, V] = NodeSerilalizer.fromProto(manifest.rootNodeBytes)
    val initTree: AvlTree[K, V] = new AvlTree(root, storage)
    initTree.insertionInFastSyncMod(List(root), isRoot = true)
  }

  def apply[K: Monoid : Order : Hashable, V: Monoid](storage: VersionalStorage): AvlTree[K, V] = new AvlTree[K, V](EmptyNode(), storage)
}