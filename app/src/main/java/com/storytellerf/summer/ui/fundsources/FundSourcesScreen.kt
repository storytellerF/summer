package com.storytellerf.summer.ui.fundsources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.storytellerf.summer.ui.components.EntryHeading
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storytellerf.summer.data.DefaultDataRepository
import com.storytellerf.summer.data.db.SummerDatabase
import com.storytellerf.summer.data.db.entity.FundSource
import com.storytellerf.summer.data.llmd.DataStoreLlmdTargetSettings
import com.storytellerf.summer.data.llmd.LlmdTarget
import com.storytellerf.summer.data.recognition.DataStoreRecognitionSettings
import com.storytellerf.summer.ui.recognition.RecognitionSettingsCard
import com.storytellerf.summer.ui.recognition.RecognitionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundSourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FundSourcesViewModel = viewModel(
        factory = FundSourcesViewModel.Factory(
            repository = DefaultDataRepository(
                SummerDatabase.getInstance(LocalContext.current.applicationContext)
            ),
            settings = DataStoreLlmdTargetSettings(LocalContext.current.applicationContext),
        )
    ),
) {
    val recognitionViewModel: RecognitionSettingsViewModel = viewModel(
        factory = RecognitionSettingsViewModel.Factory(DataStoreRecognitionSettings(LocalContext.current.applicationContext)),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var editingFundSource by remember { mutableStateOf<FundSource?>(null) }
    var deletingFundSource by remember { mutableStateOf<FundSource?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedSection == 0) ExtendedFloatingActionButton(
                onClick = {
                    editingFundSource = null
                    showDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Fund Source") },
                text = { Text("Add source") },
            )
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            PrimaryTabRow(selectedTabIndex = selectedSection) {
                listOf("Accounts", "Image recognition").forEachIndexed { index, label ->
                    Tab(selected = selectedSection == index, onClick = { selectedSection = index }, text = { Text(label) })
                }
            }
        when (val s = state) {
            FundSourcesUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
            is FundSourcesUiState.Success -> {
                FundSourcesContent(
                    recognitionViewModel = recognitionViewModel,
                    fundSources = s.fundSources,
                    selectedLlmdTarget = s.selectedLlmdTarget,
                    onSelectLlmdTarget = viewModel::selectLlmdTarget,
                    onEdit = { fundSource ->
                        editingFundSource = fundSource
                        showDialog = true
                    },
                    onDelete = { deletingFundSource = it },
                    modifier = Modifier.weight(1f),
                    showRecognition = selectedSection == 1,
                )
            }
            is FundSourcesUiState.Error -> {
                Text(
                    text = "Error: ${s.message}",
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
        }
    }

    if (showDialog) {
        FundSourceDialog(
            fundSource = editingFundSource,
            onDismiss = { showDialog = false },
            onConfirm = { name ->
                if (editingFundSource != null) {
                    viewModel.updateFundSource(editingFundSource!!.copy(name = name))
                } else {
                    viewModel.addFundSource(name)
                }
                showDialog = false
            },
        )
    }

    deletingFundSource?.let { fundSource ->
        AlertDialog(
            onDismissRequest = { deletingFundSource = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete ${fundSource.name}?") },
            text = { Text("This deletes the account and all of its balance records and imported orders. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFundSource(fundSource)
                        deletingFundSource = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingFundSource = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FundSourcesContent(
    recognitionViewModel: RecognitionSettingsViewModel,
    fundSources: List<FundSource>,
    selectedLlmdTarget: LlmdTarget,
    onSelectLlmdTarget: (LlmdTarget) -> Unit,
    onEdit: (FundSource) -> Unit,
    onDelete: (FundSource) -> Unit,
    modifier: Modifier = Modifier,
    showRecognition: Boolean,
) {
    LazyColumn(modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showRecognition) {
            item {
                RecognitionSettingsCard(recognitionViewModel) {
                    LlmdBuildSelector(selectedLlmdTarget, onSelectLlmdTarget)
                }
            }
        } else {
            item { EntryHeading("Your accounts", "Keep balances and imported orders together by account.") }
            if (fundSources.isEmpty()) item { EmptyFundSourcesCard() }
            else items(fundSources, key = { it.id }) { source ->
                FundSourceItem(source, onEdit = { onEdit(source) }, onDelete = { onDelete(source) })
            }
        }
    }
}

@Composable
private fun LlmdBuildSelector(selectedTarget: LlmdTarget, onSelectTarget: (LlmdTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LLMD build", style = MaterialTheme.typography.labelLarge)
        Text("Choose the LLMD app installed on this device.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmdTarget.entries.forEach { target ->
                FilterChip(selected = selectedTarget == target, onClick = { onSelectTarget(target) }, label = { Text(target.displayName) })
            }
        }
    }
}

@Composable
private fun EmptyFundSourcesCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("Add where you keep money", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "No fund sources yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FundSourceItem(
    fundSource: FundSource,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = fundSource.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Balance source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun FundSourceDialog(
    fundSource: FundSource?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(fundSource?.name ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fundSource != null) "Edit Fund Source" else "Add Fund Source") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Fund Source Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("For example: Savings, Cash, or Brokerage") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
