package com.storytellerf.summer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.storytellerf.summer.ui.addbalance.AddBalanceChangeScreen
import com.storytellerf.summer.ui.feed.FeedScreen
import com.storytellerf.summer.ui.importtransactions.ImportTransactionsScreen
import com.storytellerf.summer.ui.fundsources.FundSourcesScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                FeedScreen(
                    onImportTransactions = { backStack.add(ImportTransactions) },
                    onAddBalanceChange = { backStack.add(AddBalanceChange) },
                    onManageFundSources = { backStack.add(FundSources) },
                    modifier = Modifier,
                )
            }
            entry<FundSources> {
                FundSourcesScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier,
                )
            }
            entry<ImportTransactions> {
                ImportTransactionsScreen(onBack = { backStack.removeLastOrNull() }, onManageAccounts = { backStack.add(FundSources) })
            }
            entry<AddBalanceChange> {
                AddBalanceChangeScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onManageAccounts = { backStack.add(FundSources) },
                    modifier = Modifier,
                )
            }
        },
    )
}
