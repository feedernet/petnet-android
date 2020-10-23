package com.github.kruton.apps.myfeederconfig

import android.app.Application
import com.polidea.rxandroidble2.LogConstants
import com.polidea.rxandroidble2.LogOptions
import com.polidea.rxandroidble2.RxBleClient
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import timber.log.Timber.DebugTree


@HiltAndroidApp
class FeederApplication : Application() {
    companion object {
        lateinit var rxBleClient: RxBleClient
            private set
    }

    override fun onCreate() {
        super.onCreate()

        var rxBleLogLevel = LogConstants.INFO

        if (BuildConfig.DEBUG) {
            rxBleLogLevel = LogConstants.DEBUG
            Timber.plant(DebugTree())
        }

        rxBleClient = RxBleClient.create(this)
        RxBleClient.updateLogOptions(
            LogOptions.Builder()
                .setLogLevel(rxBleLogLevel)
                .setMacAddressLogSetting(LogConstants.MAC_ADDRESS_FULL)
                .setUuidsLogSetting(LogConstants.UUIDS_FULL)
                .setShouldLogAttributeValues(true)
                .setLogger { level: Int, tag: String?, msg: String? ->
                    Timber.tag(tag).log(level, msg)
                }
                .build()
        )

    }
}