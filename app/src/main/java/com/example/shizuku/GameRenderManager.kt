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
        val clampedScale = scale.coerceIn(0.60f, 1.0f)

        if (clampedScale >= 0.98f) return@withContext resetRenderScale(packageName)

        val sizeRes = scriptManager.executeCommand("wm size")
        val densityRes = scriptManager.executeCommand("wm density")
        val sizeMatch = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeRes.stdout)
        val densityMatch = Regex("Physical density:\\s*(\\d+)").find(densityRes.stdout)

        val physW = sizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1220
        val physH = sizeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 2712
        val physDpi = densityMatch?.groupValues?.get(1)?.toIntOrNull() ?: 480

        val targetW = ((physW * clampedScale).toInt() / 2) * 2
        val targetH = ((physH * clampedScale).toInt() / 2) * 2
        val targetDpi = ((physDpi * clampedScale).toInt()).coerceAtLeast(240)

        val targetPkg = packageName.ifBlank {
            scriptManager.executeCommand("dumpsys window | grep mCurrentFocus | awk -F'[ /]' '{print \$5}' | tr -d '}'").stdout.trim()
        }

        val cmd = buildString {
            if (targetPkg.isNotBlank()) append("cmd game reset $targetPkg ; ")
            append("wm size ${targetW}x${targetH} ; ")
            append("wm density $targetDpi ; ")
            if (targetPkg.isNotBlank()) {
                append("am force-stop $targetPkg ; monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
            }
        }

        scriptManager.executeCommand(cmd)

        _state.update {
            it.copy(
                renderScale = clampedScale,
                isDownscaleActive = true,
                isOperating = false,
                statusMessage = "Pantalla completa ${(clampedScale * 100).toInt()}% (${targetW}x${targetH})"
            )
        }
        true
    }

    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val targetPkg = packageName.ifBlank {
            scriptManager.executeCommand("dumpsys window | grep mCurrentFocus | awk -F'[ /]' '{print \$5}' | tr -d '}'").stdout.trim()
        }
        val cmd = buildString {
            if (targetPkg.isNotBlank()) append("cmd game reset $targetPkg ; ")
            append("wm size reset ; wm density reset")
        }
        scriptManager.executeCommand(cmd)

        _state.update {
            it.copy(
                renderScale = 1.0f,
                isDownscaleActive = false,
                isOperating = false,
                statusMessage = "Resolución nativa restaurada (100%)"
            )
        }
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
