package com.example.calendaralarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * The persisted alarm instances: one JSON document, rewritten atomically and synchronously.
 * Nothing is cached in memory, so every reader sees the latest committed state.
 */
class AlarmStore(private val file: File) {

    fun all(): List<AlarmInstance> = locked { read() }

    fun find(id: String): AlarmInstance? = all().find { it.id == id }

    /** Ringing alarms, the one that started ringing most recently first. */
    fun ringing(): List<AlarmInstance> =
        all().filter { it.state == AlarmInstance.State.RINGING }.sortedByDescending { it.updatedMs }

    /** The alarm that started ringing most recently; the one the UI shows. */
    fun latestRinging(): AlarmInstance? = ringing().firstOrNull()

    /** Runs [block] holding the process-wide lock that serializes all store mutations. */
    fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    fun read(): List<AlarmInstance> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { AlarmInstance.fromJson(arr.getJSONObject(it)) }
    }

    fun write(instances: List<AlarmInstance>) {
        check(Thread.holdsLock(LOCK)) { "AlarmStore.write outside locked()" }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JSONArray().apply { instances.forEach { put(it.toJson()) } }.toString().toByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace $file" }
        writeCount.update { it + 1 }
    }

    companion object {
        private val LOCK = Any()
        private val writeCount = MutableStateFlow(0)

        /** Changes after every committed write, so in-process views can re-render. */
        val changes: StateFlow<Int> = writeCount.asStateFlow()

        fun from(context: Context) = AlarmStore(File(context.filesDir, "alarms.json"))
    }
}
