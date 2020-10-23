package com.github.kruton.apps.myfeederconfig.config

import android.app.Application
import androidx.hilt.Assisted
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import com.github.kruton.apps.myfeederconfig.Feeder
import com.github.kruton.apps.myfeederconfig.Repository
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_STATUS_UPDATE
import com.github.kruton.apps.myfeederconfig.util.CHARACTERISTIC_WIFI_CREDENTIALS
import com.github.kruton.apps.myfeederconfig.util.Event
import com.github.kruton.apps.myfeederconfig.util.WifiCredentialsProcessor
import com.polidea.rxandroidble2.utils.ConnectionSharingAdapter
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import timber.log.Timber

class FeederConfigViewModel @ViewModelInject constructor(
    app: Application,
    private val repository: Repository,
    @Assisted private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app), LifecycleObserver {
    override fun onCleared() {
        wifiStatusDisposable?.dispose()
        wifiWriterDisposable?.dispose()
    }

    private val disconnectTriggerSubject = PublishSubject.create<Unit>()

    private val _feeder: MutableLiveData<Feeder> = MutableLiveData()

    val feeder: LiveData<Feeder>
        get() = _feeder

    fun setFeeder(mac: String) {
        repository.feeders.first { it.mac == mac }.let {
            _feeder.value = it
        }
    }

    private val _wifiStatus = MutableLiveData<String>()
    val wifiStatus: LiveData<String>
        get() = _wifiStatus

    val sendClicked: LiveData<Event<Unit>>
        get() = _sendClicked

    private val _sendClicked = MutableLiveData<Event<Unit>>()

    fun handleSendClick() {
        _sendClicked.value = Event(Unit)
    }

    val ssid = MutableLiveData<String>()

    val password = MutableLiveData<String>()

    private var wifiWriterDisposable: Disposable? = null

    private var wifiStatusDisposable: Disposable? = null

    fun sendWifiConfig(onError: (Throwable) -> Unit) {
        wifiStatusDisposable?.dispose()
        wifiWriterDisposable?.dispose()

        feeder.value?.let { feeder ->
            val ssidValue = ssid.value ?: "none"
            val passwordValue = password.value ?: "password"

            _wifiStatus.postValue("Checking...")

            val connection =
                feeder.device.establishConnection(true)
                    .takeUntil(disconnectTriggerSubject)
                    .compose(ConnectionSharingAdapter())

            connection
                .flatMap {
                    it.createNewLongWriteBuilder()
                        .setCharacteristicUuid(CHARACTERISTIC_WIFI_CREDENTIALS)
                        .setBytes(
                            WifiCredentialsProcessor.encode(
                                ssidValue,
                                passwordValue,
                                "foobar"
                            )
                        )
                        .build()
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ written ->
                    Timber.d("wrote: %s", String(written))
                }) {
                    Timber.d(it)
                    _wifiStatus.value = "Could not send data"
                }.let {
                    wifiWriterDisposable = it
                }

            connection
                .flatMap { rxBleConnection ->
                    rxBleConnection.setupNotification(CHARACTERISTIC_STATUS_UPDATE)
                }
                .doOnNext {

                }
                .flatMap { notificationObservable ->
                    notificationObservable
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ characteristicValue ->
                    Timber.d("Characteristic: %s", String(characteristicValue))
                    _wifiStatus.value = String(characteristicValue)
                }) { throwable ->
                    onError(throwable)
                }.let {
                    wifiStatusDisposable = it
                }
        }
    }
}