package com.example.shizuku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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
        val clampedScale = scale.coerceIn(0.60f, 1.0f)
        if (clampedScale >= 0.98f) return@withContext resetRenderScale(packageName)

        val pkg = packageName.ifBlank {
            scriptManager.executeCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | grep -oE '[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+' | grep -v 'gamebooster' | grep -v 'android' | grep -v 'miui' | head -n 1").stdout.trim()
        }

        val sizeOut = scriptManager.executeCommand("wm size").stdout
        val m = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeOut)
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1220
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 2712

        val newW = ((w * clampedScale).toInt() / 2) * 2
        val newH = ((h * clampedScale).toInt() / 2) * 2
        val dOut = scriptManager.executeCommand("wm density").stdout
        val d = Regex("Physical density:\\s*(\\d+)").find(dOut)?.groupValues?.get(1)?.toIntOrNull() ?: 480
        val newD = ((d * clampedScale).toInt()).coerceAtLeast(240)

        val cmd = if (pkg.isNotBlank()) {
            "wm size ${newW}x${newH} ; wm density $newD ; am force-stop $pkg"
        } else {
            "wm size ${newW}x${newH} ; wm density $newD"
        }

        scriptManager.executeCommand(cmd)
        _state.update { it.copy(renderScale = clampedScale, isDownscaleActive = true, isOperating = false, statusMessage = "Activo ${(clampedScale * 100).toInt()}% (${newW}x${newH})") }
        true
    }

    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        scriptManager.executeCommand("wm size reset ; wm density reset")
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
