package com.example.shizuku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale

data class GameRenderState(
    val renderScale: Float = 1.0f,
    val isDownscaleActive: Boolean = false,
    val isMsaaDisabled: Boolean = false,
    val isOperating: Boolean = false,
    val statusMessage: String = "Renderizado nativo (100%)"
)

class GameRenderManager(
    private val context: Context,
    private val scriptManager: ScriptManager
) {
    private val _state = MutableStateFlow(GameRenderState())
    val state: StateFlow<GameRenderState> = _state.asStateFlow()

            suspend fun applyRenderScale(packageName: String, scale: Float): Boolean = withContext(Dispatchers.IO) {
        // Bloque inicial de escalado de renderizado...
                
val clampedScale = scale.coerceIn(0.50f, 1.0f)
        val formattedScale = String.format(Locale.US, "%.2f", clampedScale)
                        val pkg = packageName.ifBlank { scriptManager.executeCommand("dumpsys activity activities | grep mResumedActivity | cut -d' ' -f8 | cut -d'/' -f1").stdout.trim() }
  if (pkg.isBlank() || pkg.contains("gamebooster")) return@withContext false
        if (clampedScale >= 0.98f) return@withContext resetRenderScale(pkg)
        val cmd = "wm size reset ; wm density reset ; cmd game mode 2 $pkg ; device_config put game_overlay $pkg mode=2,downscaleFactor=$formattedScale ; am force-stop $pkg"

        scriptManager.executeCommand(cmd)
        _state.update { it.copy(renderScale = clampedScale, isDownscaleActive = true, isOperating = false, statusMessage = "Listo: abre el juego (${(clampedScale * 100).toInt()}%)") }
        true
            }
    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val pkg = packageName.ifBlank { scriptManager.executeCommand("dumpsys activity activities | grep mResumedActivity | cut -d' ' -f8 | cut -d'/' -f1").stdout.trim() }
        if (pkg.isNotBlank()) {
            scriptManager.executeCommand("wm size reset ; wm density reset ; device_config delete game_overlay $pkg ; cmd game reset $pkg")
        }
        _state.update { it.copy(renderScale = 1.0f, isDownscaleActive = false, isOperating = false, statusMessage = "Nativo (100%)") }
        true
    }

    suspend fun toggleDisableMsaa(disable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = if (disable) "settings put global debug.hwui.force_msaa 0" else "settings delete global debug.hwui.force_msaa"
        val result = scriptManager.executeCommand(cmd)
        _state.update { it.copy(isMsaaDisabled = if (result.isSuccess) disable else it.isMsaaDisabled) }
        result.isSuccess
    }

    suspend fun resetAll(packageName: String?): Boolean = withContext(Dispatchers.IO) {
        resetRenderScale(packageName ?: "")
        toggleDisableMsaa(false)
        true
    }
}
