package com.github.kruton.apps.myfeederconfig.config

import android.app.Application
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.github.kruton.apps.myfeederconfig.Feeder
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_LOCAL_CAPS
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_LOCAL_CONTROL
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_STATUS_UPDATE
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_WIFI_CREDENTIALS
import com.github.kruton.apps.myfeederconfig.util.Event
import com.github.kruton.apps.myfeederconfig.util.LocalSetupCodec
import com.github.kruton.apps.myfeederconfig.util.SERVICE_LOCAL_SETUP
import com.github.kruton.apps.myfeederconfig.util.StockDnsSetup
import com.github.kruton.apps.myfeederconfig.util.WifiCredentialsProcessor
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDeviceServices
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

@HiltViewModel
class FeederConfigViewModel @Inject constructor(
    app: Application,
    private val repository: Repository,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app), LifecycleObserver {
    val feeder = MutableLiveData<Feeder>()
    val ssid = MutableLiveData<String>()
    val password = MutableLiveData<String>()
    val wifiStatus = MutableLiveData("Connecting…")
    val wifiReady = MutableLiveData(false)
    val sendClicked = MutableLiveData<Event<Unit>>()
    val confirmMqtt = MutableLiveData<Event<Unit>>()
    val localSupported = MutableLiveData(false)
    val busy = MutableLiveData(false)
    val localStatus = MutableLiveData("Checking firmware capabilities…")
    val stockFirmware = MutableLiveData(false)
    val networkSupported = MutableLiveData(false)
    val networkDhcp = MutableLiveData(true)
    private var networkRevision = 0L
    private var needNetworkRead = true
    val dnsServer = MutableLiveData("")
    val staticAddress = MutableLiveData("")
    val staticMask = MutableLiveData("255.255.255.0")
    val staticGateway = MutableLiveData("")
    val dnsStatus = MutableLiveData("Experimental: DNS may revert on reconnect. Verify resolution before migration.")
    private var dnsPlan: List<String>? = null
    private var dnsSsid = ""
    private var dnsStage = -1
    private var dnsSawConnection = false
    private var dnsRestoring = false
    private var dnsWrite: Disposable? = null
    private var dnsTimer: Runnable? = null
    val brokerHost = MutableLiveData("")
    val brokerPort = MutableLiveData("1883")
    val brokerUser = MutableLiveData("")
    val brokerPassword = MutableLiveData("")
    val clearPassword = MutableLiveData(false)
    val mqttEnabled = MutableLiveData(false)
    val canResume = MutableLiveData(false)
    val webAddress = MutableLiveData("")
    private val subscriptions = CompositeDisposable()
    private val main = Handler(Looper.getMainLooper())
    private val receiver = LocalSetupCodec.Receiver()
    private var connection: RxBleConnection? = null
    private var generation = 0
    private var localWrite: Disposable? = null
    private var pending = 0
    private var next = 0
    private var revision = 0L
    private var draftRevision = 0L
    private var configuredMqtt = false
    private var passwordSet = false
    private var initial = true
    private var prefill = true
    private var pendingOperation = 0
    private var statusPolls = 0
    private var timeout: Runnable? = null

    override fun onCleared() {
        subscriptions.dispose()
        main.removeCallbacksAndMessages(null)
        dnsPlan = null
        dnsSsid = ""
        brokerPassword.value = ""
        password.value = ""
        receiver.reset()
    }

    fun handleSendClick() {
        sendClicked.value = Event(Unit)
    }

    fun setFeeder(mac: String) {
        if (feeder.value?.mac == mac && !subscriptions.isDisposed && connection != null) return
        stopDns("Setup connection changed; retry the full DNS sequence if it was interrupted.")
        stockFirmware.value = false
        networkSupported.value = false
        networkDhcp.value = true
        needNetworkRead = true
        val epoch = ++generation
        main.removeCallbacksAndMessages(null)
        localWrite?.dispose()
        pending = 0
        receiver.reset()
        busy.value = false
        subscriptions.clear()
        connection = null
        wifiReady.value = false
        localSupported.value = false
        canResume.value = false
        initial = true
        configuredMqtt = false
        passwordSet = false
        localStatus.value = "Checking firmware capabilities…"
        feeder.value = repository.feeders.first { it.mac == mac }
        subscriptions.add(
            feeder.value!!
                .device
                .establishConnection(false)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { c ->
                        connection = c
                        subscriptions.add(
                            c.setupNotification(CHARACTERISTIC_STATUS_UPDATE)
                                .subscribe(
                                    { stream ->
                                        subscriptions.add(
                                            stream
                                                .observeOn(AndroidSchedulers.mainThread())
                                                .subscribe(
                                                    {
                                                        val status = it.toString(Charsets.UTF_8)
                                                        wifiStatus.value = status
                                                        stockDnsStatus(status)
                                                    },
                                                    { failure(epoch, it) },
                                                )
                                        )
                                        main.post {
                                            wifiReady.value = true
                                            wifiStatus.value = "Ready"
                                        }
                                    },
                                    { failure(epoch, it) },
                                )
                        )
                        subscriptions.add(
                            c.discoverServices()
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                    { services -> discoverLocal(c, services, epoch) },
                                    { failure(epoch, it) },
                                )
                        )
                    },
                    { failure(epoch, it) },
                )
        )
    }

    internal fun discoverLocal(c: RxBleConnection, services: RxBleDeviceServices, epoch: Int) {
        stockFirmware.value = false
        val service = services.bluetoothGattServices.firstOrNull { it.uuid == SERVICE_LOCAL_SETUP }
        val control = service?.getCharacteristic(CHARACTERISTIC_LOCAL_CONTROL)
        if (control == null || service.getCharacteristic(CHARACTERISTIC_LOCAL_CAPS) == null) {
            val rustName = feeder.value?.name?.startsWith("SF R") == true
            stockFirmware.value = !rustName
            localStatus.value = if (rustName) "Rust firmware detected; reconnect to refresh services" else
                "Stock firmware: Wi-Fi setup and experimental DNS workaround"
            return
        }
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        subscriptions.add(
            c.readCharacteristic(CHARACTERISTIC_LOCAL_CAPS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { caps ->
                        if (!LocalSetupCodec.supported(caps)) {
                            localStatus.value =
                                "Update the setup app to access this firmware's additional settings"
                        } else {
                            networkSupported.value = caps[5].toInt() and 32 != 0
                            subscribeLocal(c, epoch)
                        }
                    },
                    { failure(epoch, it) },
                )
        )
    }

    private fun subscribeLocal(c: RxBleConnection, epoch: Int) {
        subscriptions.add(
            c.setupNotification(CHARACTERISTIC_LOCAL_CONTROL)
                .subscribe(
                    { stream ->
                        subscriptions.add(
                            stream
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ onPacket(it) }, { failure(epoch, it) })
                        )
                        main.post {
                            if (epoch == generation) {
                                localSupported.value = true
                                initial = true
                                refresh()
                            }
                        }
                    },
                    { failure(epoch, it) },
                )
        )
    }

    private fun failure(epoch: Int, error: Throwable? = null) {
        if (com.github.kruton.apps.myfeederconfig.BuildConfig.DEBUG && error != null) {
            val numbers = error.javaClass.fields.filter {
                it.type == Integer.TYPE && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.mapNotNull { field -> try { field.name + "=" + field.getInt(error) } catch (_: Exception) { null } }
            android.util.Log.w("PetnetSetup", "BLE failure " + error.javaClass.simpleName + " " + numbers.joinToString())
        }
        main.post {
            if (epoch != generation) return@post
            stopDns("DNS setup interrupted. Reconnect and restore DHCP or retry the full sequence.")
            localWrite?.dispose()
            timeout?.let { main.removeCallbacks(it) }
            pending = 0
            receiver.reset()
            busy.value = false
            wifiReady.value = false
            localSupported.value = false
            connection = null
            wifiStatus.value = "Connection lost; reconnect to try again"
            localStatus.value = "Connection lost. Reload settings before retrying a save."
        }
    }

    fun sendWifiConfig(onError: (Throwable) -> Unit) {
        val c = connection
        if (c == null || wifiReady.value != true || busy.value == true) {
            onError(IllegalStateException("Not ready"))
            return
        }
        val s = ssid.value.orEmpty()
        val p = password.value.orEmpty()
        if (s.isEmpty() || s.toByteArray().size > 32 || p.toByteArray().size > 64) {
            wifiStatus.value = "Enter a valid SSID and password"
            return
        }
        busy.value = true
        wifiStatus.value = "Sending Wi-Fi settings…"
        subscriptions.add(
            c.createNewLongWriteBuilder()
                .setCharacteristicUuid(CHARACTERISTIC_WIFI_CREDENTIALS)
                .setBytes(WifiCredentialsProcessor.encode(s, p, "foobar"))
                .build()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        busy.value = false
                        password.value = ""
                    },
                    {
                        busy.value = false
                        wifiStatus.value = "Could not send settings"
                        onError(it)
                    },
                )
        )
    }

    fun saveNetwork() {
        if (stockFirmware.value == true) { applyStockDns(); return }
        if (networkSupported.value != true || localSupported.value != true || busy.value == true) return
        val args = try {
            StockDnsSetup.networkArguments(
                networkDhcp.value != false,
                staticAddress.value.orEmpty().trim(), staticMask.value.orEmpty().trim(),
                staticGateway.value.orEmpty().trim(), dnsServer.value.orEmpty().trim()
            )
        } catch (e: IllegalArgumentException) { dnsStatus.value = e.message; return }
        dnsStatus.value = "Saving network settings; Wi-Fi will reconnect…"
        command(6, networkRevision, args, false)
    }

    fun refreshNetwork() {
        if (networkSupported.value == true && localSupported.value == true) command(5, null, null, false)
    }

    fun applyStockDns() {
        startStockDns(networkDhcp.value != false)
    }

    fun restoreStockDhcp() {
        networkDhcp.value = true
        startStockDns(true)
    }

    private fun startStockDns(restore: Boolean) {
        if (stockFirmware.value != true || localSupported.value == true ||
            connection == null || wifiReady.value != true || busy.value == true ||
            feeder.value?.name?.startsWith("SF R") == true
        ) return
        val s = ssid.value.orEmpty()
        val p = password.value.orEmpty()
        val plan = try {
            if (restore) StockDnsSetup.restore(s, p) else StockDnsSetup.plan(
                s, p, staticAddress.value.orEmpty().trim(), staticMask.value.orEmpty().trim(),
                staticGateway.value.orEmpty().trim(), dnsServer.value.orEmpty().trim()
            )
        } catch (error: IllegalArgumentException) {
            dnsStatus.value = error.message
            return
        }
        dnsPlan = plan
        dnsSsid = s
        dnsRestoring = restore
        dnsSawConnection = false
        busy.value = true
        sendStockDnsStage(0, generation)
    }

    private fun stopDns(message: String) {
        if (dnsPlan == null) return
        dnsTimer?.let { main.removeCallbacks(it) }
        dnsTimer = null
        dnsWrite?.dispose()
        dnsWrite = null
        dnsPlan = null
        dnsSsid = ""
        dnsStage = -1
        dnsStatus.value = message
        busy.value = false
    }

    private fun stockDnsStatus(status: String) {
        val plan = dnsPlan ?: return
        if (dnsStage != plan.lastIndex) return
        if (status == "STATUS_WIFI_CONN") dnsSawConnection = true
        if (status == "STATUS_WIFI_IP" && dnsSawConnection) {
            val result = if (dnsRestoring) "Wi-Fi connected after DHCP restore request." else
                "Wi-Fi connected. DNS commands sent; verify DNS and migration externally. DNS can revert on reconnect."
            stopDns(result)
            password.value = ""
        }
    }

    private fun sendStockDnsStage(index: Int, epoch: Int) {
        val plan = dnsPlan ?: return
        val c = connection ?: return stopDns("Disconnected; restore DHCP or retry DNS setup.")
        if (generation != epoch || stockFirmware.value != true || localSupported.value == true) {
            stopDns("Firmware or connection changed; DNS setup stopped.")
            return
        }
        dnsStage = index
        dnsStatus.value = if (index == plan.lastIndex) "Restoring clean Wi-Fi credentials…" else
            "Sending DNS setup stage ${index + 1} of ${plan.size - 1}; keep this screen connected…"
        dnsWrite = c.createNewLongWriteBuilder()
            .setCharacteristicUuid(CHARACTERISTIC_WIFI_CREDENTIALS)
            .setBytes(WifiCredentialsProcessor.encode(dnsSsid, plan[index], "foobar"))
            .build()
            .lastOrError()
            .timeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    if (generation == epoch && dnsPlan === plan && dnsStage == index) {
                        val final = index == plan.lastIndex
                        dnsStatus.value = if (final) "Waiting for Wi-Fi IP; DNS itself is not yet verified…" else
                            "Stage ${index + 1} delivered. Waiting 45 seconds for stock to process it…"
                        val timer = Runnable {
                            if (generation == epoch && dnsPlan === plan && dnsStage == index) {
                                if (final) stopDns("No Wi-Fi confirmation. Reconnect and restore DHCP or retry the full sequence.")
                                else sendStockDnsStage(index + 1, epoch)
                            }
                        }
                        dnsTimer = timer
                        main.postDelayed(timer, if (final) 120_000L else StockDnsSetup.SETTLE_MS)
                    }
                },
                {
                    if (generation == epoch && dnsPlan === plan)
                        stopDns("Write outcome unknown. Reconnect and restore DHCP or retry the full DNS sequence.")
                }
            )
        subscriptions.add(dnsWrite!!)
    }

    fun saveBroker(acknowledge: Boolean = false) {
        if (busy.value == true || localSupported.value != true) return
        val host = brokerHost.value.orEmpty().trim()
        val port = brokerPort.value.orEmpty().toIntOrNull()
        val user = brokerUser.value.orEmpty()
        val secret = brokerPassword.value.orEmpty()
        val enabled = mqttEnabled.value == true
        if (
            port == null ||
            port !in 1..65535 ||
            host.toByteArray().size > 63 ||
            (host.isNotEmpty() && !host.matches(Regex("[A-Za-z0-9.-]+"))) ||
            (enabled && host.isEmpty()) ||
            user.toByteArray().size > 63 ||
            secret.toByteArray().size > 64 ||
            user.contains('\u0000') ||
            secret.contains('\u0000')
        ) {
            localStatus.value = "Check the host, port, and credential lengths"
            return
        }
        if (clearPassword.value == true && secret.isNotEmpty()) {
            localStatus.value = "Choose a new password or clear the saved password"
            return
        }
        if (
            user.isEmpty() && (secret.isNotEmpty() || (passwordSet && clearPassword.value != true))
        ) {
            localStatus.value = "A password requires a username"
            return
        }
        if (enabled && !configuredMqtt && !acknowledge) {
            confirmMqtt.value = Event(Unit)
            return
        }
        val args =
            linkedMapOf<Int, Any?>(
                0 to host,
                1 to port,
                2 to user,
                4 to if (enabled) 1 else 0,
                5 to acknowledge,
            )
        if (clearPassword.value == true) args[3] = "" else if (secret.isNotEmpty()) args[3] = secret
        command(1, draftRevision, args, true)
    }

    fun refresh() { needNetworkRead = true; command(0, null, null, true) }

    fun openWeb() = command(3, null, null, false)

    fun resumeMqtt() = command(4, null, null, false)

    private fun command(op: Int, rev: Long?, args: Map<Int, Any?>?, fill: Boolean) {
        val c = connection ?: return
        if (busy.value == true || localSupported.value != true) return
        next = next % 255 + 1
        pending = next
        pendingOperation = op
        prefill = fill
        receiver.reset()
        busy.value = true
        if (op == 5 || op == 6) {
            dnsStatus.value = if (op == 5) "Reading saved IPv4 settings…" else "Applying IPv4 settings…"
        } else localStatus.value = if (op == 0) "Reading feeder settings…" else "Applying settings…"
        val id = pending
        timeout = Runnable {
            if (pending == id) completeError("No reply. Reload settings to check the saved state.")
        }
        main.postDelayed(timeout!!, 60_000)
        val packets =
            try {
                LocalSetupCodec.packets(id, LocalSetupCodec.command(op, rev, args))
            } catch (e: Exception) {
                completeError("Settings are too large")
                return
            }
        localWrite =
            Observable.fromIterable(packets)
                .concatMapSingle { c.writeCharacteristic(CHARACTERISTIC_LOCAL_CONTROL, it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {},
                    { completeError("Could not send command; reload settings before retrying") },
                )
        subscriptions.add(localWrite!!)
    }

    private fun completeError(message: String) {
        localWrite?.dispose()
        timeout?.let { main.removeCallbacks(it) }
        pending = 0
        receiver.reset()
        busy.value = false
        localStatus.value = message
        if (pendingOperation == 5 || pendingOperation == 6) dnsStatus.value = message
    }

    private fun onPacket(packet: ByteArray) {
        if (pending == 0) return
        try {
            val data = receiver.accept(packet, pending) ?: return
            val response = LocalSetupCodec.decode(data) as? List<*> ?: error("Invalid reply")
            require(response.size == 3)
            val code = response[0] as Long
            localWrite?.dispose()
            timeout?.let { main.removeCallbacks(it) }
            pending = 0
            busy.value = false
            if (code != 0L) {
                localStatus.value =
                    when (code) {
                        1L -> "Invalid settings or command"
                        2L -> "Settings changed; reload before saving"
                        3L -> "Feeder busy with another operation or OTA"
                        4L -> "Could not persist settings"
                        else -> "Unsupported command"
                    }
                if (pendingOperation == 5 || pendingOperation == 6) dnsStatus.value = localStatus.value
                return
            }
            if (pendingOperation != 0 || initial) statusPolls = 5
            revision = response[1] as Long
            @Suppress("UNCHECKED_CAST")
            val m = response[2] as? Map<Long, Any?> ?: error("Missing snapshot")
            if (pendingOperation == 5 || pendingOperation == 6) {
                networkRevision = revision
                draftRevision = revision
                networkDhcp.value = m[0L] as Boolean
                staticAddress.value = StockDnsSetup.address(m[1L] as Long)
                val mask = StockDnsSetup.address(m[2L] as Long)
                staticMask.value = if (mask.isEmpty()) "255.255.255.0" else mask
                staticGateway.value = StockDnsSetup.address(m[3L] as Long)
                dnsServer.value = StockDnsSetup.address(m[4L] as Long)
                dnsStatus.value = if (pendingOperation == 6) "Network settings saved. Wi-Fi is reconnecting; reload status to check the address." else
                    "Saved IPv4 mode: ${if (networkDhcp.value == true) "DHCP" else "Static IPv4"}"
                return
            }
            configuredMqtt = (m[4L] as Long) == 1L
            passwordSet = m[3L] as Boolean
            canResume.value = configuredMqtt
            if (initial || prefill) {
                draftRevision = revision
                brokerHost.value = m[0L] as String
                brokerPort.value = (m[1L] as Long).toString()
                brokerUser.value = m[2L] as String
                mqttEnabled.value = configuredMqtt
                brokerPassword.value = ""
                clearPassword.value = false
                initial = false
            }
            val ip = m[8L] as Long
            webAddress.value =
                if (ip == 0L) ""
                else "http://" + (0..3).joinToString(".") { ((ip shr (8 * it)) and 255).toString() }
            val activeMode = m[5L] as Long
            val active = activeMode == 1L
            val connected = m[6L] as Boolean
            val seconds = m[7L] as Long
            localStatus.value =
                "Saved mode: ${if (configuredMqtt) "MQTT" else "HTTP"}. Active: ${if (activeMode == 2L) "switching" else if (active) {if (connected) "MQTT connected" else "MQTT connecting"} else "HTTP"}." +
                (
                    if (configuredMqtt && !active && seconds > 0)
                        " Web access: ${seconds}s remaining."
                    else ""
                    ) +
                (if (passwordSet) " Password saved." else " No password saved.")
            if (needNetworkRead && networkSupported.value == true) {
                needNetworkRead = false
                val epoch = generation
                main.post { if (generation == epoch && connection != null && busy.value != true) refreshNetwork() }
            }
            if (statusPolls > 0 && (activeMode == 2L || (configuredMqtt && active && !connected))) {
                statusPolls--
                main.postDelayed(
                    { if (connection != null && busy.value != true) command(0, null, null, false) },
                    2000,
                )
            }
        } catch (e: Exception) {
            completeError("Invalid reply; reload settings")
        }
    }
}
