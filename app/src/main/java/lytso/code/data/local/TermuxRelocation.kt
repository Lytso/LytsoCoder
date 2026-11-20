package lytso.code.data.local

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs Termux packages in the Native system without proot and without rebuilding them. Termux packages are built
 * for /data/data/com.termux; this app's package name (lytso.code) has the same length (10 bytes), so every
 * "com.termux" in their files can be rewritten byte for byte: nothing inside a binary moves. Termux scripts then
 * call this app's components by the rewritten names (lytso.code/lytso.code.app.TermuxService, ...), which the
 * manifest declares.
 *
 * - The bootstrap, a rootfs or a restored backup: [relocateTree] after it is extracted. In the home folder only paths
 *   are rewritten (pathsOnly): it holds the user's own files, where a bare "com.termux" (say `package com.termux.foo;`
 *   in an Android project) must stay.
 * - Packages installed later: an apt `DPkg::Pre-Install-Pkgs` hook and a dpkg wrapper ([ensurePackageHooks]) rewrite each .deb (paths,
 *   file contents, symlinks, maintainer scripts) before dpkg unpacks it.
 */
object TermuxRelocation {

    private const val TAG = "TermuxRelocation"
    const val TERMUX_PACKAGE = "com.termux"
    private val TERMUX_BYTES = TERMUX_PACKAGE.toByteArray(Charsets.US_ASCII)

    /**
     * All 10-byte package IDs a rootfs, backup, or .deb may have been built or previously relocated for.
     * Rewriting all foreign ones ensures Release (`lytso.code`), Debug (`lytso.cdev`), official Termux
     * (`com.termux`), and legacy backups (`ai.antigem` / `ai.antidev`) run cleanly under the current app.
     */
    val KNOWN_SOURCE_PACKAGES: List<String> = listOf(
        TERMUX_PACKAGE,
        "lytso.code",
        "lytso.cdev",
        "ai.antigem",
        "ai.antidev"
    )

    /** Zip-based files keep CRCs and dex files a checksum: rewriting bytes in them would corrupt them, so they are skipped. */
    private val SKIPPED_EXTENSIONS = setOf("apk", "jar", "zip", "aar", "whl", "dex", "jmod")

    /**
     * "com.termux" followed by one of these stays: names inside Java code (in skipped zip files) that keep their Java
     * package. termux-am's `am` script starts com.termux.termuxam.Am from am.apk. termux-x11's scripts start
     * com.termux.x11.Loader from loader.apk and send its app actions (com.termux.x11.CHANGE_PREFERENCE): LytsoCoder's
     * Termux:X11 (lytso.code.x11) keeps those Java names. "com.termux.x11" as the app's package name (followed by
     * anything else) is still rewritten.
     */
    private val KEPT_SUFFIXES: List<ByteArray> = listOf(".termuxam", ".x11.").map { it.toByteArray(Charsets.US_ASCII) }
    private val LOOKAHEAD = KEPT_SUFFIXES.maxOf { it.size }
    private const val CHUNK = 256 * 1024
    /** The bridge's relocate-debs marker (Capability in agy_ide_bridge/pkg/relocate): the hook runs it only when found. */
    private const val FAST_RELOCATOR_MARKER = "lytsocoder-relocate-debs/1"
    private const val SLASH = '/'.code.toByte()

    data class Stats(var files: Int = 0, var patchedFiles: Int = 0, var links: Int = 0)

    /** This app's package name, which Termux files are rewritten for; null when it cannot be (another length). */
    fun targetPackage(context: Context): String? =
        context.packageName.takeIf { it != TERMUX_PACKAGE && it.length == TERMUX_PACKAGE.length }

    /** Rewrites [root] (recursively, without following symlinks) when this app needs it; null otherwise. */
    fun relocateIfNeeded(context: Context, root: File, onProgress: ((Int) -> Unit)? = null): Stats? {
        val pkg = targetPackage(context) ?: return null
        return relocateTree(root, pkg, onProgress)
    }

    private enum class EntryKind { LINK, DIR, FILE }

    private val useAndroidOs: Boolean by lazy {
        try { Os.lstat(".") != null } catch (_: Throwable) { false }
    }

