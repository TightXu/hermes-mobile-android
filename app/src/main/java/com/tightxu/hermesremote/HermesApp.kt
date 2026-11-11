package com.tightxu.hermesremote

import android.app.Application
import com.tightxu.hermesremote.data.ApiClient
import com.tightxu.hermesremote.data.PersistentCookieStore
import com.tightxu.hermesremote.data.SecretStore
import com.tightxu.hermesremote.data.SettingsStore

/** Hand-rolled DI: one container, created once in [HermesApp]. No Hilt/Koin. */
class AppContainer(application: Application) {
    val settings: SettingsStore = SettingsStore(application)
    val cookieStore: PersistentCookieStore = PersistentCookieStore(application)
    val secretStore: SecretStore = SecretStore(application)
    val api: ApiClient = ApiClient(cookieStore)
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
