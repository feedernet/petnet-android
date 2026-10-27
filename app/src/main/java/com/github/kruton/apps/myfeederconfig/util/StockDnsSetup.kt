package com.github.kruton.apps.myfeederconfig.util

/** The experimentally verified stock CR-in-password sequence, not a Rust protocol. */
object StockDnsSetup {
    const val SETTLE_MS = 45_000L

    private fun ipv4(text: String): Long {
        val parts = text.split('.')
        require(
            parts.size == 4 && parts.all {
                it.isNotEmpty() && it.all { c -> c in '0'..'9' } &&
                    (it == "0" || !it.startsWith('0')) && (it.toIntOrNull() ?: -1) in 0..255
            }
        ) { "Use dotted IPv4 addresses without a port" }
        return parts.fold(0L) { value, part -> (value shl 8) or part.toLong() }
    }

    private fun host(text: String): Long {
        val value = ipv4(text)
        require((value ushr 24) in 1L..223L && value ushr 24 != 127L) {
            "Use a unicast IPv4 address"
        }
        return value
    }

    private fun credentials(ssid: String, password: String) {
        require(
            ssid.toByteArray(Charsets.UTF_8).size in 1..32 &&
                password.toByteArray(Charsets.UTF_8).size in 1..64 &&
                (ssid + password).none { it == '\r' || it == '\n' || it == '\u0000' }
        ) {
            "Enter an SSID and WPA password within the stock byte limits"
        }
    }

    fun networkArguments(dhcp: Boolean, ip: String, mask: String, gateway: String, dns: String): Map<Int, Any?> {
        if (dhcp) return linkedMapOf(0 to true, 1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L)
        val address = host(ip)
        val router = host(gateway)
        val resolver = host(dns)
        val netmask = ipv4(mask)
        val inverse = netmask xor 0xffffffffL
        require(netmask != 0L && inverse >= 3 && (inverse and (inverse + 1)) == 0L) {
            "Use a contiguous IPv4 subnet mask with usable host addresses"
        }
        require(
            (address and netmask) == (router and netmask) && address != router &&
                (address and inverse) != 0L && (address and inverse) != inverse &&
                (router and inverse) != 0L && (router and inverse) != inverse
        ) {
            "Use distinct feeder and gateway host addresses in the same subnet"
        }
        return linkedMapOf(0 to false, 1 to address, 2 to netmask, 3 to router, 4 to resolver)
    }

    fun address(value: Long): String {
        require(value in 0..0xffffffffL)
        return if (value == 0L) "" else (3 downTo 0).joinToString(".") { ((value shr (8 * it)) and 255).toString() }
    }

    fun plan(
        ssid: String,
        password: String,
        ip: String,
        mask: String,
        gateway: String,
        dns: String
    ): List<String> {
        credentials(ssid, password)
        networkArguments(false, ip, mask, gateway, dns)
        val stages = listOf(
            "x\r\rATNDHCP=0\rATNDHCP=?",
            "x\r\rATNSET=$ip,$mask,$gateway",
            "x\r\rATNDNSSVR1=0\rATNDNSSVR1=$dns\rATNDNSSVR=?",
            password,
        )
        require(stages.all { it.toByteArray(Charsets.UTF_8).size <= 64 }) {
            "DNS setup commands exceed the stock password limit"
        }
        return stages
    }

    fun restore(ssid: String, password: String): List<String> {
        credentials(ssid, password)
        return listOf("x\r\rATNDHCP=1\rATNDHCP=?", password)
    }
}
