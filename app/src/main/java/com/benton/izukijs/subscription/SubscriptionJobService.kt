package com.benton.izukijs.subscription

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.benton.izukijs.IzukiApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One job scans all due subscriptions, so script hash collisions cannot replace another job. */
class SubscriptionJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            var retry = false
            try {
                (application as IzukiApp).container.subscriptionManager.syncDue()
            } catch (cancelled: CancellationException) {
                // onStopJob owns rescheduling; never finish a stopped job from its cancelled coroutine.
                throw cancelled
            } catch (_: Exception) {
                retry = true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        // Safe to retry downloads with baseline checking; this job never runs script/input actions.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x495A5301

        fun enqueue(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "无法调度订阅后台同步" }
        }
    }
}
