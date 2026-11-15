package ro.camara.app.ui

import android.content.Context

enum class CamaraPalette(val label: String) {
    Sage("Salvie"), Ocean("Ocean"), Lavender("Lavandă"), Terracotta("Teracotă"), Ink("Grafit"),
}

enum class CamaraMode(val label: String) {
    System("Sistem"), Light("Luminos"), Dark("Întunecat"),
}

data class CamaraAppearance(
    val palette: CamaraPalette = CamaraPalette.Sage,
    val mode: CamaraMode = CamaraMode.System,
    val motion: Boolean = true,
) {
    fun dark(systemDark: Boolean): Boolean = when (mode) {
        CamaraMode.System -> systemDark
        CamaraMode.Light -> false
        CamaraMode.Dark -> true
    }
}

internal class AppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun read(): CamaraAppearance {
        val palette = preferences.getString("palette", null)
        val mode = preferences.getString("mode", null)
        return CamaraAppearance(
            palette = CamaraPalette.entries.firstOrNull { it.name == palette } ?: CamaraPalette.Sage,
            mode = CamaraMode.entries.firstOrNull { it.name == mode } ?: CamaraMode.System,
            motion = preferences.getBoolean("motion", true),
        )
    }

    fun save(appearance: CamaraAppearance) {
        preferences.edit()
            .putString("palette", appearance.palette.name)
            .putString("mode", appearance.mode.name)
            .putBoolean("motion", appearance.motion)
            .apply()
    }
}
