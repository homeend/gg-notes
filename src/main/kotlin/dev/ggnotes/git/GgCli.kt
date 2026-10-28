package dev.ggnotes.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * The gg executable, for the few things the plugin WRITES: it hands them to
 * gg (`gg batch`) instead of touching the store files, which gg owns. The
 * binary is `ggnotes.gg` (system property) or `gg` on PATH, resolved to an
 * absolute path so a stray `gg.exe` in the checkout is never picked up.
 * Background only.
 */
object GgCli {

    private val LOG = logger<GgCli>()

    class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** The executable to run, or null when gg is found nowhere [GgLocator] looks. */
    fun executable(): File? {
        System.getProperty("ggnotes.gg")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return GgLocator.find(
            path = System.getenv("PATH"),
            shellPath = EnvironmentUtil.getValue("PATH"),
            env = System::getenv,
            home = System.getProperty("user.home"),
            windows = SystemInfo.isWindows,
        )
    }

    /**
     * `gg batch --keep-going` in [workDir] with [lines] on stdin. Each line is one
     * gg command; gg answers with `#<n> ok|!<exit> <line>` sections and a `#done`
     * trailer. `ggnotes.env.<NAME>` system properties become environment
     * variables, the same override the store reader honours.
     */
    fun batch(workDir: File, lines: List<String>, timeoutSeconds: Long = 60): Result {
        if (ApplicationManager.getApplication()?.isDispatchThread == true) LOG.error("GgNotes: gg must not run on the EDT")
        val exe = executable() ?: return Result(127, "", "gg was not found on PATH or in Go's bin directory (~/go/bin)")
        return try {
            run(exe, workDir, lines, timeoutSeconds)
        } catch (t: Exception) {
            // a bad ggnotes.gg path, a file that is not executable, a closed pipe: the caller reports it
            LOG.info("GgNotes: gg batch failed to run: $t")
            Result(1, "", "cannot run ${exe.path}: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun run(exe: File, workDir: File, lines: List<String>, timeoutSeconds: Long): Result {
        val pb = ProcessBuilder(exe.absolutePath, "batch", "--keep-going").directory(workDir)
        for ((k, v) in System.getProperties()) {
            val key = k.toString()
            if (key.startsWith("ggnotes.env.")) pb.environment()[key.removePrefix("ggnotes.env.")] = v.toString()
        }
        val process = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outReader = Thread { process.inputStream.reader(StandardCharsets.UTF_8).use { out.append(it.readText()) } }.apply { isDaemon = true; start() }
        val errReader = Thread { process.errorStream.reader(StandardCharsets.UTF_8).use { err.append(it.readText()) } }.apply { isDaemon = true; start() }
        process.outputStream.writer(StandardCharsets.UTF_8).use { w -> for (l in lines) w.write(l + "\n") }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Result(124, out.toString(), "gg batch did not finish within $timeoutSeconds s")
        }
        outReader.join(5_000)
        errReader.join(5_000)
        return Result(process.exitValue(), out.toString(), err.toString())
    }
}
