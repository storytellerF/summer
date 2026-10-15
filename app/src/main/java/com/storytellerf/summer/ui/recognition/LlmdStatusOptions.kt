package com.storytellerf.summer.ui.recognition

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storytellerf.summer.data.llmd.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
fun LlmdStatusOptions(selectedTarget: LlmdTarget, onSelectTarget: (LlmdTarget) -> Unit) {
    val context = LocalContext.current
    val model: LlmdStatusViewModel = viewModel(factory = LlmdStatusViewModel.Factory(AndroidLlmdStatusReader(context)))
    val host = model.host
    val state by host.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    val owner = LocalLifecycleOwner.current
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { host.refresh() }
    LaunchedEffect(host, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            host.start()
            try { awaitCancellation() } finally { host.stop() }
        }
    }
    LaunchedEffect(host, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            host.effects.collect { effect ->
                withContext(Dispatchers.Main.immediate) {
                    val target = when (effect) {
                        is LlmdStatusEffect.Authorize -> effect.target
                        is LlmdStatusEffect.Open -> effect.target
                    }
                    try {
                        when (effect) {
                            is LlmdStatusEffect.Authorize -> authorization.launch(
                                Intent(LlmdServiceConnection.ACTION_AUTHORIZE_CALLER).setPackage(target.packageName)
                                    .putExtra(LlmdServiceConnection.EXTRA_CALLER_PACKAGE, context.packageName))
                            is LlmdStatusEffect.Open -> context.startActivity(Intent(Intent.ACTION_MAIN)
                                .setComponent(ComponentName(target.packageName, "com.storytellerf.llmd.MainActivity")))
                        }
                    } catch (_: android.content.ActivityNotFoundException) { host.launchFailed(target) }
                    catch (_: SecurityException) { host.launchFailed(target) }
                }
            }
        }
    }
    LlmdStatusRows(state, selectedTarget, onSelectTarget, host::refresh, host::authorize, host::open)
}

@Composable
internal fun LlmdStatusRows(
    state: LlmdStatusState,
    selectedTarget: LlmdTarget,
    onSelectTarget: (LlmdTarget) -> Unit,
    onRefresh: () -> Unit,
    onAuthorize: (LlmdTarget) -> Unit,
    onOpen: (LlmdTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LLMD apps", style = MaterialTheme.typography.titleMedium)
        Text("Choose a build for recognition. Status is shown separately for each app.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRefresh, enabled = !state.refreshing) {
            Text(if (state.refreshing) "Checking LLMD status…" else "Refresh LLMD status")
        }
        LlmdTarget.entries.forEach { target ->
            val status = state.statuses.getValue(target)
            OutlinedCard(Modifier.fillMaxWidth().testTag("llmd-status-${target.preferenceValue}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = selectedTarget == target, onClick = { onSelectTarget(target) },
                        label = { Text("LLMD ${target.displayName}") })
                    when (status) {
                        LlmdStatus.Checking -> Text("Checking status…")
                        LlmdStatus.NotInstalled -> {
                            Text("Installation: Not installed")
                            Text("Authorization: Not applicable")
                            Text("Service: Unavailable")
                            Text("Running model: Unavailable")
                        }
                        is LlmdStatus.Available -> {
                            Text("Installation: Installed")
                            Text("Authorization: Authorized")
                            Text("Service: ${status.service.label}")
                            Text("Running model: ${status.activeModel ?: if (status.service == LlmdServiceStatus.Ready) "Not reported" else "None"}")
                            if (status.selectedModel != null && status.service != LlmdServiceStatus.Ready) Text("Selected model: ${status.selectedModel}")
                            Text("Imported models: ${status.models.joinToString().ifEmpty { "None" }}")
                        }
                        else -> {
                            Text("Installation: ${if (status is LlmdStatus.Unavailable && !status.installationKnown) "Unknown" else "Installed"}")
                            Text("Authorization: ${if (status == LlmdStatus.AuthorizationRequired) "Required" else "Unknown"}")
                            Text("Service: Unknown")
                            Text("Running model: Unknown")
                            Text("Imported models: Unknown")
                            when (status) {
                                LlmdStatus.UpdateRequired -> Text("Update LLMD to check status without loading a model.")
                                LlmdStatus.AuthorizationRequired -> Text("Allow Summer in this LLMD app to read its status.")
                                is LlmdStatus.Unavailable -> Text(status.message)
                                else -> Unit
                            }
                        }
                    }
                    if (status !in listOf(LlmdStatus.NotInstalled, LlmdStatus.Checking)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (status == LlmdStatus.AuthorizationRequired) {
                                TextButton(onClick = { onAuthorize(target) }) { Text("Authorize ${target.displayName}") }
                            }
                            TextButton(onClick = { onOpen(target) }) { Text("Open ${target.displayName}") }
                        }
                    }
                }
            }
        }
    }
}
