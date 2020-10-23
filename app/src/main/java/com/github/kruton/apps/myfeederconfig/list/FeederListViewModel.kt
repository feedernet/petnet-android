package com.github.kruton.apps.myfeederconfig.list

import android.app.Application
import android.os.ParcelUuid
import androidx.hilt.Assisted
import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.*
import com.github.kruton.apps.myfeederconfig.*
import com.github.kruton.apps.myfeederconfig.FeederApplication.Companion.rxBleClient
import com.github.kruton.apps.myfeederconfig.util.Event
import com.github.kruton.apps.myfeederconfig.util.SERVICE_FEEDER_V2
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import timber.log.Timber

class FeederListViewModel @ViewModelInject constructor(
    app: Application,
    private val repository: Repository,
    @Assisted private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app), LifecycleObserver {
    private fun scanBleDevices(): Flowable<ScanResult> {
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_FEEDER_V2))
            .build()

        return rxBleClient.scanBleDevices(scanSettings, scanFilter)
            .toFlowable(BackpressureStrategy.BUFFER)
    }

    var adapter: FeederListAdapter? = null
        set(value) {
            field = value
            if (value == null) {
                scanDisposable?.dispose()
            } else {
                scanBleDevices()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally { dispose() }
                    .subscribe({ adapter?.addScanResult(it) }, { onScanFailure(it) })
                    .let { scanDisposable = it }
            }
        }

    private var scanDisposable: Disposable? = null

    private fun dispose() {
        scanDisposable = null
        adapter?.clearScanResults()
    }

    private fun onScanFailure(throwable: Throwable?) {
        Timber.d(throwable)
    }

    val feederClicked: LiveData<Event<Feeder>>
        get() = _feederClicked

    private val _feederClicked = MutableLiveData<Event<Feeder>>()

    fun handleClick(feeder: Feeder) {
        _feederClicked.value = Event(feeder)
    }

    private val _permissionResults = MutableLiveData<Event<Boolean>>()

    val permissionsResults: LiveData<Event<Boolean>>
        get() = _permissionResults

    private val cancelRequest: Cancellable = requestPermission()

    private fun requestPermission(): Cancellable {
        return PermissionRequester.requestPermissions(
            getApplication(),
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION"
        ) { list ->
            _permissionResults.value = Event(list.all { it.state == State.GRANTED })
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelRequest()
    }
}