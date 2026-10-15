package com.storytellerf.summer

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object FundSources : NavKey
@Serializable data object AddBalanceChange : NavKey

@Serializable data object ImportTransactions : NavKey
