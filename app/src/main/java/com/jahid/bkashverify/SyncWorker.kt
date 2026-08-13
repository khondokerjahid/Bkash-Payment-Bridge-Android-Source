package com.jahid.bkashverify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        if (!BridgeStorage.isConnected(applicationContext)) return Result.success()

        // Recovery is best-effort. Manual Full Phone History Scan is the authoritative
        // full-history action on Xiaomi/HyperOS.
        if (
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                BkashSmsScanner.scanRecoveryPayments(applicationContext)
            } catch (_: Exception) {
                // Do not let Xiaomi/provider restrictions block queued payment sync.
            }
        }

        val queue = BridgeStorage.pending(applicationContext)

        if (queue.isEmpty()) {
            return try {
                BridgeApi.heartbeat(applicationContext)
                BridgeStorage.setLastSyncMessage(
                    applicationContext,
                    "Connected · ${java.util.Date()}"
                )
                Result.success()
            } catch (e: BridgeApiException) {
                if (e.statusCode == 401) {
                    BridgeStorage.setLastSyncMessage(
                        applicationContext,
                        "Connection rejected. Pair the phone again."
                    )
                    Result.failure()
                } else {
                    Result.retry()
                }
            } catch (_: Exception) {
                Result.retry()
            }
        }

        var sent = 0
        var alreadyOnServer = 0

        for (payment in queue.take(100)) {
            try {
                val response = BridgeApi.sendPayment(applicationContext, payment)

                if (response.optBoolean("success", false)) {
                    BridgeStorage.markSent(
                        applicationContext,
                        payment.transactionId
                    )
                    sent += 1
                }
            } catch (e: BridgeApiException) {
                when (e.statusCode) {
                    401 -> {
                        BridgeStorage.setLastSyncMessage(
                            applicationContext,
                            "Connection rejected. Pair the phone again."
                        )
                        return Result.failure()
                    }

                    // Historical SMS may already exist on the website because it was
                    // imported earlier from the PC. A duplicate conflict must NOT block
                    // newer unsynced payments behind it in the queue.
                    409 -> {
                        BridgeStorage.markSent(
                            applicationContext,
                            payment.transactionId
                        )
                        alreadyOnServer += 1
                        continue
                    }

                    else -> {
                        BridgeStorage.setLastSyncMessage(
                            applicationContext,
                            "Sync waiting: ${e.message}"
                        )
                        return Result.retry()
                    }
                }
            } catch (_: Exception) {
                BridgeStorage.setLastSyncMessage(
                    applicationContext,
                    "Offline. Pending payments will retry automatically."
                )
                return Result.retry()
            }
        }

        BridgeStorage.setLastSyncMessage(
            applicationContext,
            buildString {
                append("Synced $sent new payment(s)")
                if (alreadyOnServer > 0) {
                    append(" · $alreadyOnServer already existed")
                }
                append(" · ${java.util.Date()}")
            }
        )

        return Result.success()
    }

    companion object {
        private const val UNIQUE_NOW = "bkash-bridge-sync-now"
        private const val UNIQUE_PERIODIC = "bkash-bridge-periodic"

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
