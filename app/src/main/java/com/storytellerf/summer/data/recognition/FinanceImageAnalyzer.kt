package com.storytellerf.summer.data.recognition

import com.storytellerf.summer.data.llmd.LlmdTarget

interface FinanceImageAnalyzer : AutoCloseable {
    suspend fun extractBalanceFromImage(
        imageReference: String,
        target: LlmdTarget = LlmdTarget.Release,
    ): Result<Double>

    suspend fun extractBalanceWithImage(
        imageReference: String,
        target: LlmdTarget = LlmdTarget.Release,
    ): Result<RecognizedBalance> = extractBalanceFromImage(imageReference, target).map { RecognizedBalance(it, null) }

    suspend fun extractTransactionsFromImage(
        imageReference: String,
        target: LlmdTarget = LlmdTarget.Release,
    ): Result<RecognizedTransactions> = Result.failure(UnsupportedOperationException("Transaction recognition is unavailable"))

    suspend fun extractBalancesFromImage(imageReference: String, targets: List<BalanceReadTarget>, target: LlmdTarget = LlmdTarget.Release): Result<RecognizedBalances> =
        if (targets.size == 1) extractBalanceWithImage(imageReference, target).map {
            RecognizedBalances(listOf(RecognizedAccountBalance(targets.single().fundSourceId, it.balance, targets.single().balanceToRead)), it.imagePath)
        } else Result.failure(UnsupportedOperationException("Multiple balance recognition is unavailable"))

    override fun close() = Unit
}
