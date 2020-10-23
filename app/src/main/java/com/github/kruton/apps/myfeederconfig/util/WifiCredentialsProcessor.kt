package com.github.kruton.apps.myfeederconfig.util

import android.util.Base64
import android.util.Base64.NO_WRAP
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays

class WifiCredentialsProcessor {
    companion object {
        @Throws(IOException::class)
        fun encode(
            wifiSsid: String,
            wifiPassword: String,
            token: String,
            chunkSize: Int = 20
        ): ByteArray {
            val command = ByteArrayOutputStream()
            command.write(TRANSMISSION_BEGIN)
            command.write(Base64.encode(wifiSsid.toByteArray(charset("UTF-8")), NO_WRAP))
            command.write(RECORD_SEPARATOR)
            command.write(Base64.encode(wifiPassword.toByteArray(charset("UTF-8")), NO_WRAP))
            command.write(RECORD_SEPARATOR)
            command.write(Base64.encode(token.toByteArray(charset("UTF-8")), NO_WRAP))
            command.write(TRANSMISSION_END_FINAL)
            val commandBytes = command.toByteArray()
            val chunkedsBytes = ByteArrayOutputStream()
            var chunkEnd: Int
            var commandOffset = 0
            while (commandOffset < commandBytes.size) {
                chunkEnd = commandOffset + chunkSize
                if (chunkEnd < commandBytes.size) {
                    val chunk = Arrays.copyOfRange(commandBytes, commandOffset, chunkEnd)
                    chunk[chunkSize - 1] = TRANSMISSION_END_BLOCK.toByte()
                    chunkedsBytes.write(chunk)
                } else {
                    chunkedsBytes.write(Arrays.copyOfRange(commandBytes, commandOffset, commandBytes.size))
                    if (chunkEnd == commandBytes.size) {
                        break
                    }
                }
                commandOffset = chunkEnd - 1
            }
            return chunkedsBytes.toByteArray()
        }
        private const val RECORD_SEPARATOR = 30
        private const val TRANSMISSION_BEGIN = 2
        private const val TRANSMISSION_END_BLOCK = 23
        private const val TRANSMISSION_END_FINAL = 4
    }
}