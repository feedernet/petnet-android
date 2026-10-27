package com.github.kruton.apps.myfeederconfig.util

import org.junit.Assert.*
import org.junit.Test

class LocalSetupCodecTest {
    @Test
    fun sharedFirmwareVectors() {
        val rows =
            javaClass
                .getResourceAsStream("/local_setup_v1.tsv")!!
                .bufferedReader()
                .readLines()
                .associate {
                    val pieces = it.split('\t')
                    pieces[0] to
                        pieces[1].chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray()
                }
        val args =
            linkedMapOf<Int, Any?>(
                0 to "broker.lan",
                1 to 1883,
                2 to "u",
                3 to "p",
                4 to 1,
                5 to true,
            )
        assertArrayEquals(rows["save"], LocalSetupCodec.command(1, 7, args))
        assertArrayEquals(rows["http"], LocalSetupCodec.command(2, 7, mapOf(4 to 0)))
        assertArrayEquals(rows["web"], LocalSetupCodec.command(3, null, null))
        assertArrayEquals(rows["resume"], LocalSetupCodec.command(4, null, null))
        val snapshot = LocalSetupCodec.decode(rows["snapshot"]!!) as List<*>
        val values = snapshot[2] as Map<*, *>
        assertEquals(7L, snapshot[1])
        assertEquals(1L, values[4L])
        assertEquals(0L, values[5L])
        assertEquals(true, values[3L])
    }

    @Test
    fun goldenGetAndCapability() {
        assertArrayEquals(
            byteArrayOf(0x83.toByte(), 0, 0xf6.toByte(), 0xf6.toByte()),
            LocalSetupCodec.command(0, null, null),
        )
        assertArrayEquals(
            byteArrayOf(3, 7, 0, 0x83.toByte(), 0, 0xf6.toByte(), 0xf6.toByte()),
            LocalSetupCodec.packets(7, LocalSetupCodec.command(0, null, null)).single(),
        )
        assertTrue(LocalSetupCodec.supported(byteArrayOf(80, 78, 76, 67, 1, 31, 224.toByte(), 0)))
        assertFalse(LocalSetupCodec.supported(byteArrayOf(80, 78, 76, 67, 2, 31, 224.toByte(), 0)))
    }

    @Test
    fun maximumCredentialsAndFragmentation() {
        val args =
            linkedMapOf<Int, Any?>(
                0 to "a".repeat(63),
                1 to 65535,
                2 to "u".repeat(63),
                3 to "p".repeat(64),
                4 to 1,
                5 to true,
            )
        val command = LocalSetupCodec.command(1, 0xffffffffL, args)
        assertTrue(command.size <= 224)
        val receiver = LocalSetupCodec.Receiver()
        var result: ByteArray? = null
        LocalSetupCodec.packets(9, command).forEach {
            assertTrue(it.size <= 20)
            result = receiver.accept(it, 9)
        }
        assertArrayEquals(command, result)
        assertEquals(
            listOf(
                1L,
                0xffffffffL,
                args
                    .mapKeys { it.key.toLong() }
                    .mapValues { if (it.value is Int) (it.value as Int).toLong() else it.value },
            ),
            LocalSetupCodec.decode(command),
        )
    }

    @Test
    fun malformedFragmentsAndStaleIds() {
        val receiver = LocalSetupCodec.Receiver()
        assertNull(receiver.accept(byteArrayOf(3, 8, 0, 1), 7))
        try {
            receiver.accept(byteArrayOf(2, 7, 2, 1), 7)
            fail("bad sequence")
        } catch (expected: IllegalArgumentException) {}
        try {
            LocalSetupCodec.decode(byteArrayOf(0xfa.toByte(), 0, 0, 0, 0))
            fail("float")
        } catch (expected: IllegalArgumentException) {}
        try {
            LocalSetupCodec.encode(5.25)
            fail("float")
        } catch (expected: IllegalArgumentException) {}
    }
}
