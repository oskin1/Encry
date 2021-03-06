package encry.consensus

import org.encryfoundation.common.modifiers.history.Header
import org.encryfoundation.common.utils.TaggedTypes.{Difficulty, Height}
import supertagged.@@

import scala.concurrent.duration.FiniteDuration

object PowLinearController {

  val PrecisionConstant: Int = 1000000000

  def getDifficulty(previousHeaders: Seq[(Int, Header)], epochLength: Int, desiredBlockInterval: FiniteDuration,
                    initialDifficulty: Difficulty): Difficulty =
    if (previousHeaders.lengthCompare(1) == 0 || previousHeaders.head._2.timestamp >= previousHeaders.last._2.timestamp)
      previousHeaders.head._2.difficulty
    else {
      val data: Seq[(Int, Difficulty)] = previousHeaders.sliding(2).toList.map { d =>
        val start: (Int, Header) = d.head
        val end: (Int, Header) = d.last
        require(end._1 - start._1 == epochLength, s"Incorrect heights interval for $d")
        val diff: @@[BigInt, Difficulty.Tag] = Difficulty @@ (end._2.requiredDifficulty * desiredBlockInterval.toMillis *
          epochLength / (end._2.timestamp - start._2.timestamp))
        (end._1, diff)
      }
      val diff: Difficulty = interpolate(data, epochLength)
      if (diff >= initialDifficulty) diff else initialDifficulty
    }

  /** Used to provide `getDifficulty()` with the sequence of headers of correct heights. */
  def getHeightsForRetargetingAt(height: Height, epochLength: Int, retargetingEpochsQty: Int): Seq[Height] = {
    if ((height - 1) % epochLength == 0 && height > epochLength * retargetingEpochsQty)
      (0 to retargetingEpochsQty).reverse.map(i => (height - 1) - i * epochLength)
    else Seq(height - 1)
  }.map(i => Height @@ i)

  // y = a + bx
  private def interpolate(data: Seq[(Int, Difficulty)], epochLength: Int): Difficulty = {
    val size: Int = data.size
    val xy: Iterable[BigInt] = data.map(d => d._1 * d._2)
    val x: Iterable[BigInt] = data.map(d => BigInt(d._1))
    val x2: Iterable[BigInt] = data.map(d => BigInt(d._1) * d._1)
    val y: Iterable[BigInt] = data.map(d => d._2)
    val xySum: BigInt = xy.sum
    val x2Sum: BigInt = x2.sum
    val ySum: BigInt = y.sum
    val xSum: BigInt = x.sum

    val k: BigInt = (xySum * size - xSum * ySum) * PrecisionConstant / (x2Sum * size - xSum * xSum)
    val b: BigInt = (ySum * PrecisionConstant - k * xSum) / size / PrecisionConstant

    val point: Int = data.map(_._1).max + epochLength
    Difficulty @@ (b + k * point / PrecisionConstant)
  }
}
