package com.dimax.flash

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central registry for feature flags, the same idea as Impulse's FeatureFlags.kt: add one entry here
 * and it shows up in Settings > Feature flags by itself, because that screen iterates [entries].
 *
 * [defaultValue] is what "Default" resolves to today. A flag the user has set by hand keeps that value
 * until it is set back to Default, so flipping a default later does not undo anyone's choice.
 */
enum class FeatureFlag(
    val key: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val defaultValue: Boolean
) {
    /** The Mother Core leans downhill when the phone tilts and wobbles when it is shaken. */
    CORE_MOTION(
        key = "core_motion",
        titleRes = R.string.feature_flag_core_motion_title,
        descriptionRes = R.string.feature_flag_core_motion_description,
        defaultValue = false
    ),

    /** The Mother Core is a rounded 3D object fixed in space; it lies flat or stands upright. */
    CORE_3D(
        key = "core_3d",
        titleRes = R.string.feature_flag_core_3d_title,
        descriptionRes = R.string.feature_flag_core_3d_description,
        defaultValue = false
    )
}

/** What a flag is right now, given the manual overrides (null = Default). */
fun Map<FeatureFlag, Boolean?>.isOn(flag: FeatureFlag): Boolean = this[flag] ?: flag.defaultValue

/**
 * Manual overrides for [FeatureFlag]s, kept in plain SharedPreferences like Impulse's
 * FeatureFlagPreferences. A key that is absent means "Default" and resolves to the flag's own
 * [FeatureFlag.defaultValue]; [overrides] lets screens react when one changes.
 */
class FeatureFlagStore(context: Context) {

    private val prefs = context.getSharedPreferences("feature_flags", Context.MODE_PRIVATE)

    private val _overrides = MutableStateFlow(FeatureFlag.entries.associateWith { read(it) })

    /** null = Default (nothing stored), true = forced on, false = forced off. */
    val overrides: StateFlow<Map<FeatureFlag, Boolean?>> = _overrides.asStateFlow()

    fun isEnabled(flag: FeatureFlag): Boolean = _overrides.value.isOn(flag)

    /** Pass null to go back to Default. */
    fun setOverride(flag: FeatureFlag, override: Boolean?) {
        prefs.edit {
            if (override == null) remove(flag.key) else putBoolean(flag.key, override)
        }
        _overrides.value = _overrides.value + (flag to override)
    }

    private fun read(flag: FeatureFlag): Boolean? =
        if (prefs.contains(flag.key)) prefs.getBoolean(flag.key, false) else null
}
