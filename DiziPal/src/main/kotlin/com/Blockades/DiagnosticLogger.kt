package com.Blockades

import android.util.Log

object DiagnosticLogger {
    private const val TAG = "CloudstreamDiagnostics"

    fun log(
        provider: String,
        stage: DiagnosticStage,
        category: DiagnosticCategory,
        message: String,
        throwable: Throwable? = null
    ) {
        val logMessage = "[$provider] [$stage] [$category] $message"
        if (throwable != null) {
            Log.e(TAG, logMessage, throwable)
        } else {
            Log.d(TAG, logMessage)
        }
    }
}
