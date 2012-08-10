package com.twitter.finagle.mysql.util

object BufferUtil {
  /**
   * Helper method to do a hex dump of a sequence of bytes.
   */
  def hex(data: Seq[Byte], output: String = ""): String = {
    val (begin,end) = data.splitAt(16)
    val hexline = begin.map { "%02X".format(_) } mkString(" ")
    val charline = begin.map { b => if (0x20 <= b && b <= 0x7E) b.toChar else " " } mkString("")
    val line = "%-47s".format(hexline) + "     " + charline
    if (end.isEmpty)
      output + line + "\n"
    else
      hex(end, output + line + "\n")
  }
}