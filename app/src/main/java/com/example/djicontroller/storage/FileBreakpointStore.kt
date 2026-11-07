package com.example.djicontroller.storage

import android.content.Context
import com.example.djicontroller.core.port.BreakpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trwały punkt przerwania patrolu (sekcja 4) w `SharedPreferences`.
 * Zapis przez `commit()` na wątku IO — wartość musi przetrwać nagłe zabicie procesu.
 */
class FileBreakpointStore(context: Context) : BreakpointStore {

    private val prefs = context.applicationContext.getSharedPreferences("patrol_breakpoints", Context.MODE_PRIVATE)

    override suspend fun load(routeId: String): Int? = withContext(Dispatchers.IO) {
        if (prefs.contains(routeId)) prefs.getInt(routeId, 0) else null
    }

    override suspend fun save(routeId: String, waypointIndex: Int) {
        withContext(Dispatchers.IO) { prefs.edit().putInt(routeId, waypointIndex).commit() }
    }

    override suspend fun clear(routeId: String) {
        withContext(Dispatchers.IO) { prefs.edit().remove(routeId).commit() }
    }
}
