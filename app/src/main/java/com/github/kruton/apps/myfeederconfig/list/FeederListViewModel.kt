package com.github.kruton.apps.myfeederconfig.list

import android.Manifest
import android.os.Build
import android.app.Application
import android.os.ParcelUuid
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

@HiltViewModel
class FeederListViewModel @Inject constructor(
    app: Application,
    private val repository: Repository,
    private val savedStateHandle: SavedStateHandle
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
                scanError.value = false
                scanBleDevices()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally { dispose() }
                    .subscribe({ adapter?.addScanResult(it) }, { onScanFailure(it) })
                    .let { scanDisposable = it }
            }
        }

    private var scanDisposable: Disposable? = null
    val scanError = MutableLiveData(false)

    private fun dispose() {
        scanDisposable = null
        adapter?.clearScanResults()
    }

    private fun onScanFailure(throwable: Throwable?) {
        Timber.d(throwable)
        scanError.value = true
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

    var permissionsGranted = false
        private set

    private val cancelRequest: Cancellable = requestPermission()

    private fun requestPermission(): Cancellable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            permissionsGranted = true
            return {}
        }
        return PermissionRequester.requestPermissions(
            getApplication(),
            *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            })
        ) { list ->
            permissionsGranted = list.isNotEmpty() && list.all { it.state == State.GRANTED }
            _permissionResults.value = Event(permissionsGranted)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelRequest()
    }
}
