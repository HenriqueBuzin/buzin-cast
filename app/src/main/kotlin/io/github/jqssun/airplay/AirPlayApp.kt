package io.github.jqssun.airplay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jqssun.airplay.billing.AppstoreBilling

@HiltAndroidApp
class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppstoreBilling.init(this)
    }
}