    @android.annotation.SuppressLint("NewApi")
    private object NioFallback {
        fun kindOf(file: File): EntryKind? {
            val p = file.toPath()
            return when {
                java.nio.file.Files.isSymbolicLink(p) -> EntryKind.LINK
                java.nio.file.Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> EntryKind.DIR
                java.nio.file.Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> EntryKind.FILE
                else -> null
            }
        }

        fun readLink(file: File): String = java.nio.file.Files.readSymbolicLink(file.toPath()).toString()

        fun createSymlink(target: String, link: File) {
            java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
        }
    }

    /**
     * Rewrites every file and symlink under [root] from any known 10-byte source package to [newPackage].
     * With [pathsOnly], only matches immediately after "/" (/data/data/<pkg>/…, /data/user/0/<pkg>/…) are
     * rewritten, keeping user project identifiers intact inside home folders.
     */
    fun relocateTree(
        root: File,
        newPackage: String,
        onProgress: ((Int) -> Unit)? = null,
        pathsOnly: Boolean = false
    ): Stats {
        val new = newPackage.toByteArray(Charsets.US_ASCII)
        require(new.size == TERMUX_PACKAGE.length) { "$newPackage must have the length of $TERMUX_PACKAGE" }
        val sources = sourcePatternsFor(newPackage)
        val stats = Stats()
        if (!root.exists() || sources.isEmpty()) return stats
        val dirs = ArrayDeque<File>().apply { add(root) }
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeLast()
            // a git repository (projects in a restored home): only its config may hold paths; the index ends in a
            // checksum and objects are compressed, so the rest is left alone
            val children = if (dir.name == ".git") arrayOf(File(dir, "config")) else dir.listFiles() ?: continue
            for (f in children) {
                val (kind, mode) = if (useAndroidOs) {
                    val m = try { Os.lstat(f.absolutePath).st_mode } catch (_: Exception) { continue }
                    val k = when {
                        OsConstants.S_ISLNK(m) -> EntryKind.LINK
                        OsConstants.S_ISDIR(m) -> EntryKind.DIR
                        OsConstants.S_ISREG(m) -> EntryKind.FILE
                        else -> null
                    }
                    k to m
                } else {
                    (try { NioFallback.kindOf(f) } catch (_: Exception) { null }) to 0
                }
                when (kind) {
                    EntryKind.LINK -> if (relink(f, newPackage, pathsOnly)) stats.links++
                    EntryKind.DIR -> dirs.add(f)
                    EntryKind.FILE -> {
                        stats.files++
                        if (patchFile(f, mode, sources, new, pathsOnly)) stats.patchedFiles++
                        if (stats.files % 500 == 0) onProgress?.invoke(stats.files)
                    }
                    null -> {}
                }
            }
        }
        return stats
    }

    private fun sourcePatternsFor(newPackage: String): List<ByteArray> =
        KNOWN_SOURCE_PACKAGES
            .filter { it != newPackage }
            .map { pkg ->
                pkg.toByteArray(Charsets.US_ASCII).also {
                    require(it.size == TERMUX_PACKAGE.length) { "$pkg must have the length of $TERMUX_PACKAGE" }
                }
            }

    private fun relink(link: File, newPackage: String, pathsOnly: Boolean): Boolean {
        val target = try {
            if (useAndroidOs) Os.readlink(link.absolutePath) else NioFallback.readLink(link)
        } catch (_: Exception) { return false }
        if (KNOWN_SOURCE_PACKAGES.none { it != newPackage && target.contains(it) }) return false
        val newTarget = String(rewrite(target.toByteArray(Charsets.UTF_8), newPackage, pathsOnly), Charsets.UTF_8)
        if (newTarget == target) return false
        return try {
            link.delete()
            if (useAndroidOs) Os.symlink(newTarget, link.absolutePath) else NioFallback.createSymlink(newTarget, link)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Relinking ${link.absolutePath} -> $target failed: ${e.message}")
            false
        }
    }

    private fun patchFile(file: File, mode: Int, patterns: List<ByteArray>, new: ByteArray, pathsOnly: Boolean): Boolean {
        if (file.extension.lowercase() in SKIPPED_EXTENSIONS) return false
        return try {
            val offsets = findAll(file, patterns, pathsOnly)
            if (offsets.isEmpty()) return false
            val perms = mode and 0xFFF
            val ownerWritable = if (useAndroidOs) (perms and 0x80) != 0 else file.canWrite()
            if (!ownerWritable) {
                if (useAndroidOs) Os.chmod(file.absolutePath, perms or 0x80) else file.setWritable(true, true)
            }
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    for (offset in offsets) {
                        raf.seek(offset)
                        raf.write(new)
                    }
                }
            } finally {
                if (!ownerWritable) {
                    try {
                        if (useAndroidOs) Os.chmod(file.absolutePath, perms) else file.setReadOnly()
                    } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Rewriting ${file.absolutePath} failed: ${e.message}")
            false
        }
    }

    /**
     * [bytes] with every known foreign source package replaced by [newPackage], except "com.termux" followed by a
     * [KEPT_SUFFIXES] entry and, with [pathsOnly], occurrences not immediately after "/".
     */
    internal fun rewrite(bytes: ByteArray, newPackage: String, pathsOnly: Boolean = false): ByteArray {
        val new = newPackage.toByteArray(Charsets.US_ASCII)
        val patterns = sourcePatternsFor(newPackage)
        val out = bytes.copyOf()
        val n = TERMUX_PACKAGE.length
        var i = 0
        while (i <= out.size - n) {
            val matched = matchAnyAt(out, i, patterns)
            if (matched != null) {
                val inPath = !pathsOnly || (i > 0 && out[i - 1] == SLASH)
                val kept = matched.contentEquals(TERMUX_BYTES) && keptAt(out, i + n, out.size)
                if (inPath && !kept) System.arraycopy(new, 0, out, i, new.size)
                i += n
            } else i++
        }
        return out
    }

    private fun matchAnyAt(buf: ByteArray, at: Int, patterns: List<ByteArray>): ByteArray? {
        val first = buf[at]
        for (p in patterns) {
            if (p[0] == first && matchesAt(buf, at, p)) return p
        }
        return null
    }

    private fun matchesAt(buf: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for (k in 1 until pattern.size) if (buf[at + k] != pattern[k]) return false
        return true
    }

    /** Whether a [KEPT_SUFFIXES] entry starts at [at] (within the first [len] bytes of [buf]). */
    private fun keptAt(buf: ByteArray, at: Int, len: Int): Boolean =
        KEPT_SUFFIXES.any { s -> at + s.size <= len && buf[at] == s[0] && matchesAt(buf, at, s) }

    internal fun findAll(file: File, pattern: ByteArray, pathsOnly: Boolean = false): List<Long> =
        findAll(file, listOf(pattern), pathsOnly)

    /**
     * Offsets of every match among [patterns] (all 10 bytes, mutually non-overlapping) in [file] that should be
     * rewritten (not "com.termux" followed by a [KEPT_SUFFIXES] entry), read in 256 KB chunks.
     */
    internal fun findAll(file: File, patterns: List<ByteArray>, pathsOnly: Boolean = false): List<Long> {
        val result = ArrayList<Long>()
        if (patterns.isEmpty()) return result
        val n = TERMUX_PACKAGE.length
        val validFirst = BooleanArray(256)
        for (p in patterns) validFirst[p[0].toInt() and 0xFF] = true
        val buf = ByteArray(CHUNK + n - 1 + LOOKAHEAD)
        RandomAccessFile(file, "r").use { raf ->
            var base = 0L // file offset of buf[0]
            var len = 0 // bytes in buf
            var before: Byte? = null // the byte before buf[0] (none at the start of the file)
            while (true) {
                val read = raf.read(buf, len, buf.size - len)
                val eof = read <= 0
                if (!eof) len += read
                // until the end of the file, a match needs its lookahead in the buffer to be checked
                val last = if (eof) len - n else len - n - LOOKAHEAD
                var i = 0
                while (i <= last) {
                    if (validFirst[buf[i].toInt() and 0xFF]) {
                        val matched = matchAnyAt(buf, i, patterns)
                        if (matched != null) {
                            val inPath = !pathsOnly || (if (i > 0) buf[i - 1] else before) == SLASH
                            val kept = matched.contentEquals(TERMUX_BYTES) && keptAt(buf, i + n, len)
                            if (inPath && !kept) result.add(base + i)
                            i += n
                            continue
                        }
                    }
                    i++
                }
                if (eof) break
                // the bytes not scanned yet (at most n-1+LOOKAHEAD) move to the start of the buffer
                val keepFrom = i.coerceAtMost(len)
                if (keepFrom > 0) before = buf[keepFrom - 1]
                System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom)
                len -= keepFrom
                base += keepFrom
            }
        }
        return result
    }

    /**
     * Keeps package installs working: Termux .debs are built for com.termux.
     * - apt (`pkg install`, `apt upgrade`, `apt install ./x.deb`): the `DPkg::Pre-Install-Pkgs` hook in
     *   apt.conf.d/99lytsocoder-relocate rewrites each .deb before dpkg unpacks it.
     * - dpkg run by hand (`dpkg -i x.deb`): dpkg is diverted to bin/dpkg.distrib with dpkg-divert (dpkg upgrades
     *   then keep the real binary there) and a wrapper in its place rewrites copies of the .debs first.
     * Called after each install and terminal start; only writes what changed.
     */
    fun ensurePackageHooks(context: Context) {
        val pkg = targetPackage(context) ?: return
        val prefixDir = LocalEnvironmentManager.getPrefixDir(context)
        if (!File(prefixDir, "etc/apt").isDirectory) return
        // the path dpkg knows its files by (its database lists /data/data/…, not /data/user/0/…)
        val prefix = "/data/data/$pkg/files/usr"
        val relocator = "$prefix/libexec/lytsocoder/relocate-debs"
        try {
            // the bridge holds the fast .deb relocator (relocate-debs) these hooks run: unpacked here too, so it always
            // comes from the same app version as them
            LocalServerManager.ensureBridgeBinary(context)
        } catch (e: Exception) {
            Log.w(TAG, "bridge unpack failed (the shell relocator is used): ${e.message}")
        }
        try {
            runCatching { File(prefixDir, "etc/apt/apt.conf.d/99antigem-relocate").delete() }
            runCatching { File(prefixDir, "libexec/antigem").deleteRecursively() }
            writeIfChanged(File(prefixDir, "libexec/lytsocoder/relocate-debs"), relocateScript(prefix, pkg), executable = true)
            writeIfChanged(
                File(prefixDir, "etc/apt/apt.conf.d/99lytsocoder-relocate"),
                """
                |// Written by LytsoCoder (edits are overwritten). Termux packages are built for $TERMUX_PACKAGE: this hook
                |// rewrites each .deb for $pkg before dpkg installs it.
                |DPkg::Pre-Install-Pkgs { "$relocator --apt"; };
                |""".trimMargin(),
                executable = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "apt relocation hook setup failed: ${e.message}")
        }
        try {
            ensureDpkgWrapper(prefixDir, prefix, relocator)
        } catch (e: Exception) {
            Log.w(TAG, "dpkg wrapper setup failed: ${e.message}")
        }
        try {
            ensureAmApk(context, prefixDir, prefix)
        } catch (e: Exception) {
            Log.w(TAG, "am.apk setup failed: ${e.message}")
        }
    }

    private fun ensureDpkgWrapper(prefixDir: File, prefix: String, relocator: String) {
        val dpkg = File(prefixDir, "bin/dpkg")
        // only the real dpkg is ever diverted, never a wrapper left without its target
        if (!File(prefixDir, "bin/dpkg.distrib").isFile && isScript(dpkg)) return
        if (!ensureDiverted(prefixDir, prefix, "bin/dpkg")) return
        writeIfChanged(dpkg, dpkgWrapper(prefix, relocator), executable = true)
    }

    /**
     * termux-am's am.apk (the Java code of the `am` command) built for this app: Termux's one sends intents as
     * com.termux, which Android refuses here, so termux-open, termux-wake-lock, termux-setup-storage and the
     * Termux:API commands failed. Ours (assets/bin/am.apk, built from the termux-am submodule by
     * termux-am-build/build.gradle.kts) sends them as this app. Termux's is diverted to am.apk.distrib, where
     * termux-am updates go too.
     */
    private fun ensureAmApk(context: Context, prefixDir: File, prefix: String) {
        val path = "libexec/termux-am/am.apk"
        val target = File(prefixDir, path)
        if (target.parentFile?.isDirectory != true) return // termux-am isn't installed
        val ours = try {
            context.assets.open("bin/am.apk").use { it.readBytes() }
        } catch (_: Exception) {
            return
        }
        if (target.isFile && target.length() == ours.size.toLong() && target.readBytes().contentEquals(ours)) return
        if (!ensureDiverted(prefixDir, prefix, path)) return
        // Android 14+ refuses to load a writable dex file: it is written read-only (0400), as the am script does too
        val tmp = File(target.parentFile, ".am.apk.tmp")
        tmp.delete()
        tmp.writeBytes(ours)
        Os.chmod(tmp.absolutePath, 0x100)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /**
     * The termux-api package's libexec/termux-api (a symlink to termux-api-broadcast) diverted to termux-api.distrib,
     * so LytsoCoder's dispatcher can sit in its place (LocalEnvironmentManager.ensureTermuxApiDispatcher). Without the
     * package (yet), the diversion is only registered: when it is installed later, its file goes to .distrib straight
     * away. True when diverted.
     */
    internal fun divertTermuxApi(context: Context): Boolean {
        val pkg = targetPackage(context) ?: return false
        val prefixDir = LocalEnvironmentManager.getPrefixDir(context)
        val path = "libexec/termux-api"
        val current = File(prefixDir, path)
        val isLink = try { OsConstants.S_ISLNK(Os.lstat(current.absolutePath).st_mode) } catch (_: Exception) { false }
        // a symlink there is the package's (moved aside); a plain file is LytsoCoder's own dispatcher (stays)
        return ensureDiverted(prefixDir, "/data/data/$pkg/files/usr", path, rename = isLink)
    }

    /** Whether dpkg diverts [prefix]/[path] (var/lib/dpkg/diversions: three lines per diversion, the path first). */
    private fun isDiverted(prefixDir: File, prefix: String, path: String): Boolean {
        val lines = try { File(prefixDir, "var/lib/dpkg/diversions").readLines() } catch (_: Exception) { return false }
        return (lines.indices step 3).any { lines[it] == "$prefix/$path" }
    }

    /**
     * Diverts [path] (relative to the prefix) to `<path>.distrib` with dpkg-divert, so the package that owns it
     * installs its new versions there and the file in its place can be LytsoCoder's. [rename]: move the file there now
     * (it must exist). True when it is diverted (now or before); false when it couldn't be (e.g. apt is running and
     * holds the lock: tried again at the next start).
     */
    private fun ensureDiverted(prefixDir: File, prefix: String, path: String, rename: Boolean = true): Boolean {
        if (isDiverted(prefixDir, prefix, path)) return true
        if (rename && runCatching { Os.lstat(File(prefixDir, path).absolutePath) }.isFailure) return false
        val divert = File(prefixDir, "bin/dpkg-divert")
        if (!divert.canExecute()) return false
        val args = mutableListOf(divert.absolutePath, "--add")
        if (rename) args += "--rename"
        args += listOf("--divert", "$prefix/$path.distrib", "$prefix/$path")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().apply {
            put("PREFIX", prefix)
            put("TMPDIR", "$prefix/tmp")
            put("PATH", "$prefix/bin:/system/bin")
            remove("LD_PRELOAD")
        }
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) proc.destroyForcibly()
        val ok = done && proc.exitValue() == 0 && isDiverted(prefixDir, prefix, path)
        if (!ok) Log.w(TAG, "dpkg-divert $path failed: ${output.trim()}")
        return ok
    }

    private fun isScript(file: File): Boolean = try {
        file.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte() }
    } catch (_: Exception) { false }

    /** Writes [file] only when its content differs: via a temporary file and a rename, so a symlink there is replaced, never written through. */
    internal fun writeIfChanged(file: File, content: String, executable: Boolean) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content)
        try { Os.chmod(tmp.absolutePath, if (executable) 0x1ED else 0x1A4) } catch (_: Exception) {} // 0755 / 0644
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** relocate-debs: rewrites the .debs whose paths it reads on stdin, in place (used by the apt hook and the dpkg wrapper). */
    internal fun relocateScript(prefix: String, pkg: String): String {
        val skipped = SKIPPED_EXTENSIONS.joinToString("|") { "*.$it" }
        val fastKeep = KEPT_SUFFIXES.joinToString(" ") { "--keep '${String(it, Charsets.US_ASCII)}'" }
        val fastSkip = SKIPPED_EXTENSIONS.joinToString(",")
        val oldRegex = TERMUX_PACKAGE.replace(".", "\\.")
        // [KEPT_SUFFIXES] become "\x01om.termux<suffix>" (same length) first, and back after the rewrite
        val placeholder = "\\x01" + TERMUX_PACKAGE.drop(1)
        val sedProg = KEPT_SUFFIXES.joinToString("") { b ->
            val suffix = String(b, Charsets.US_ASCII)
            "s/$oldRegex${suffix.replace(".", "\\.")}/$placeholder$suffix/g; "
        } + "s/$oldRegex/$pkg/g; s/${placeholder.replace(".", "\\.")}/$TERMUX_PACKAGE/g"
        return """
            |#!$prefix/bin/sh
            |# Written by LytsoCoder (edits are overwritten). Termux packages are built for /data/data/$TERMUX_PACKAGE.
            |# Reads .deb paths on stdin (apt's DPkg::Pre-Install-Pkgs hook, apt.conf.d/99lytsocoder-relocate, and the dpkg
            |# wrapper call it) and rewrites each .deb in place for $pkg: every "$TERMUX_PACKAGE" in paths, file contents,
            |# symlinks and maintainer scripts becomes "$pkg" (same length, so binaries keep their layout).
            |# --apt (the hook): only the .debs apt downloaded are rewritten; a .deb of the user's (apt install ./x.deb)
            |# stays as it is and the dpkg wrapper rewrites a copy when apt hands it to dpkg.
            |OLD='$TERMUX_PACKAGE'
            |NEW='$pkg'
            |export LC_ALL=C
            |umask 022
            |TMP="${'$'}{TMPDIR:-$prefix/tmp}"
            |SED_PROG='$sedProg'
            |# The fast relocator (the IDE bridge's relocate-debs: one pass per package, several packages at once) when this
            |# bridge has it: its marker text is in the file (an older bridge is never run, it would start its server).
            |# This script's own code below is the fallback.
            |FAST='$prefix/bin/agy_ide_bridge'
            |if [ -x "${'$'}FAST" ] && grep -qa '$FAST_RELOCATOR_MARKER' "${'$'}FAST" 2>/dev/null; then
            |    exec "${'$'}FAST" relocate-debs --from "${'$'}OLD" --to "${'$'}NEW" $fastKeep --skip-ext '$fastSkip' "${'$'}@"
            |fi
            |APT_MODE=0
            |[ "${'$'}{1:-}" = "--apt" ] && APT_MODE=1
            |
            |relocate_deb() {
            |    work=${'$'}(mktemp -d "${'$'}TMP/lytsocoder-deb.XXXXXX") || return 1
            |    if ! dpkg-deb -R "${'$'}1" "${'$'}work/pkg"; then rm -rf "${'$'}work"; return 1; fi
            |    # already for ${'$'}NEW (or nothing of ${'$'}OLD in it): left as it is
            |    if [ ! -d "${'$'}work/pkg/data/data/${'$'}OLD" ] && ! grep -rqF "${'$'}OLD" "${'$'}work/pkg" 2>/dev/null; then
            |        rm -rf "${'$'}work"; return 0
            |    fi
            |    echo "LytsoCoder: adapting ${'$'}{1##*/} for ${'$'}NEW"
            |    if [ -d "${'$'}work/pkg/data/data/${'$'}OLD" ]; then
            |        mv "${'$'}work/pkg/data/data/${'$'}OLD" "${'$'}work/pkg/data/data/${'$'}NEW" || { rm -rf "${'$'}work"; return 1; }
            |    fi
            |    grep -rlF "${'$'}OLD" "${'$'}work/pkg" 2>/dev/null > "${'$'}work/files"
            |    while IFS= read -r f; do
            |        case "${'$'}f" in $skipped) continue ;; esac
            |        [ -L "${'$'}f" ] && continue
            |        [ -w "${'$'}f" ] || chmod u+w "${'$'}f"
            |        sed -i "${'$'}SED_PROG" "${'$'}f" || { rm -rf "${'$'}work"; return 1; }
            |    done < "${'$'}work/files"
            |    find "${'$'}work/pkg" -type l > "${'$'}work/links"
            |    while IFS= read -r l; do
            |        t=${'$'}(readlink "${'$'}l")
            |        case "${'$'}t" in
            |            *"${'$'}OLD"*) ln -sfn "${'$'}(printf '%s' "${'$'}t" | sed "${'$'}SED_PROG")" "${'$'}l" || { rm -rf "${'$'}work"; return 1; } ;;
            |        esac
            |    done < "${'$'}work/links"
            |    # md5sums (dpkg --verify, debsums) describe the rewritten files
            |    if [ -f "${'$'}work/pkg/DEBIAN/md5sums" ]; then
            |        ( cd "${'$'}work/pkg" && cut -c35- DEBIAN/md5sums | xargs -r -d '\n' md5sum ) > "${'$'}work/md5sums" &&
            |            mv -f "${'$'}work/md5sums" "${'$'}work/pkg/DEBIAN/md5sums"
            |    fi
            |    chmod 0755 "${'$'}work/pkg/DEBIAN"
            |    for s in preinst postinst prerm postrm config; do
            |        [ -f "${'$'}work/pkg/DEBIAN/${'$'}s" ] && chmod 0755 "${'$'}work/pkg/DEBIAN/${'$'}s"
            |    done
            |    # gzip -1: quick, and far smaller than an uncompressed .deb on the phone's storage
            |    if ! dpkg-deb --root-owner-group -Zgzip -z1 -b "${'$'}work/pkg" "${'$'}work/out.deb" >/dev/null; then
            |        rm -rf "${'$'}work"; return 1
            |    fi
            |    mv -f "${'$'}work/out.deb" "${'$'}1"
            |    status=${'$'}?
            |    rm -rf "${'$'}work"
            |    return ${'$'}status
            |}
            |
            |fail=0
            |while IFS= read -r deb; do
            |    case "${'$'}deb" in *.deb) ;; *) continue ;; esac
            |    [ -f "${'$'}deb" ] || continue
            |    if [ "${'$'}APT_MODE" = 1 ]; then
            |        case "${'$'}deb" in */cache/apt/archives/*) ;; *) continue ;; esac
            |    fi
            |    relocate_deb "${'$'}deb" || { echo "LytsoCoder: could not adapt ${'$'}deb" >&2; fail=1; }
            |done
            |exit ${'$'}fail
            |""".trimMargin()
    }

    /** Installed as bin/dpkg (the real one is bin/dpkg.distrib): adapts .debs given by hand before dpkg installs them. */
    internal fun dpkgWrapper(prefix: String, relocator: String): String = """
        |#!$prefix/bin/sh
        |# Written by LytsoCoder (edits are overwritten). The real dpkg is dpkg.distrib (moved there with dpkg-divert, so
        |# dpkg upgrades keep it there). Termux .debs are built for /data/data/$TERMUX_PACKAGE: when dpkg installs .deb
        |# files given by hand (dpkg -i x.deb, dpkg -R folder), copies of them are rewritten for this app first; the
        |# files themselves stay. apt (which holds the frontend lock) has rewritten the .debs it downloaded already
        |# (apt.conf.d/99lytsocoder-relocate) and passes them by path, or, when they are many, as symlinks in a folder of its
        |# own (dpkg --recursive): those go through untouched; only a .deb of the user's (apt install ./x.deb) gets a copy.
        |REAL='$prefix/bin/dpkg.distrib'
        |locked=0
        |[ -n "${'$'}{DPKG_FRONTEND_LOCKED:-}" ] && locked=1
        |
        |# whether ${'$'}1 is a .deb to adapt (symlinks followed)
        |needs() {
        |    [ -f "${'$'}1" ] || return 1
        |    case "${'$'}1" in *.deb) ;; *) return 1 ;; esac
        |    if [ "${'$'}locked" = 1 ]; then
        |        target=${'$'}(readlink -f "${'$'}1" 2>/dev/null || printf '%s' "${'$'}1")
        |        case "${'$'}target" in */cache/apt/archives/*) return 1 ;; esac
        |    fi
        |    return 0
        |}
        |
        |# whether the folder ${'$'}1 (dpkg --recursive) holds a .deb to adapt
        |dir_needs() {
        |    find -L "${'$'}1" -type f -name '*.deb' 2>/dev/null | while IFS= read -r d; do
        |        if needs "${'$'}d"; then echo yes; break; fi
        |    done | grep -q yes
        |}
        |
        |install=0
        |recursive=0
        |for a in "${'$'}@"; do
        |    case "${'$'}a" in
        |        --install|--unpack|--record-avail) install=1 ;;
        |        --recursive) recursive=1 ;;
        |        --*) ;;
        |        -?*)
        |            case "${'$'}a" in *[iA]*) install=1 ;; esac
        |            case "${'$'}a" in *R*) recursive=1 ;; esac
        |            ;;
        |    esac
        |done
        |found=0
        |if [ "${'$'}install" = 1 ]; then
        |    for a in "${'$'}@"; do
        |        if needs "${'$'}a"; then found=1; break; fi
        |        if [ "${'$'}recursive" = 1 ] && [ -d "${'$'}a" ] && dir_needs "${'$'}a"; then found=1; break; fi
        |    done
        |fi
        |# nothing to adapt (always so for apt's own .debs): dpkg as it was called
        |[ "${'$'}found" = 1 ] || exec "${'$'}REAL" "${'$'}@"
        |
        |TMP="${'$'}{TMPDIR:-$prefix/tmp}"
        |work=${'$'}(mktemp -d "${'$'}TMP/lytsocoder-dpkg.XXXXXX") || exit 1
        |trap 'rm -rf "${'$'}work"' EXIT
        |trap 'exit 130' INT TERM
        |n=0
        |# copies ${'$'}1 into the work folder and rewrites the copy; ${'$'}adapted is its path
        |adapt() {
        |    n=${'$'}((n + 1))
        |    adapted="${'$'}work/${'$'}n-${'$'}{1##*/}"
        |    cp "${'$'}1" "${'$'}adapted" && printf '%s\n' "${'$'}adapted" | '$relocator' >&2
        |}
        |
        |count=${'$'}#
        |i=0
        |while [ "${'$'}i" -lt "${'$'}count" ]; do
        |    a=${'$'}1
        |    shift
        |    i=${'$'}((i + 1))
        |    if needs "${'$'}a"; then
        |        adapt "${'$'}a" || { echo "dpkg (LytsoCoder): could not adapt ${'$'}a" >&2; exit 1; }
        |        a=${'$'}adapted
        |    elif [ "${'$'}recursive" = 1 ] && [ -d "${'$'}a" ] && dir_needs "${'$'}a"; then
        |        # a folder with the same .debs: adapted copies of those that need it, links to the others
        |        dir="${'$'}work/dir${'$'}i"
        |        mkdir -p "${'$'}dir"
        |        find -L "${'$'}a" -type f -name '*.deb' > "${'$'}work/list${'$'}i"
        |        while IFS= read -r d; do
        |            if needs "${'$'}d"; then
        |                adapt "${'$'}d" || { echo "dpkg (LytsoCoder): could not adapt ${'$'}d" >&2; exit 1; }
        |                mv "${'$'}adapted" "${'$'}dir/"
        |            else
        |                n=${'$'}((n + 1))
        |                ln -s "${'$'}(readlink -f "${'$'}d")" "${'$'}dir/${'$'}n-${'$'}{d##*/}"
        |            fi
        |        done < "${'$'}work/list${'$'}i"
        |        a=${'$'}dir
        |    fi
        |    set -- "${'$'}@" "${'$'}a"
        |done
        |"${'$'}REAL" "${'$'}@"
        |""".trimMargin()
}
