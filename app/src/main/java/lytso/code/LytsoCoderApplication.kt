package lytso.code

import android.app.Application
import lytso.code.data.adb.AdbConnectManager
import lytso.code.data.updater.PluginUpdateCheckWorker
import lytso.code.data.updater.UpdateCheckWorker
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class LytsoCoderApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = lytso.code.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(lytso.code.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { lytso.code.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        // a crash opens the crash screen (with the report) instead of the app just vanishing
        lytso.code.crash.CrashHandler.install(this)
        // the crash screen's own process needs none of the startup below
        if (lytso.code.crash.CrashHandler.isCrashProcess(this)) return
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        lytso.code.data.preferences.AuthPreferences(this)
        // the chosen light / dark themes and the custom ones, before the first screen draws
        lytso.code.theme.ThemeManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // mini app reminders and exact-time tasks: alarms again (Android may have dropped them), off the main thread
        lytso.code.data.miniapps.MiniAppScheduler.rearmAsync(this)
        Thread { lytso.code.ui.miniapps.MiniAppShortcuts.migrate(this) }.start()
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        // ADB / Shizuku / root: which one Android Automation uses, and Shizuku's binder listeners
        lytso.code.data.adb.DeviceAccess.init(this)
        // the rooted Ubuntu chroot runtime: its installs and which one is in use (no root call here)
        lytso.code.data.local.chroot.ChrootManager.init(this)
        lytso.code.data.ssh.SshSessionFactory.init(this)
        // Connect to PC: the paired PCs and the one in use (AuthPreferences reads the latter on its own too)
        lytso.code.data.pc.PcConnect.init(this)
        // the cloud project new Antigravity chats start in (Remote Agents), before the first chat is created
        lytso.code.data.agent.agy.AgyCloud.init(this)
    }
}
