package com.github.kruton.apps.myfeederconfig.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

val SERVICE_LOCAL_SETUP: UUID = UUID.fromString("5fb90100-1178-45f0-a8cb-fd25b4f8d9dc")
val CHARACTERISTIC_LOCAL_CAPS: UUID = UUID.fromString("5fb90101-1178-45f0-a8cb-fd25b4f8d9dc")
val CHARACTERISTIC_LOCAL_CONTROL: UUID = UUID.fromString("5fb90102-1178-45f0-a8cb-fd25b4f8d9dc")

/** Local Setup v1. Integer CBOR only; secrets deliberately have no data-class toString. */
object LocalSetupCodec {
    const val MAX = 224

    fun supported(caps: ByteArray): Boolean =
        caps.size == 8 &&
            caps.copyOfRange(0, 4).contentEquals(byteArrayOf(80, 78, 76, 67)) &&
            caps[4].toInt() == 1 &&
            caps[5].toInt() and 31 == 31 &&
            ((caps[6].toInt() and 255) or ((caps[7].toInt() and 255) shl 8)) == MAX

    fun command(op: Int, revision: Long?, args: Map<Int, Any?>?): ByteArray =
        encode(listOf(op, revision, args))

    fun packets(id: Int, bytes: ByteArray): List<ByteArray> {
        require(id in 1..255 && bytes.isNotEmpty() && bytes.size <= MAX)
        return bytes.toList().chunked(17).mapIndexed { index, part ->
            val flags = (if (index == 0) 1 else 0) or (if ((index + 1) * 17 >= bytes.size) 2 else 0)
            byteArrayOf(flags.toByte(), id.toByte(), index.toByte()) + part.toByteArray()
        }
    }

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        fun head(major: Int, n: Long) {
            require(n >= 0 && n <= 0xffffffffL)
            when {
                n < 24 -> out.write((major shl 5) or n.toInt())
                n <= 255 -> {
                    out.write((major shl 5) or 24)
                    out.write(n.toInt())
                }
                n <= 65535 -> {
                    out.write((major shl 5) or 25)
                    out.write((n shr 8).toInt())
                    out.write(n.toInt())
                }
                else -> {
                    out.write((major shl 5) or 26)
                    for (i in 3 downTo 0) out.write((n shr (8 * i)).toInt())
                }
            }
        }
        fun emit(v: Any?, depth: Int) {
            require(depth <= 4)
            when (v) {
                null -> out.write(0xf6)
                is Boolean -> out.write(if (v) 0xf5 else 0xf4)
                is Int -> head(0, v.toLong())
                is Long -> head(0, v)
                is String -> {
                    val b = v.toByteArray(Charsets.UTF_8)
                    head(3, b.size.toLong())
                    out.write(b)
                }
                is List<*> -> {
                    head(4, v.size.toLong())
                    v.forEach { emit(it, depth + 1) }
                }
                is Map<*, *> -> {
                    head(5, v.size.toLong())
                    v.forEach { (k, x) ->
                        require(k is Int)
                        emit(k, depth + 1)
                        emit(x, depth + 1)
                    }
                }
                else -> throw IllegalArgumentException("Unsupported setup value")
            }
        }
        emit(value, 0)
        return out.toByteArray().also { require(it.size <= MAX) }
    }

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size <= MAX)
        var pos = 0
        fun byte(): Int {
            require(pos < bytes.size)
            return bytes[pos++].toInt() and 255
        }
        fun read(depth: Int): Any? {
            require(depth <= 4)
            val h = byte()
            if (h == 0xf4) return false
            if (h == 0xf5) return true
            if (h == 0xf6) return null
            val info = h and 31
            val n =
                when (info) {
                    in 0..23 -> info.toLong()
                    24 -> byte().toLong()
                    25 -> (byte().toLong() shl 8) or byte().toLong()
                    26 -> {
                        var x = 0L
                        repeat(4) { x = (x shl 8) or byte().toLong() }
                        x
                    }
                    else -> throw IllegalArgumentException("Invalid setup length")
                }
            return when (h shr 5) {
                0 -> n
                3 -> {
                    require(n <= MAX && pos + n <= bytes.size)
                    val s =
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes, pos, n.toInt()))
                            .toString()
                    pos += n.toInt()
                    s
                }
                4 -> {
                    require(n <= 16)
                    List(n.toInt()) { read(depth + 1) }
                }
                5 -> {
                    require(n <= 16)
                    val m = linkedMapOf<Long, Any?>()
                    repeat(n.toInt()) {
                        val k = read(depth + 1) as? Long ?: error("Invalid setup key")
                        require(!m.containsKey(k))
                        m[k] = read(depth + 1)
                    }
                    m
                }
                else -> throw IllegalArgumentException("Invalid setup type")
            }
        }
        return read(0).also { require(pos == bytes.size) }
    }

    class Receiver {
        private val buffer = ByteArray(MAX)
        private var used = 0
        private var next = 0
        private var id = 0

        fun reset() {
            buffer.fill(0)
            used = 0
            next = 0
            id = 0
        }

        fun accept(p: ByteArray, expected: Int): ByteArray? {
            try {
                require(p.size in 4..20 && p[0].toInt() and 0xfc == 0)
                val request = p[1].toInt() and 255
                if (request != expected) return null
                if (p[0].toInt() and 1 != 0) {
                    require(p[2].toInt() == 0)
                    reset()
                    id = request
                }
                require(id == request && p[2].toInt() and 255 == next && used + p.size - 3 <= MAX)
                p.copyInto(buffer, used, 3)
                used += p.size - 3
                next++
                if (p[0].toInt() and 2 != 0) return buffer.copyOf(used).also { reset() }
                return null
            } catch (e: Exception) {
                reset()
                throw e
            }
        }
    }
}
