package com.storytellerf.summer.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.storytellerf.summer.ui.components.formatMoney
import com.storytellerf.summer.ui.components.formatSignedMoney
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onAddBalanceChange: () -> Unit,
    onManageFundSources: () -> Unit,
    onImportTransactions: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = viewModel(
        factory = FeedViewModel.Factory(
            com.storytellerf.summer.data.db.SummerDatabase.getInstance(
                androidx.compose.ui.platform.LocalContext.current.applicationContext
            ).let { com.storytellerf.summer.data.DefaultDataRepository(it) }
        )
    ),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val flow = remember(viewModel, lifecycle) { viewModel.items.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED) }
    val items = flow.collectAsLazyPagingItems(context = Dispatchers.Main.immediate)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Summer Finance", style = MaterialTheme.typography.titleMedium, maxLines = 1) },
                actions = {
                    TextButton(onClick = onImportTransactions) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "Import Transactions", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import orders")
                    }
                    IconButton(onClick = onManageFundSources) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Button(onClick = onAddBalanceChange, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Add Balance Change")
                        Spacer(Modifier.width(8.dp))
                        Text("Record balance")
                    }
                }
            }
        },
    ) { paddingValues ->
        FeedContent(items, Modifier.padding(paddingValues))
    }
}

@Composable
private fun FeedContent(
    items: LazyPagingItems<TimelineItem>,
    modifier: Modifier = Modifier,
) {
    if (items.itemCount == 0 && items.loadState.refresh is LoadState.Loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (items.itemCount == 0 && items.loadState.refresh is LoadState.Error) {
        PageError(onRetry = items::retry, modifier = modifier)
        return
    }
    if (items.itemCount == 0) {
        Box(
            modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("Start your timeline", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Record a balance or import orders to start your timeline.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Stable UI-only start anchor; paged rows retain their own zero-based indexes.
        item(key = "timeline:top-sentinel", contentType = "timeline:top-sentinel") {
            Spacer(Modifier.fillMaxWidth().height(0.dp))
        }
        items(count = items.itemCount, key = items.itemKey { it.key }) { index ->
            when (val item = items[index]) {
                is TimelineItem.Snapshot -> BalanceSnapshotCard(item.snapshot, item.isLatest)
                is TimelineItem.Transaction -> BalanceImpactConnector(
                    item.record.amount, item.fundSourceName, item.record.timestamp,
                    item.record.note ?: "Transaction", isDifference = false,
                )
                is TimelineItem.Difference -> BalanceImpactConnector(
                    item.amount, item.fundSourceName, item.timestamp, "Balance difference", isDifference = true,
                )
                null -> Unit
            }
        }
        if (items.loadState.append is LoadState.Loading || items.loadState.prepend is LoadState.Loading) {
            item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        if (items.loadState.append is LoadState.Error || items.loadState.prepend is LoadState.Error || items.loadState.refresh is LoadState.Error) {
            item { PageError(onRetry = items::retry) }
        }
    }
}

@Composable
private fun PageError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp)) {
        Text("Could not load the timeline.")
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun BalanceSnapshotCard(
    snapshot: BalanceSnapshot,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLatest) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatRange(snapshot.startTimestamp, snapshot.timestamp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLatest) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = "Latest",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (isLatest) "Total assets" else "Balance checkpoint",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatMoney(snapshot.totalBalance),
                style = if (isLatest) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            snapshot.fundBalances.forEachIndexed { index, fund ->
                FundBalanceRow(fundBalance = fund)
                if (index < snapshot.fundBalances.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 36.dp, top = 10.dp, bottom = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FundBalanceRow(
    fundBalance: FundBalance,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = fundBalance.fundSourceName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatMoney(fundBalance.balance),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BalanceImpactConnector(
    amount: Double,
    fundSourceName: String,
    timestamp: Long,
    title: String,
    isDifference: Boolean,
    modifier: Modifier = Modifier,
) {
    val isIncrease = amount > 0
    val isDecrease = amount < 0
    val amountColor = when {
        isIncrease -> MaterialTheme.colorScheme.primary
        isDecrease -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(44.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (isDifference) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else if (isDecrease) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isDifference) {
                            Icons.AutoMirrored.Outlined.HelpOutline
                        } else if (isDecrease) {
                            Icons.Default.ArrowDownward
                        } else {
                            Icons.Default.ArrowUpward
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isDifference) MaterialTheme.colorScheme.onTertiaryContainer else amountColor,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                    if (isDifference) Text("Not covered by orders", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$fundSourceName · ${formatDate(timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = if (isDecrease) {
                        Icons.Default.ArrowDownward
                    } else {
                        Icons.Default.ArrowUpward
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = amountColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formatSignedMoney(amount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatRange(start: Long, end: Long): String {
    if (start == end) return formatDate(end)
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return if (day.format(Date(start)) == day.format(Date(end)))
        "${day.format(Date(end))} · ${formatTime(start)}–${formatTime(end)}"
    else "${formatDate(start)} – ${formatDate(end)}"
}
