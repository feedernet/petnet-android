package com.github.kruton.apps.myfeederconfig.config

import android.app.Application
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kruton.apps.myfeederconfig.R
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.databinding.FragmentFeederConfigBinding
import com.github.kruton.apps.myfeederconfig.util.StockDnsSetup
import com.github.kruton.apps.myfeederconfig.util.WifiCredentialsProcessor
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDeviceServices
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class SetupCompatibilityTest {
    @Test
    fun stockDiscoveryNeverReadsOrWritesExtension() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as Application
            val vm = FeederConfigViewModel(app, Repository(), SavedStateHandle())
            vm.wifiReady.value = true
            val c =
                Proxy.newProxyInstance(
                    RxBleConnection::class.java.classLoader,
                    arrayOf(RxBleConnection::class.java),
                ) { _, method, _ ->
                    throw AssertionError(
                        "Stock discovery must not call an extension operation: " + method.name
                    )
                } as RxBleConnection
            vm.discoverLocal(c, RxBleDeviceServices(emptyList()), 0)
            assertEquals(true, vm.wifiReady.value)
            assertEquals(false, vm.localSupported.value)
            assertEquals(true, vm.stockFirmware.value)
            assertEquals("Stock firmware: Wi-Fi setup and experimental DNS workaround", vm.localStatus.value)
        }
    }

    @Test
    fun stockAndExtendedLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as Application
            val context = ContextThemeWrapper(app, R.style.AppTheme)
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry(this)

                    override val lifecycle: Lifecycle get() = registry
                }
            owner.registry.currentState = Lifecycle.State.RESUMED
            val vm = FeederConfigViewModel(app, Repository(), SavedStateHandle())
            val binding = FragmentFeederConfigBinding.inflate(LayoutInflater.from(context))
            binding.lifecycleOwner = owner
            binding.viewModel = vm
            binding.executePendingBindings()
            assertEquals(View.GONE, binding.localSetupSection.visibility)
            assertEquals(View.GONE, binding.stockDnsSection.visibility)
            vm.stockFirmware.value = true
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.stockDnsSection.visibility)
            assertTrue(binding.networkDhcp.isChecked)
            assertEquals(View.GONE, binding.staticIpv4Fields.visibility)
            vm.networkDhcp.value = false
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.staticIpv4Fields.visibility)
            assertEquals("255.255.255.0", binding.staticMask.text.toString())
            assertEquals(View.VISIBLE, binding.wifiSsid.visibility)
            vm.localSupported.value = true
            vm.brokerHost.value = "broker.lan"
            vm.webAddress.value = "http://192.168.1.2"
            vm.mqttEnabled.value = false
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.webInterfaceSection.visibility)
            vm.mqttEnabled.value = true
            binding.executePendingBindings()
            assertEquals(View.GONE, binding.webInterfaceSection.visibility)
            assertEquals(View.VISIBLE, binding.localSetupSection.visibility)
            // Real Rust discovery clears the legacy flag before checking extensions.
            vm.stockFirmware.value = false
            binding.executePendingBindings()
            assertEquals(View.GONE, binding.stockDnsSection.visibility)
            vm.networkSupported.value = true
            binding.executePendingBindings()
            assertEquals(View.VISIBLE, binding.stockDnsSection.visibility)
            assertEquals("broker.lan", binding.brokerHost.text.toString())
            assertTrue(binding.savedMode.isChecked)
            assertFalse(binding.brokerPassword.isSaveEnabled)
            assertFalse(binding.wifiPassword.isSaveEnabled)
            vm.busy.value = true
            binding.executePendingBindings()
            assertFalse(binding.saveBroker.isEnabled)
            assertFalse(binding.brokerHost.isEnabled)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun dnsFramesCarryExactBoundedStagePasswords() {
        val stages = StockDnsSetup.plan(
            "network", "test-password", "10.100.100.198",
            "255.255.255.0", "10.100.100.1", "10.100.10.5"
        )
        for (password in stages) {
            val pieces = WifiCredentialsProcessor.encode("network", password, "foobar")
                .toList().chunked(20)
            val joined = pieces.flatMap { it.dropLast(1) }.drop(1).toByteArray()
                .toString(Charsets.UTF_8)
            assertTrue(joined.toByteArray().size + 2 <= 184)
            val fields = joined.split(30.toChar()).map {
                Base64.decode(it, Base64.NO_WRAP).toString(Charsets.UTF_8)
            }
            assertEquals(listOf("network", password, "foobar"), fields)
        }
    }

    @Test
    fun rustCapabilitiesBlockStockDnsWrites() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val vm = FeederConfigViewModel(app, Repository(), SavedStateHandle())
            val c = Proxy.newProxyInstance(
                RxBleConnection::class.java.classLoader,
                arrayOf(RxBleConnection::class.java)
            ) { _, method, _ ->
                throw AssertionError("Rust must not receive stock DNS writes: " + method.name)
            } as RxBleConnection
            val field = FeederConfigViewModel::class.java.getDeclaredField("connection")
            field.isAccessible = true
            field.set(vm, c)
            vm.stockFirmware.value = true
            vm.localSupported.value = true
            vm.wifiReady.value = true
            vm.ssid.value = "network"
            vm.password.value = "test-password"
            vm.applyStockDns()
            vm.restoreStockDhcp()
            assertEquals(false, vm.busy.value)
        }
    }

    @Test
    fun stockWifiFramingAllLengths() {
        for (slen in 1..32) for (plen in listOf(0, 1, 7, 8, 63, 64)) {
            val ssid = "a".repeat(slen)
            val password = "b".repeat(plen)
            val pieces =
                WifiCredentialsProcessor.encode(ssid, password, "foobar").toList().chunked(20)
            assertEquals(2, pieces.first().first().toInt())
            assertEquals(4, pieces.last().last().toInt())
            pieces.dropLast(1).forEach {
                assertEquals(20, it.size)
                assertEquals(23, it.last().toInt())
            }
            val joined =
                pieces.flatMap { it.dropLast(1) }.drop(1).toByteArray().toString(Charsets.UTF_8)
            val fields =
                joined.split(30.toChar()).map {
                    Base64.decode(it, Base64.NO_WRAP).toString(Charsets.UTF_8)
                }
            assertEquals(listOf(ssid, password, "foobar"), fields)
        }
    }
}
