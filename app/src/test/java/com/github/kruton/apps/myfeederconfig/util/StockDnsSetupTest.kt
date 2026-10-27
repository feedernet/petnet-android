package com.github.kruton.apps.myfeederconfig.util

import org.junit.Assert.*
import org.junit.Test

class StockDnsSetupTest {
    private fun plan(
        dns: String = "10.100.10.5",
        ip: String = "10.100.100.198",
        mask: String = "255.255.255.0",
        gateway: String = "10.100.100.1"
    ) =
        StockDnsSetup.plan("network", "test-password", ip, mask, gateway, dns)

    @Test fun boundedStagesAndCleanFinalPassword() {
        val stages = plan()
        assertEquals(
            listOf(
                "x\r\rATNDHCP=0\rATNDHCP=?",
                "x\r\rATNSET=10.100.100.198,255.255.255.0,10.100.100.1",
                "x\r\rATNDNSSVR1=0\rATNDNSSVR1=10.100.10.5\rATNDNSSVR=?",
                "test-password"
            ),
            stages
        )
        assertTrue(stages.all { it.toByteArray().size <= 64 })
        assertEquals(45_000L, StockDnsSetup.SETTLE_MS)
        assertTrue(plan(dns = "223.255.255.254").all { it.length <= 64 })
        assertEquals(
            listOf("x\r\rATNDHCP=1\rATNDHCP=?", "test-password"),
            StockDnsSetup.restore("network", "test-password")
        )
    }

    @Test fun dhcpAndStaticNativeArguments() {
        val dhcp = StockDnsSetup.networkArguments(true, "", "", "", "")
        assertEquals(true, dhcp[0]); assertEquals(0L, dhcp[1]); assertEquals(0L, dhcp[4])
        val static = StockDnsSetup.networkArguments(false, "10.100.100.198", "255.255.255.0", "10.100.100.1", "10.250.0.1")
        assertEquals(false, static[0]); assertEquals(0x0a6464c6L, static[1])
        assertEquals("10.100.100.198", StockDnsSetup.address(static[1] as Long))
        rejects { StockDnsSetup.address(0x100000000L) }
    }

    @Test fun invalidInputsCannotInjectCommands() {
        for (
            bad in listOf(
                "1.1.1.1:1053", "1.1.1.1\rATWREV", "01.1.1.1", "256.1.1.1",
                "0.0.0.0", "127.0.0.1", "224.0.0.1", "::1", ""
            )
        ) {
            rejects { plan(dns = bad) }
        }
        rejects { plan(ip = "10.100.100.0") }
        rejects { plan(ip = "10.100.100.255") }
        rejects { plan(ip = "10.100.100.1") }
        rejects { plan(gateway = "10.100.10.1") }
        rejects { plan(mask = "255.0.255.0") }
        rejects { plan(mask = "255.255.255.255") }
        rejects { StockDnsSetup.plan("é".repeat(17), "password", "1.1.1.2", "255.255.255.0", "1.1.1.1", "1.1.1.1") }
        rejects { StockDnsSetup.restore("network", "p\rATWREV") }
        rejects { StockDnsSetup.restore("network", "é".repeat(33)) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid setup to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
