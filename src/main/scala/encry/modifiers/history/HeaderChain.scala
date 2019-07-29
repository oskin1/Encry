package encry.modifiers.history

import org.encryfoundation.common.modifiers.history.Header

case class HeaderChain(headers: IndexedSeq[Header]) {

  headers.indices.foreach(i => if (i > 0) require(headers(i).parentId.sameElements(headers(i - 1).id)))

  def exists(f: Header => Boolean): Boolean = headers.exists(f)

  //todo remove .head
  def head: Header = headers.head

  def headOption: Option[Header] = headers.headOption

  def tail: HeaderChain = HeaderChain(headers.drop(1))

  //todo remove .take
  def take(i: Int): HeaderChain = HeaderChain(headers.take(i))

  def takeAfter(h: Header): HeaderChain = {
    val commonIndex = headers.indexWhere(_.id.sameElements(h.id))
    val commonBlockThenSuffixes = headers.takeRight(headers.length - commonIndex)
    HeaderChain(commonBlockThenSuffixes)
  }

  val length: Int = headers.size

  def ++(c: HeaderChain): HeaderChain = HeaderChain(headers ++ c.headers)
}

object HeaderChain {

  def empty: HeaderChain = HeaderChain(IndexedSeq.empty[Header])

  def apply(seq: Seq[Header]): HeaderChain = HeaderChain(seq.toIndexedSeq)
}