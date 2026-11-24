package lytso.code.data.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.concurrent.TimeUnit
import lytso.code.data.security.AiPermissionsClient

/**
 * Root mode: commands run as root through the phone's own `su` (Magisk, KernelSU, APatch). The first `su` makes the
 * root manager ask the user to allow LytsoCoder; the answer is remembered by the root manager, not here.
 * Uses explicit candidate resolution matching `ai_permissions.go` so root works even when `su` is outside PATH.
 */
object RootShell {
    private const val PREFS = "adb_connect"

    private val SU_CANDIDATES = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su"
    )

    enum class Check { UNKNOWN, CHECKING, GRANTED, DENIED, NO_SU }

    data class State(val check: Check = Check.UNKNOWN, val message: String? = null, val suPath: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Resolves the real su binary path across Magisk / KernelSU / APatch locations, falling back to PATH `su`. */
    fun resolveSuBinary(): String {
        val cached = _state.value.suPath
        if (cached.isNotBlank() && File(cached).let { it.exists() && !it.isDirectory }) return cached
        val fromBridge = AiPermissionsClient.state.value.suPath
        if (fromBridge.isNotBlank() && File(fromBridge).let { it.exists() && !it.isDirectory }) return fromBridge
        for (cand in SU_CANDIDATES) {
            val f = File(cand)
            if (f.exists() && !f.isDirectory) return cand
        }
        return "su"
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // the last answer, so the page doesn't have to call su (and maybe pop the root prompt) just to show it
        val savedPath = prefs.getString("root_su_path", "") ?: ""
        if (prefs.getBoolean("root_granted", false)) _state.value = State(Check.GRANTED, suPath = savedPath)
    }

    /** Runs `su -c id -u`: the root manager asks the user the first time. */
    suspend fun check(): Check = withContext(Dispatchers.IO) {
        _state.value = State(Check.CHECKING, suPath = _state.value.suPath)
        val candidates = buildList {
            for (c in SU_CANDIDATES) {
                val f = File(c)
                if (f.exists() && !f.isDirectory) add(c)
            }
            if (isEmpty()) add("su")
        }
        var result = State(Check.NO_SU, "su was not found: this phone is not rooted, or the root manager hides su from LytsoCoder.")
        for (suBin in candidates) {
            result = try {
                val p = ProcessBuilder(suBin, "-c", "id -u").redirectErrorStream(true).start()
                p.outputStream.close()
                // the root manager's dialog waits for the user (Magisk: 10 s by default)
                val finished = p.waitFor(60, TimeUnit.SECONDS)
                val out = p.inputStream.bufferedReader().readText().trim()
                if (!finished) {
                    p.destroy()
                    State(Check.DENIED, "No answer from su (the root prompt timed out).", suBin)
                } else if (p.exitValue() == 0 && out.lineSequence().lastOrNull()?.trim() == "0") {
                    State(Check.GRANTED, null, suBin)
                } else {
                    State(Check.DENIED, out.ifBlank { "su exited with code ${p.exitValue()}" }.take(300), suBin)
                }
            } catch (e: Exception) {
                State(Check.NO_SU, "su was not found: this phone is not rooted, or the root manager hides su from LytsoCoder.")
            }
            if (result.check == Check.GRANTED) break
        }
        if (::appContext.isInitialized) {
            prefs.edit()
                .putBoolean("root_granted", result.check == Check.GRANTED)
                .putString("root_su_path", result.suPath)
                .apply()
        }
        _state.value = result
        runCatching { AiPermissionsClient.refresh() }
        result.check
    }

    fun notReadyMessage(): String? = when (_state.value.check) {
        Check.GRANTED -> null
        Check.NO_SU -> "This phone has no su (not rooted, or the root manager hides su from LytsoCoder)."
        Check.DENIED -> "Root was denied to LytsoCoder. Allow LytsoCoder in the root manager (Magisk / KernelSU / APatch), then tap Check root in LytsoCoder → Settings → AI Permissions, Root & ADB."
        else -> "Root hasn't been checked yet. Open LytsoCoder → Settings → AI Permissions, Root & ADB and tap Check root."
    }

    /** Starts `su -c [script]` (stdout and stderr together). */
    suspend fun start(script: String): DeviceAccess.RunningCommand = withContext(Dispatchers.IO) {
        val suBin = resolveSuBinary()
        // the first line is the root shell's pid (on a line of its own), so kill can stop what it started
        val process = try {
            ProcessBuilder(suBin, "-c", "echo \$\$\n$script").redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw IllegalStateException("su was not found: this phone is not rooted, or the root manager hides su from LytsoCoder.")
        }
        val raw = process.inputStream
        var pid = -1
        // read lazily: su may still be waiting for the user to allow it
        val out = object : InputStream() {
            private var stream: InputStream? = null
            private fun s(): InputStream = stream ?: run {
                val first = DeviceShellService.readFirstLine(raw)
                val n = first.trim().toIntOrNull()
                if (n != null) pid = n
                // not a pid: su printed something (e.g. "Permission denied") that belongs to the output
                val head = if (n == null && first.isNotEmpty()) (first + "\n").toByteArray() else ByteArray(0)
                SequenceInputStream(ByteArrayInputStream(head), raw).also { stream = it }
            }
            override fun read(): Int = s().read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = s().read(b, off, len)
            override fun close() = raw.close()
        }
        object : DeviceAccess.RunningCommand {
            override val input: OutputStream = process.outputStream
            override val output: InputStream = out
            override fun waitFor(): Int = runCatching { process.waitFor() }.getOrDefault(-1)
            override fun kill() {
                if (pid > 0) runCatching {
                    ProcessBuilder(suBin, "-c", "pkill -9 -P $pid; kill -9 $pid").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
                }
                runCatching { process.destroy() }
            }
        }
    }
}
