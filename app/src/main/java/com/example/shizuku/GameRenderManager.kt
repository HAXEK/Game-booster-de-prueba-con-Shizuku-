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
        val clampedScale = scale.coerceIn(0.50f, 1.0f)
        val formattedScale = String.format(Locale.US, "%.2f", clampedScale)

        val targetPkg = packageName.ifBlank {
            val res = scriptManager.executeCommand("dumpsys window | grep mCurrentFocus | awk -F'[ /]' '{print \$5}' | tr -d '}'")
            res.stdout.trim()
        }

        if (targetPkg.isBlank() || targetPkg.contains("gamebooster")) {
            _state.update { it.copy(isOperating = false, statusMessage = "Abre el juego antes de aplicar") }
            return@withContext false
        }

        _state.update { it.copy(isOperating = true, statusMessage = "Aplicando ${(clampedScale * 100).toInt()}% a $targetPkg...") }

        if (clampedScale >= 0.98f) return@withContext resetRenderScale(targetPkg)

        val cmd = "cmd game mode performance $targetPkg ; " +
                  "cmd game set --downscale $formattedScale $targetPkg ; " +
                  "device_config put game_overlay $targetPkg \"mode=2,downscaleFactor=$formattedScale\" ; " +
                  "am force-stop $targetPkg ; " +
                  "monkey -p $targetPkg -c android.intent.category.LAUNCHER 1"

        scriptManager.executeCommand(cmd)

        _state.update {
            it.copy(
                renderScale = clampedScale,
                isDownscaleActive = true,
                isOperating = false,
                statusMessage = "Aplicado ${(clampedScale * 100).toInt()}% en $targetPkg"
            )
        }
        true
    }

    suspend fun resetRenderScale(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val targetPkg = packageName.ifBlank {
            val res = scriptManager.executeCommand("dumpsys window | grep mCurrentFocus | awk -F'[ /]' '{print \$5}' | tr -d '}'")
            res.stdout.trim()
        }

        if (targetPkg.isNotBlank()) {
            val cmd = "device_config delete game_overlay $targetPkg ; cmd game reset $targetPkg"
            scriptManager.executeCommand(cmd)
        }

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
