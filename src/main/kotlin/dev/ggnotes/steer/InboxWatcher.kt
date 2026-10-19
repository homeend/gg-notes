package dev.ggnotes.steer

import com.intellij.openapi.diagnostic.logger
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * A directory watcher on the sub-inboxes and the docs directories that wakes
 * the drain and the docs reload at once; the polls stay as the fallback for
 * mounts that deliver no events. Its own
 * daemon thread, never the application pool (a blocking take there would
 * starve it); a watcher that dies is restarted by the next [start].
 */
class InboxWatcher(private val dirs: List<Path>, private val onWake: () -> Unit) {

    private val LOG = logger<InboxWatcher>()
    @Volatile private var service: WatchService? = null
    @Volatile private var thread: Thread? = null

    fun start() {
        try {
            val ws = FileSystems.getDefault().newWatchService()
            for (d in dirs) runCatching { d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE) }
            service = ws
            thread = Thread({ loop(ws) }, "GgNotes steer watcher").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            LOG.info("GgNotes steer: no directory watcher (${e.message}); polling only")
        }
    }

    /**
     * Watch one more directory (a root detected after start, a docs directory that appeared).
     * [createOnly] for the inbox itself: only a `docs/` appearing matters there, and the IDE's own
     * presence touch would otherwise wake the reload every second.
     */
    fun add(dir: Path, createOnly: Boolean = false) {
        val ws = service ?: return
        runCatching {
            if (createOnly) dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE)
            else dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
        }
    }

    private fun loop(ws: WatchService) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
                key.pollEvents()
                key.reset()
                runCatching { onWake() }
            }
        } catch (e: InterruptedException) {
            // stopped
        } catch (e: java.nio.file.ClosedWatchServiceException) {
            // stopped
        } catch (t: Throwable) {
            LOG.info("GgNotes steer: watcher ended (${t.message}); polling continues")
        }
    }

    fun stop() {
        thread?.interrupt()
        runCatching { service?.close() }
    }
}
