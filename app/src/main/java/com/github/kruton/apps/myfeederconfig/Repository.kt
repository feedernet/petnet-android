package com.github.kruton.apps.myfeederconfig

import androidx.lifecycle.MutableLiveData
import com.polidea.rxandroidble2.RxBleDevice
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor() {
    val feeders = mutableListOf<Feeder>()
}

fun <T> MutableLiveData<T>.notifyObserver() {
    this.value = this.value
}

data class Feeder(
    val device: RxBleDevice,
    val name: String,
    val mac: String
)