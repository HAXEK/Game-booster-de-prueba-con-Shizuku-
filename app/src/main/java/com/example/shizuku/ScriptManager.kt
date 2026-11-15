package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isSuccess: Boolean = exitCode == 0
)

class ScriptManager(private val context: Context) {

    companion object {
        private const val TAG = "ScriptManager"
        const val SCRIPTS_DIR_NAME = "gamebooster_scripts"
    }

    private var shellService: IShellService? = null
    private var connectionDeferred: CompletableDeferred<IShellService?>? = null
    private val bindMutex = Mutex()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = IShellService.Stub.asInterface(service)
            shellService = stub
            connectionDeferred?.complete(stub)
            Log.d(TAG, "Shizuku UserService conectado correctamente")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            connectionDeferred = null
            Log.w(TAG, "Shizuku UserService desconectado")
        }
    }

    private suspend fun getOrBindService(): IShellService? = bindMutex.withLock {
        shellService?.let {
            if (it.asBinder().isBinderAlive) return it
        }

        val isShizukuReady = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        if (!isShizukuReady) return null

        val deferred = CompletableDeferred<IShellService?>()
        connectionDeferred = deferred

        withContext(Dispatchers.Main) {
            try {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Throwable) {
                Log.e(TAG, "Error en bindUserService: ${e.message}")
                deferred.complete(null)
            }
        }

        return withTimeoutOrNull(4000L) {
            deferred.await()
        }
    }

    fun getLocalScriptsDir(): File {
        val dir = File(context.filesDir, SCRIPTS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun deployScripts(): Boolean = withContext(Dispatchers.IO) {
        try {
            val scriptsDir = getLocalScriptsDir()
            val scriptFiles = context.assets.list("scripts") ?: emptyArray()
            for (scriptName in scriptFiles) {
                val destFile = File(scriptsDir, scriptName)
                context.assets.open("scripts/$scriptName").use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setReadable(true, false)
                destFile.setExecutable(true, false)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeCommand(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        val service = getOrBindService()
            ?: return@withContext ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku no está conectado o no tiene permisos concedidos."
            )

        return@withContext try {
            val res = service.runCommand(command)
            val code = res.getOrNull(0)?.toIntOrNull() ?: -1
            val out = res.getOrNull(1) ?: ""
            val err = res.getOrNull(2) ?: ""
            ShellCommandResult(exitCode = code, stdout = out, stderr = err, isSuccess = code == 0)
        } catch (e: Exception) {
            shellService = null
            ShellCommandResult(exitCode = -1, stdout = "", stderr = e.message ?: "Error ejecutando comando")
        }
    }

    suspend fun executeScript(scriptName: String, args: List<String> = emptyList()): ShellCommandResult = withContext(Dispatchers.IO) {
        val scriptFile = File(getLocalScriptsDir(), scriptName)
        if (!scriptFile.exists()) deployScripts()

        if (!scriptFile.exists()) {
            return@withContext ShellCommandResult(-1, "", "Script $scriptName no encontrado")
        }

        val safeArgs = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val command = "sh '${scriptFile.absolutePath}' $safeArgs"
        executeCommand(command)
    }
}
