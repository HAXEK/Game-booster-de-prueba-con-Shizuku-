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
        val clampedScale = scale.coerceIn(0.50f, 1.0f)
        val formattedScale = String.format(Locale.US, "%.2f", clampedScale)

        _state.update {
            it.copy(
                isOperating = true,
                statusMessage = "Aplicando escala ${(clampedScale * 100).toInt()}% vía Shizuku..."
            )
        }

        if (clampedScale >= 0.98f) {
            return@withContext resetRenderScale(packageName)
        }

        val metrics = getPhysicalDisplayMetrics()
        if (metrics == null) {
            _state.update {
                it.copy(
                    isOperating = false,
                    statusMessage = "Error: No se pudo conectar con Shizuku para leer pantalla"
                )
            }
            return@withContext false
        }

        val (physW, physH, physDpi) = metrics
        // Asegurar dimensiones pares para evitar fallos en SurfaceFlinger
        val targetW = ((physW * clampedScale).roundToInt() / 2) * 2
        val targetH = ((physH * clampedScale).roundToInt() / 2) * 2
        val targetDpi = (physDpi * clampedScale).roundToInt().coerceAtLeast(160)

        val cmd = buildString {
            append("wm size ${targetW}x${targetH} && ")
            append("wm density $targetDpi && ")
            append("cmd power set-fixed-performance-mode-enabled true ; ")
            append("cmd game mode performance $packageName 2>/dev/null ; ")
            append("device_config put game_overlay $packageName \"mode=2,downscaleFactor=$formattedScale\" 2>/dev/null")
        }

        val result = scriptManager.executeCommand(cmd)
        val success = result.isSuccess

        _state.update {
            it.copy(
                renderScale = clampedScale,
                isDownscaleActive = success,
                isOperating = false,
                statusMessage = if (success) {
                    "Activo: ${(clampedScale * 100).toInt()}% (${targetW}x${targetH})"
                } else {
                    "Fallo Shizuku: ${result.stderr}"
                }
            )
        }

        Log.d(TAG, "applyRenderScale -> $success | ${result.stderr}")
        success
    }

    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        _state.update { it.copy(isOperating = true, statusMessage = "Restaurando resolución nativa...") }

        val cmd = buildString {
            append("wm size reset && ")
            append("wm density reset && ")
            append("cmd power set-fixed-performance-mode-enabled false ; ")
            append("cmd game reset $packageName 2>/dev/null ; ")
            append("device_config delete game_overlay $packageName 2>/dev/null")
        }

        val result = scriptManager.executeCommand(cmd)
        val success = result.isSuccess

        _state.update {
            it.copy(
                renderScale = 1.0f,
                isDownscaleActive = false,
                isOperating = false,
                statusMessage = if (success) "Renderizado nativo restaurado (100%)" else "Error: ${result.stderr}"
            )
        }

        success
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
