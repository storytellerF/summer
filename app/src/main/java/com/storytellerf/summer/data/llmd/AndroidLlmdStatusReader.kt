package com.storytellerf.summer.data.llmd

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only probing: old IPC implementations can initialize engines even on health queries. */
class AndroidLlmdStatusReader(context: Context) : LlmdStatusReader {
    private val appContext = context.applicationContext

    override suspend fun read(target: LlmdTarget): LlmdStatus {
        val installed = try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(target.packageName, PackageManager.GET_SERVICES)
        } catch (_: PackageManager.NameNotFoundException) { return LlmdStatus.NotInstalled }
        // This private service was introduced with explicit loading and read-only Binder health.
        if (installed.services.orEmpty().none { it.name == "com.storytellerf.llmd.LlmdModelService" }) {
            return LlmdStatus.UpdateRequired
        }
        val connection = LlmdServiceConnection(appContext)
        return try {
            withTimeoutOrNull(8_000) {
                val health = connection.health(target)
                if (authorizationRequired(health)) LlmdStatus.AuthorizationRequired
                else parseLlmdStatus(health, connection.models(target))
            } ?: LlmdStatus.Unavailable("LLMD did not respond. Try refreshing.", installationKnown = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LlmdStatus.Unavailable("Could not read LLMD status. Try refreshing.", installationKnown = true) }
        finally { connection.close() }
    }
}
