package encry.avltree

import com.google.common.primitives.Ints
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.lang.ArrayUtils
import org.bouncycastle.util.Arrays
import org.encryfoundation.common.utils.TaggedTypes._
import scorex.crypto.hash.{Blake2b256, CryptographicHash, Digest}
import scorex.utils.ByteArray
import scala.annotation.tailrec
import scala.util.{Failure, Random, Success, Try}

class BatchAVLProver[D <: Digest, HF <: CryptographicHash[D]](val keyLength: Int,
                                                              val valueLengthOpt: Option[Int],
                                                              oldRootAndHeight: Option[(EncryProverNodes[D], Int)] = None,
                                                              val collectChangedNodes: Boolean = true)
                                                             (implicit val hf: HF = Blake2b256)
  extends AuthenticatedTreeOps[D] with StrictLogging {

  protected val labelLength: Int = hf.DigestSize

  var topNode: EncryProverNodes[D] =
    oldRootAndHeight.map(_._1).getOrElse({
      val t: EncryProverNodes[D] = new ProverLeaf(NegativeInfinityKey,
        ADValue @@ Array.fill(valueLengthOpt.getOrElse(0))(0: Byte), PositiveInfinityKey)
      t.isNew = false
      t
    })

  var rootNodeHeight: Int = oldRootAndHeight.map(_._2).getOrElse(0)

  private var oldTopNode = topNode

  private var directions = Array.emptyByteArray
  private var directionsBitLength: Int = 0

  private var replayIndex = 0
  private var lastRightStep = 0
  private var found: Boolean = false // keeps track of whether the key for the current

  protected def nextDirectionIsLeft(key: ADKey, r: InternalEncryNode[D]): Boolean = {
    val ret: Boolean = if (found) true
    else {
      ByteArray.compare(key, r.asInstanceOf[InternalProverEncryNode[D]].key) match {
        case 0 => // found in the tree -- go one step right, then left to the leaf
          found = true
          lastRightStep = directionsBitLength
          false
        case o if o < 0 => true // going left
        case _ => false // going right
      }
    }
    if ((directionsBitLength & 7) == 0) directions = ArrayUtils.add(directions, if (ret) 1: Byte else 0: Byte)
    else if (ret) {
      val i = directionsBitLength >> 3
      directions(i) = (directions(i) | (1 << (directionsBitLength & 7))).toByte // change last byte
    }
    directionsBitLength += 1
    ret
  }

  protected def keyMatchesLeaf(key: ADKey, r: EncryLeaf[D]): Boolean = {
    val ret: Boolean = found
    found = false // reset for next time
    ret
  }

  protected def replayComparison: Int = {
    val ret: Int = if (replayIndex == lastRightStep) 0
    else if ((directions(replayIndex >> 3) & (1 << (replayIndex & 7)).toByte) == 0) 1
    else -1
    replayIndex += 1
    ret
  }

  protected def addNode(r: EncryLeaf[D], key: ADKey, v: ADValue): InternalProverEncryNode[D] = {
    val n: ADKey = r.nextLeafKey
    new InternalProverEncryNode(key, r.getNew(newNextLeafKey = key).asInstanceOf[ProverLeaf[D]],
      new ProverLeaf(key, v, n), Balance @@ 0.toByte)
  }

  def digest: ADDigest = digest(topNode)

  def performOneOperation(operation: encry.avltree.Operation): Try[Option[ADValue]] = Try {
    replayIndex = directionsBitLength
    returnResultOfOneOperation(operation, topNode) match {
      case Success(n) =>
        topNode = n._1.asInstanceOf[EncryProverNodes[D]]
        n._2
      case Failure(e) =>
        val oldDirectionsByteLength: Int = (replayIndex + 7) / 8
        directions = ArrayUtils.subarray(directions, 0,oldDirectionsByteLength)
        directionsBitLength = replayIndex
        if ((directionsBitLength & 7) > 0) {
          val mask: Int = (1 << (directionsBitLength & 7)) - 1
          directions(directions.length - 1) = (directions(directions.length - 1) & mask).toByte
        }
        throw e
    }
  }

  def removedNodes(): List[EncryProverNodes[D]] = {
    changedNodesBufferToCheck.foreach { cn =>
      if (!contains(cn)) changedNodesBuffer += cn
    }
    changedNodesBuffer.toList
  }

  def contains(node: EncryProverNodes[D]): Boolean = contains(node.key, node.label)

  def contains(key: ADKey, label: D): Boolean = {
    @tailrec
    def loop(currentNode: EncryProverNodes[D], keyFound: Boolean): Boolean = {
      currentNode match {
        case _ if currentNode.label sameElements label => true
        case r: InternalProverEncryNode[D] =>
          if (keyFound) loop(r.left, keyFound = true)
          else ByteArray.compare(key, r.key) match {
            case 0 => loop(r.right, keyFound = true) // found in the tree -- go one step right, then left to the leaf
            case o if o < 0 => loop(r.left, keyFound = false) // going left, not yet found
            case _ => loop(r.right, keyFound = false) // going right, not yet found
          }
        case _ => false
      }
    }

    loop(topNode, keyFound = false)
  }

  def generateProofForOperations(operations: Seq[encry.avltree.Operation]): Try[(SerializedAdProof, ADDigest)] = Try {
    val newProver: BatchAVLProver[D, HF] =
      new BatchAVLProver[D, HF](keyLength, valueLengthOpt, Some(topNode, rootNodeHeight), collectChangedNodes = false)
    operations.foreach(o => newProver.performOneOperation(o).get)
    (newProver.generateProof(), newProver.digest)
  }

  def generateProof(): SerializedAdProof = {
    //here
    changedNodesBuffer.clear()
    changedNodesBufferToCheck.clear()
    var packagedTree = Array.emptyByteArray
    var previousLeafAvailable: Boolean = false

    def packTree(rNode: EncryProverNodes[D]): Unit = {
      if (!rNode.visited) {
        packagedTree = ArrayUtils.add(packagedTree, LabelInPackagedProof)
        packagedTree = ArrayUtils.addAll(packagedTree, rNode.label)
        assert(rNode.label.length == labelLength)
        previousLeafAvailable = false
      } else {
        rNode.visited = false
        rNode match {
          case r: ProverLeaf[D] =>
            packagedTree = ArrayUtils.add(packagedTree, LeafInPackagedProof)
            if (!previousLeafAvailable) packagedTree = ArrayUtils.addAll(packagedTree, r.key)
            packagedTree = ArrayUtils.addAll(packagedTree, r.nextLeafKey)
            if (valueLengthOpt.isEmpty) packagedTree = ArrayUtils.addAll(packagedTree, Ints.toByteArray(r.value.length))
            packagedTree = ArrayUtils.addAll(packagedTree, r.value)
            previousLeafAvailable = true
          case r: InternalProverEncryNode[D] =>
            packTree(r.left)
            packTree(r.right)
            packagedTree = ArrayUtils.add(packagedTree, r.balance)
        }
      }
    }

    def resetNew(r: EncryProverNodes[D]): Unit = if (r.isNew) {
      r match {
        case rn: InternalProverEncryNode[D] =>
          resetNew(rn.left)
          resetNew(rn.right)
        case _ =>
      }
      r.isNew = false
      r.visited = false
    }

    logger.debug(s"\n\nStarting to PACK TREE!!")
    val startTime = System.currentTimeMillis()
    packTree(oldTopNode)
    logger.debug(s"\n\nFinishing to PACK TREE. Process time is: ${System.currentTimeMillis() - startTime}")
    logger.debug(s"\n\nStarting to EndOfTreeInPackagedProof!!")
    val startTime1 = System.currentTimeMillis()
    packagedTree = ArrayUtils.add(packagedTree, EndOfTreeInPackagedProof)
    logger.debug(s"\n\nFinishing to EndOfTreeInPackagedProof. Process time is: ${System.currentTimeMillis() - startTime1}")
    logger.debug(s"\n\nStarting to directions!!")
    val startTime2 = System.currentTimeMillis()
    packagedTree = ArrayUtils.addAll(packagedTree, directions)
    logger.debug(s"\n\nFinishing to directions. Process time is: ${System.currentTimeMillis() - startTime2}")

    logger.debug(s"\n\nStarting to resetNew!!")
    val startTime3 = System.currentTimeMillis()
    resetNew(topNode)
    logger.debug(s"\n\nFinishing to resetNew. Process time is: ${System.currentTimeMillis() - startTime3}")
    directions = Array.emptyByteArray
    directionsBitLength = 0
    oldTopNode = topNode

    SerializedAdProof @@ packagedTree
  }

  def treeWalk[IR, LR](internalNodeFn: (InternalProverEncryNode[D], IR) => (EncryProverNodes[D], IR),
                       leafFn: (ProverLeaf[D], IR) => LR,
                       initial: IR): LR = {
    def walk(rNode: EncryProverNodes[D], ir: IR): LR = rNode match {
      case leaf: ProverLeaf[D] => leafFn(leaf, ir)
      case r: InternalProverEncryNode[D] =>
        val i: (EncryProverNodes[D], IR) = internalNodeFn(r, ir)
        walk(i._1, i._2)
    }

    walk(topNode, initial)
  }

  def randomWalk(rand: Random = new Random): Option[(ADKey, ADValue)] = {
    def internalNodeFn(r: InternalProverEncryNode[D], dummy: Unit.type): (EncryProverNodes[D], Unit.type) =
      if (rand.nextBoolean()) (r.right, Unit) else (r.left, Unit)

    def leafFn(leaf: ProverLeaf[D], dummy: Unit.type): Option[(ADKey, ADValue)] =
      if (leaf.key sameElements PositiveInfinityKey) None
      else if (leaf.key sameElements NegativeInfinityKey) None
      else Some(leaf.key -> leaf.value)

    treeWalk(internalNodeFn, leafFn, Unit)
  }

  def unauthenticatedLookup(key: ADKey): Option[ADValue] = {
    def internalNodeFn(r: InternalProverEncryNode[D], found: Boolean): (EncryProverNodes[D], Boolean) =
      if (found) (r.left, true)
      else Arrays.compareUnsigned(key, r.key) match {
        case 0 => (r.right, true) // found in the tree -- go one step right, then left to the leaf
        case o if o < 0 => (r.left, false) // going left, not yet found
        case _ => (r.right, false) // going right, not yet found
      }

    def leafFn(leaf: ProverLeaf[D], found: Boolean): Option[ADValue] = if (found) Some(leaf.value) else None

    treeWalk(internalNodeFn, leafFn, false)
  }

  private[avltree] def checkTree(postProof: Boolean = false): Unit = {
    var fail: Boolean = false

    def checkTreeHelper(rNode: EncryProverNodes[D]): (ProverLeaf[D], ProverLeaf[D], Int) = {
      def myRequire(t: Boolean, s: String): Unit = if (!t) {
        var x: Int = rNode.key(0).toInt
        if (x < 0) x = x + 256
        logger.error("Tree failed at key = " + x + ": " + s)
        fail = true
      }

      myRequire(!postProof || (!rNode.visited && !rNode.isNew), "postproof flags")
      rNode match {
        case r: InternalProverEncryNode[D] =>
          if (r.left.isInstanceOf[InternalProverEncryNode[D]])
            myRequire(ByteArray.compare(r.left.key, r.key) < 0, "wrong left key")
          if (r.right.isInstanceOf[InternalProverEncryNode[D]])
            myRequire(ByteArray.compare(r.right.key, r.key) > 0, "wrong right key")
          val (minLeft: ProverLeaf[D], maxLeft: ProverLeaf[D], leftHeight: Int) = checkTreeHelper(r.left)
          val (minRight: ProverLeaf[D], maxRight: ProverLeaf[D], rightHeight: Int) = checkTreeHelper(r.right)
          myRequire(maxLeft.nextLeafKey sameElements minRight.key, "children don't match")
          myRequire(minRight.key sameElements r.key, "min of right subtree doesn't match")
          myRequire(r.balance >= -1 && r.balance <= 1 && r.balance.toInt == rightHeight - leftHeight, "wrong balance")
          val height: Int = math.max(leftHeight, rightHeight) + 1
          (minLeft, maxRight, height)
        case l: ProverLeaf[D] => (l, l, 0)
      }
    }

    val (minTree: ProverLeaf[D], maxTree: ProverLeaf[D], treeHeight: Int) = checkTreeHelper(topNode)
    require(minTree.key sameElements NegativeInfinityKey)
    require(maxTree.nextLeafKey sameElements PositiveInfinityKey)
    require(treeHeight == rootNodeHeight)
    require(!fail, "Tree failed: \n" + toString)
  }

  override def toString: String = {

    def stringTreeHelper(rNode: EncryProverNodes[D], depth: Int): String = {
      Seq.fill(depth + 2)(" ").mkString + (rNode match {
        case leaf: ProverLeaf[D] =>
          "At leaf label = " + leaf.labelOpt.map(arrayToString) + " key = " + arrayToString(leaf.key) +
            " nextLeafKey = " + arrayToString(leaf.nextLeafKey) + "\n"
        case r: InternalProverEncryNode[D] =>
          "Internal node label = " + r.labelOpt.map(arrayToString) + " key = " + arrayToString(r.key) + " balance = " +
            r.balance + "\n" + stringTreeHelper(r.left, depth + 1) +
            stringTreeHelper(r.right, depth + 1)
      })
    }

    stringTreeHelper(topNode, 0)
  }
}
