package com.github.plokhotnyuk.jsoniter_scala

import java.io.{IOException, OutputStream}

import com.github.plokhotnyuk.jsoniter_scala.JsonWriter._

import scala.annotation.switch
import scala.collection.breakOut

case class WriterConfig(
  indentionStep: Int = 0,
  escapeUnicode: Boolean = false)

final class JsonWriter private[jsoniter_scala](
    private var buf: Array[Byte] = new Array[Byte](4096),
    private var count: Int = 0,
    private var indention: Int = 0,
    private var out: OutputStream = null,
    private var isBufGrowingAllowed: Boolean = true,
    private var config: WriterConfig = WriterConfig()) {
  def writeComma(comma: Boolean): Boolean = {
    if (comma) ensureAndWrite(',')
    writeIndention(0)
    true
  }

  def writeObjectField(comma: Boolean, x: Boolean): Boolean = {
    writeCommaWithParentheses(comma)
    writeVal(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Byte): Boolean = {
    writeCommaWithParentheses(comma)
    writeInt(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Char): Boolean = {
    writeComma(comma)
    writeChar(x)
    writeColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Short): Boolean = {
    writeCommaWithParentheses(comma)
    writeInt(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Int): Boolean = {
    writeCommaWithParentheses(comma)
    writeInt(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Long): Boolean = {
    writeCommaWithParentheses(comma)
    writeLong(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Float): Boolean = {
    writeCommaWithParentheses(comma)
    writeFloat(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: Double): Boolean = {
    writeCommaWithParentheses(comma)
    writeDouble(x)
    writeParenthesesWithColon()
    true
  }

  def writeObjectField(comma: Boolean, x: BigInt): Boolean =
    if (x ne null) {
      writeCommaWithParentheses(comma)
      writeAsciiString(x.toString)
      writeParenthesesWithColon()
      true
    } else encodeError("key cannot be null")

  def writeObjectField(comma: Boolean, x: BigDecimal): Boolean =
    if (x ne null) {
      writeCommaWithParentheses(comma)
      writeAsciiString(x.toString)
      writeParenthesesWithColon()
      true
    } else encodeError("key cannot be null")

  def writeObjectField(comma: Boolean, x: String): Boolean =
    if (x ne null) {
      writeComma(comma)
      writeString(x, 0, x.length)
      writeColon()
      true
    } else encodeError("key cannot be null")

  def encodeError(msg: String): Nothing = throw new IOException(msg)

  def writeVal(x: BigDecimal): Unit = if (x eq null) writeNull() else writeAsciiString(x.toString)

  def writeVal(x: BigInt): Unit = if (x eq null) writeNull() else writeAsciiString(x.toString)

  def writeVal(x: String): Unit = if (x eq null) writeNull() else writeString(x, 0, x.length)

  def writeVal(x: Boolean): Unit =
    if (x) ensureAndWrite('t'.toByte, 'r'.toByte, 'u'.toByte, 'e'.toByte)
    else ensureAndWrite('f'.toByte, 'a'.toByte, 'l'.toByte, 's'.toByte, 'e'.toByte)

  def writeVal(x: Byte): Unit = writeInt(x.toInt)

  def writeVal(x: Short): Unit = writeInt(x.toInt)

  def writeVal(x: Char): Unit = writeChar(x)

  def writeVal(x: Int): Unit = writeInt(x)

  def writeVal(x: Long): Unit = writeLong(x)

  def writeVal(x: Float): Unit = writeFloat(x)

  def writeVal(x: Double): Unit = writeDouble(x)

  def writeNull(): Unit = ensureAndWrite('n'.toByte, 'u'.toByte, 'l'.toByte, 'l'.toByte)

  def writeArrayStart(): Unit = {
    indention += config.indentionStep
    ensureAndWrite('[')
  }

  def writeArrayEnd(): Unit = {
    val indentionStep = config.indentionStep
    writeIndention(indentionStep)
    indention -= indentionStep
    ensureAndWrite(']')
  }

  def writeObjectStart(): Unit = {
    indention += config.indentionStep
    ensureAndWrite('{')
  }

  def writeObjectEnd(): Unit = {
    val indentionStep = config.indentionStep
    writeIndention(indentionStep)
    indention -= indentionStep
    ensureAndWrite('}')
  }

  private def ensureAndWrite(b: Byte): Unit = {
    val pos = ensure(1)
    count = write(b, pos)
  }

  private def ensureAndWrite(b1: Byte, b2: Byte): Unit = {
    val pos = ensure(2)
    count = write(b1, b2, pos)
  }

  private def ensureAndWrite(b1: Byte, b2: Byte, b3: Byte): Unit = {
    val pos = ensure(3)
    count = write(b1, b2, b3, pos)
  }

  private def ensureAndWrite(b1: Byte, b2: Byte, b3: Byte, b4: Byte): Unit = {
    val pos = ensure(4)
    count = write(b1, b2, b3, b4, pos)
  }

  private def ensureAndWrite(b1: Byte, b2: Byte, b3: Byte, b4: Byte, b5: Byte): Unit = {
    val pos = ensure(5)
    count = write(b1, b2, b3, b4, b5, pos)
  }

  private def writeAsciiString(s: String): Unit = count = {
    val len = s.length
    val pos = ensure(len)
    s.getBytes(0, len, buf, pos)
    pos + len
  }

  private def writeString(s: String, from: Int, to: Int): Unit = count = {
    var pos = write('"', ensure((to - from) + 2)) // 1 byte per char (suppose that they are ASCII only) + make room for the quotes
    var i = from
    var ch: Char = 0 // the fast path without utf8 and escape support
    while (i < to && {
      ch = s.charAt(i)
      ch > 31 && ch < 128 && ch != '"' && ch != '\\'
    }) pos = {
      i += 1
      write(ch.toByte, pos)
    }
    if (i == to) write('"', pos)
    else { // for the remaining parts we process with utf-8 encoding and escape unicode support
      count = pos
      writeStringSlowPath(s, i, to)
    }
  }

  private def writeStringSlowPath(s: String, from: Int, to: Int): Int = {
    var pos = ensure((to - from) * (if (config.escapeUnicode) 6 else 3) + 1) // max 6 or 3 bytes per char + the closing quotes
    var i = from
    while (i < to) pos = {
      val ch1 = s.charAt(i)
      i += 1
      if (ch1 < 128) writeAscii(ch1, pos) // 1 byte, 7 bits: 0xxxxxxx
      else if (config.escapeUnicode) {
        if (ch1 < 2048 || !Character.isHighSurrogate(ch1)) {
          if (Character.isLowSurrogate(ch1)) illegalSurrogateError()
          writeEscapedUnicode(ch1, pos)
        } else if (i < to) {
          val ch2 = s.charAt(i)
          i += 1
          if (!Character.isLowSurrogate(ch2)) illegalSurrogateError()
          writeEscapedUnicode(ch2, writeEscapedUnicode(ch1, pos))
        } else illegalSurrogateError()
      } else if (ch1 < 2048) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
        write((0xC0 | (ch1 >> 6)).toByte, (0x80 | (ch1 & 0x3F)).toByte, pos)
      } else if (!Character.isHighSurrogate(ch1)) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
        if (Character.isLowSurrogate(ch1)) illegalSurrogateError()
        write((0xE0 | (ch1 >> 12)).toByte, (0x80 | ((ch1 >> 6) & 0x3F)).toByte, (0x80 | (ch1 & 0x3F)).toByte, pos)
      } else if (i < to) { // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
        val ch2 = s.charAt(i)
        i += 1
        if (!Character.isLowSurrogate(ch2)) illegalSurrogateError()
        val cp = Character.toCodePoint(ch1, ch2)
        write((0xF0 | (cp >> 18)).toByte, (0x80 | ((cp >> 12) & 0x3F)).toByte, (0x80 | ((cp >> 6) & 0x3F)).toByte, (0x80 | (cp & 0x3F)).toByte, pos)
      } else illegalSurrogateError()
    }
    write('"', pos)
  }

  private def writeChar(ch: Char): Unit = count = {
    var pos = write('"', ensure(8)) // 6 bytes per char for encoded unicode + make room for the quotes
    pos = {
      if (ch < 128) writeAscii(ch, pos) // 1 byte, 7 bits: 0xxxxxxx
      else if (config.escapeUnicode) {
        if (Character.isSurrogate(ch)) illegalSurrogateError()
        writeEscapedUnicode(ch, pos)
      } else if (ch < 2048) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
        write((0xC0 | (ch >> 6)).toByte, (0x80 | (ch & 0x3F)).toByte, pos)
      } else if (!Character.isSurrogate(ch)) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
        write((0xE0 | (ch >> 12)).toByte, (0x80 | ((ch >> 6) & 0x3F)).toByte, (0x80 | (ch & 0x3F)).toByte, pos)
      } else illegalSurrogateError()
    }
    buf(pos) = '"'
    pos + 1
  }

  private def writeAscii(ch: Char, pos: Int) = (ch: @switch) match {
    case '"' => write('\\', '"', pos)
    case '\\' => write('\\', '\\', pos)
    case '\b' => write('\\', 'b', pos)
    case '\f' => write('\\', 'f', pos)
    case '\n' => write('\\', 'n', pos)
    case '\r' => write('\\', 'r', pos)
    case '\t' => write('\\', 't', pos)
    case _ => if (config.escapeUnicode && (ch < 32 || ch > 126)) writeEscapedUnicode(ch, pos) else write(ch.toByte, pos)
  }

  private def writeEscapedUnicode(ch: Char, pos: Int) = {
    buf(pos) = '\\'.toByte
    buf(pos + 1) = 'u'.toByte
    buf(pos + 2) = toHexDigit(ch >>> 12)
    buf(pos + 3) = toHexDigit(ch >>> 8)
    buf(pos + 4) = toHexDigit(ch >>> 4)
    buf(pos + 5) = toHexDigit(ch)
    pos + 6
  }

  private def toHexDigit(n: Int): Byte = {
    val nibble = n & 15
    (((9 - nibble) >> 31) & 39) + nibble + 48 // branchless conversion of nibble to hex digit
  }.toByte

  private def illegalSurrogateError(): Nothing = encodeError("illegal char sequence of surrogate pair")

  private def writeCommaWithParentheses(comma: Boolean): Unit = {
    if (comma) ensureAndWrite(',')
    writeIndention(0)
    ensureAndWrite('"')
  }

  private def writeParenthesesWithColon(): Unit =
    if (config.indentionStep > 0) ensureAndWrite('"'.toByte, ':'.toByte, ' '.toByte)
    else ensureAndWrite('"'.toByte, ':'.toByte)

  private def writeColon(): Unit =
    if (config.indentionStep > 0) ensureAndWrite(':'.toByte, ' '.toByte)
    else ensureAndWrite(':'.toByte)

  private def writeInt(x: Int): Unit = count = {
    var pos = ensure(11) // minIntBytes.length
    if (x == Integer.MIN_VALUE) writeBytes(minIntBytes, pos)
    else {
      val q0 =
        if (x >= 0) x
        else {
          pos = write('-', pos)
          -x
        }
      val q1 = q0 / 1000
      if (q1 == 0) writeFirstRem(q0, pos)
      else {
        val r1 = q0 - q1 * 1000
        val q2 = q1 / 1000
        if (q2 == 0) writeRem(r1, writeFirstRem(q1, pos))
        else {
          val r2 = q1 - q2 * 1000
          val q3 = q2 / 1000
          writeRem(r1, writeRem(r2, {
            if (q3 == 0) writeFirstRem(q2, pos)
            else {
              val r3 = q2 - q3 * 1000
              writeRem(r3, write((q3 + '0').toByte, pos))
            }
          }))
        }
      }
    }
  }

  // TODO: consider more cache-aware algorithm from RapidJSON, see https://github.com/miloyip/itoa-benchmark/blob/master/src/branchlut.cpp
  private def writeLong(x: Long): Unit = count = {
    var pos = ensure(20) // minLongBytes.length
    if (x == java.lang.Long.MIN_VALUE) writeBytes(minLongBytes, pos)
    else {
      val q0 =
        if (x >= 0) x
        else {
          pos = write('-', pos)
          -x
        }
      val q1 = q0 / 1000
      if (q1 == 0) writeFirstRem(q0.toInt, pos)
      else {
        val r1 = (q0 - q1 * 1000).toInt
        val q2 = q1 / 1000
        if (q2 == 0) writeRem(r1, writeFirstRem(q1.toInt, pos))
        else {
          val r2 = (q1 - q2 * 1000).toInt
          val q3 = q2 / 1000
          if (q3 == 0) writeRem(r1, writeRem(r2, writeFirstRem(q2.toInt, pos)))
          else {
            val r3 = (q2 - q3 * 1000).toInt
            val q4 = (q3 / 1000).toInt
            if (q4 == 0) writeRem(r1, writeRem(r2, writeRem(r3, writeFirstRem(q3.toInt, pos))))
            else {
              val r4 = (q3 - q4 * 1000).toInt
              val q5 = q4 / 1000
              if (q5 == 0) writeRem(r1, writeRem(r2, writeRem(r3, writeRem(r4, writeFirstRem(q4, pos)))))
              else {
                val r5 = q4 - q5 * 1000
                val q6 = q5 / 1000
                writeRem(r1, writeRem(r2, writeRem(r3, writeRem(r4, writeRem(r5, {
                  if (q6 == 0) writeFirstRem(q5, pos)
                  else {
                    val r6 = q5 - q6 * 1000
                    writeRem(r6, write((q6 + '0').toByte, pos))
                  }
                })))))
              }
            }
          }
        }
      }
    }
  }

  private def writeBytes(bs: Array[Byte], pos: Int): Int = {
    System.arraycopy(bs, 0, buf, pos, bs.length)
    pos + bs.length
  }

  private def writeFirstRem(r: Int, pos: Int) = {
    val d = digits(r)
    val skip = d >> 12
    if (skip == 0) write(((d >> 8) & 15 | '0').toByte, ((d >> 4) & 15 | '0').toByte, (d & 15 | '0').toByte, pos)
    else if (skip == 1) write(((d >> 4) & 15 | '0').toByte, (d & 15 | '0').toByte, pos)
    else write((d & 15 | '0').toByte, pos)
  }

  private def writeRem(r: Int, pos: Int) = {
    val d = digits(r)
    write(((d >> 8) & 15 | '0').toByte, ((d >> 4) & 15 | '0').toByte, (d & 15 | '0').toByte, pos)
  }

  private def writeFloat(x: Float): Unit =
    if (java.lang.Float.isFinite(x)) writeAsciiString(java.lang.Float.toString(x))
    else encodeError("illegal number: " + x)

  // TODO: use more efficient algorithm from RapidJSON, see https://github.com/miloyip/dtoa-benchmark
  private def writeDouble(x: Double): Unit =
    if (java.lang.Double.isFinite(x)) writeAsciiString(java.lang.Double.toString(x))
    else encodeError("illegal number: " + x)

  @inline
  private def writeIndention(delta: Int): Unit = if (indention != 0) writeNewLineAndSpaces(delta)

  private def writeNewLineAndSpaces(delta: Int): Unit = count = {
    val toWrite = indention - delta
    var pos = write('\n', ensure(toWrite + 1))
    val to = pos + toWrite
    while (pos < to) pos = write(' ', pos)
    pos
  }

  @inline
  private def write(b: Byte, pos: Int): Int = {
    buf(pos) = b
    pos + 1
  }

  @inline
  private def write(b1: Byte, b2: Byte, pos: Int): Int = {
    buf(pos) = b1
    buf(pos + 1) = b2
    pos + 2
  }

  @inline
  private def write(b1: Byte, b2: Byte, b3: Byte, pos: Int): Int = {
    buf(pos) = b1
    buf(pos + 1) = b2
    buf(pos + 2) = b3
    pos + 3
  }

  @inline
  private def write(b1: Byte, b2: Byte, b3: Byte, b4: Byte, pos: Int): Int = {
    buf(pos) = b1
    buf(pos + 1) = b2
    buf(pos + 2) = b3
    buf(pos + 3) = b4
    pos + 4
  }

  @inline
  private def write(b1: Byte, b2: Byte, b3: Byte, b4: Byte, b5: Byte, pos: Int): Int = {
    buf(pos) = b1
    buf(pos + 1) = b2
    buf(pos + 2) = b3
    buf(pos + 3) = b4
    buf(pos + 4) = b5
    pos + 5
  }

  @inline
  private def ensure(required: Int): Int = {
    if (buf.length < count + required) growBuffer(required)
    count
  }

  private def growBuffer(required: Int): Unit = {
    flushBuffer()
    if (buf.length < count + required) {
      if (isBufGrowingAllowed) {
        val newBuf = new Array[Byte](Math.max(buf.length << 1, count + required))
        System.arraycopy(buf, 0, newBuf, 0, buf.length)
        buf = newBuf
      } else throw new IOException("buf is overflown")
    }
  }

  private[jsoniter_scala] def flushBuffer(): Unit = if (out ne null) {
    out.write(buf, 0, count)
    count = 0
  }
}

object JsonWriter {
  private val pool: ThreadLocal[JsonWriter] = new ThreadLocal[JsonWriter] {
    override def initialValue(): JsonWriter = new JsonWriter()

    override def get(): JsonWriter = {
      val writer = super.get()
      if (writer.buf.length > 16384) writer.buf = new Array[Byte](16384)
      writer
    }
  }
  private val digits: Array[Short] = (0 to 999).map { i =>
    (((if (i < 10) 2 else if (i < 100) 1 else 0) << 12) + // this nibble encodes number of leading zeroes
      ((i / 100) << 8) + (((i / 10) % 10) << 4) + i % 10).toShort // decimal digit per nibble
  }(breakOut)
  private val minIntBytes: Array[Byte] = "-2147483648".getBytes
  private val minLongBytes: Array[Byte] = "-9223372036854775808".getBytes

  final def write[A](codec: JsonCodec[A], obj: A, out: OutputStream): Unit =
    write(codec, obj, out, null)

  final def write[A](codec: JsonCodec[A], obj: A, out: OutputStream, config: WriterConfig): Unit = {
    if (codec eq null) throw new IOException("codec should be not null")
    if (out eq null) throw new IOException("out should be not null")
    val writer = pool.get
    val currConfig = writer.config
    if (config ne null) writer.config = config
    writer.out = out
    writer.count = 0
    writer.indention = 0
    try codec.encode(obj, writer)
    finally {
      writer.config = currConfig
      writer.flushBuffer()
      writer.out = null // do not close output stream, just help GC instead
    }
  }

  final def write[A](codec: JsonCodec[A], obj: A): Array[Byte] =
    write(codec, obj, null.asInstanceOf[WriterConfig])

  final def write[A](codec: JsonCodec[A], obj: A, config: WriterConfig): Array[Byte] = {
    if (codec eq null) throw new IOException("codec should be not null")
    val writer = pool.get
    val currConfig = writer.config
    if (config ne null) writer.config = config
    writer.count = 0
    writer.indention = 0
    try codec.encode(obj, writer)
    finally writer.config = currConfig
    val arr = new Array[Byte](writer.count)
    System.arraycopy(writer.buf, 0, arr, 0, arr.length)
    arr
  }

  final def write[A](codec: JsonCodec[A], obj: A, buf: Array[Byte], from: Int): Int =
    write(codec, obj, buf, from, null)

  final def write[A](codec: JsonCodec[A], obj: A, buf: Array[Byte], from: Int, config: WriterConfig): Int = {
    if (codec eq null) throw new IOException("codec should be not null")
    if ((buf eq null) || buf.length == 0) throw new IOException("buf should be non empty")
    if (from < 0 || from > buf.length) throw new IOException("from should be positive and not greater than buf length")
    val writer = pool.get
    val currConfig = writer.config
    val currBuf = writer.buf
    if (config ne null) writer.config = config
    writer.buf = buf
    writer.count = from
    writer.indention = 0
    writer.isBufGrowingAllowed = false
    try {
      codec.encode(obj, writer)
      writer.count
    } finally {
      writer.config = currConfig
      writer.buf = currBuf
      writer.isBufGrowingAllowed = true
    }
  }
}