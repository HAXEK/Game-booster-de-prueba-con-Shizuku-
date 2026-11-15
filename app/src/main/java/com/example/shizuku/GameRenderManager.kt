package com.example.shizuku

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

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
    companion object {
        private const val TAG = "GameRenderManager"
    }

    private val _state = MutableStateFlow(GameRenderState())
    val state: StateFlow<GameRenderState> = _state.asStateFlow()

    private suspend fun getPhysicalDisplayMetrics(): Triple<Int, Int, Int>? {
        val sizeRes = scriptManager.executeCommand("wm size")
        val densityRes = scriptManager.executeCommand("wm density")
        if (!sizeRes.isSuccess || !densityRes.isSuccess) return null

        val sizeMatch = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeRes.stdout)
        val densityMatch = Regex("Physical density:\\s*(\\d+)").find(densityRes.stdout)

        val width = sizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = sizeMatch.groupValues[2].toIntOrNull() ?: return null
        val density = densityMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null

        return Triple(width, height, density)
    }

        suspend fun applyRenderScale(packageName: String, scale: Float): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext false
        val clampedScale = scale.coerceIn(0.50f, 1.0f)
        val formattedScale = String.format(Locale.US, "%.2f", clampedScale)

        _state.update { it.copy(isOperating = true, statusMessage = "Aplicando Modo Ligero ${(clampedScale * 100).toInt()}%...") }

        if (clampedScale >= 0.98f) return@withContext resetRenderScale(packageName)

        val cmd = "device_config set_sync_disabled_for_tests persistent ; " +
                  "device_config put game_overlay $packageName \"mode=2,downscaleFactor=$formattedScale\" && " +
                  "cmd game mode performance $packageName ; " +
                  "am force-stop $packageName && " +
                  "monkey -p $packageName -c android.intent.category.LAUNCHER 1"

        val result = scriptManager.executeCommand(cmd)

        _state.update {
            it.copy(
                renderScale = clampedScale,
                isDownscaleActive = result.isSuccess,
                isOperating = false,
                statusMessage = if (result.isSuccess) "Modo Ligero activo: ${(clampedScale * 100).toInt()}%" else "No soportado por el juego"
            )
        }
        result.isSuccess
    }

    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val cmd = "device_config delete game_overlay $packageName ; cmd game reset $packageName"
        scriptManager.executeCommand(cmd)
        _state.update {
            it.copy(renderScale = 1.0f, isDownscaleActive = false, isOperating = false, statusMessage = "Resolución nativa restaurada (100%)")
        }
        true
    }


    suspend fun toggleDisableMsaa(disable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cmd = if (disable) {
            "settings put global debug.hwui.force_msaa 0 ; setprop debug.egl.force_msaa 0 2>/dev/null ; echo ok"
        } else {
            "settings delete global debug.hwui.force_msaa ; echo ok"
        }
        val result = scriptManager.executeCommand(cmd)
        val success = result.isSuccess

        _state.update {
            it.copy(
                isMsaaDisabled = if (success) disable else it.isMsaaDisabled,
                statusMessage = if (success) {
                    if (disable) "Filtros pesados desactivados" else "Filtros GPU restablecidos"
                } else {
                    "Error filtros: ${result.stderr}"
                }
            )
        }

        success
    }

    suspend fun resetAll(packageName: String?): Boolean = withContext(Dispatchers.IO) {
        resetRenderScale(packageName ?: "")
        toggleDisableMsaa(false)
        true
    }
}
