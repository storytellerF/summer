package com.storytellerf.summer.data.recognition

import com.storytellerf.summer.data.llmd.LlmdTarget

interface FinanceImageAnalyzer : AutoCloseable {
    suspend fun extractTransactionsFromImage(
        imageReference: String,
        target: LlmdTarget = LlmdTarget.Release,
        currency: String? = null,
    ): Result<RecognizedTransactions> = Result.failure(UnsupportedOperationException("Transaction recognition is unavailable"))

    suspend fun extractBalancesFromImage(
        imageReference: String,
        targets: List<BalanceReadTarget>,
        target: LlmdTarget = LlmdTarget.Release,
    ): Result<RecognizedBalances> = Result.failure(UnsupportedOperationException("Balance recognition is unavailable"))

    override fun close() = Unit
}
