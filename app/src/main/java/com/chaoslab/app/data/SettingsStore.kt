package com.chaoslab.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chaosDataStore: DataStore<Preferences> by preferencesDataStore(name = "chaos_settings")

data class ChaosSettings(
    val particleBudget: Int = 8000,
    val autoQuality: Boolean = true,
    val trails: Boolean = true,
    val grid: Boolean = true,
    val collisions: Boolean = true,
    val recycle: Boolean = true,
    val palette: Int = 0,
    val hintsSeen: Boolean = false,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.chaosDataStore

    private object Keys {
        val BUDGET = intPreferencesKey("particle_budget")
        val AUTO_QUALITY = booleanPreferencesKey("auto_quality")
        val TRAILS = booleanPreferencesKey("trails")
        val GRID = booleanPreferencesKey("grid")
        val COLLISIONS = booleanPreferencesKey("collisions")
        val RECYCLE = booleanPreferencesKey("recycle")
        val PALETTE = intPreferencesKey("palette")
        val HINTS_SEEN = booleanPreferencesKey("hints_seen")
    }

    val settings: Flow<ChaosSettings> = store.data.map { p ->
        ChaosSettings(
            particleBudget = p[Keys.BUDGET] ?: 8000,
            autoQuality = p[Keys.AUTO_QUALITY] ?: true,
            trails = p[Keys.TRAILS] ?: true,
            grid = p[Keys.GRID] ?: true,
            collisions = p[Keys.COLLISIONS] ?: true,
            recycle = p[Keys.RECYCLE] ?: true,
            palette = p[Keys.PALETTE] ?: 0,
            hintsSeen = p[Keys.HINTS_SEEN] ?: false,
        )
    }

    suspend fun setBudget(v: Int) = store.edit { it[Keys.BUDGET] = v }
    suspend fun setAutoQuality(v: Boolean) = store.edit { it[Keys.AUTO_QUALITY] = v }
    suspend fun setTrails(v: Boolean) = store.edit { it[Keys.TRAILS] = v }
    suspend fun setGrid(v: Boolean) = store.edit { it[Keys.GRID] = v }
    suspend fun setCollisions(v: Boolean) = store.edit { it[Keys.COLLISIONS] = v }
    suspend fun setRecycle(v: Boolean) = store.edit { it[Keys.RECYCLE] = v }
    suspend fun setPalette(v: Int) = store.edit { it[Keys.PALETTE] = v }
    suspend fun setHintsSeen(v: Boolean) = store.edit { it[Keys.HINTS_SEEN] = v }
}
