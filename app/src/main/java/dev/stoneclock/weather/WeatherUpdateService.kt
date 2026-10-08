package dev.stoneclock.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WeatherUpdateService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        tasks[params.jobId] = scope.launch(start = CoroutineStart.LAZY) {
            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else null
            val success = WeatherRepository.get(this@WeatherUpdateService).refresh(network = network)
            tasks.remove(params.jobId)
            jobFinished(params, !success)
        }
        tasks[params.jobId]?.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { tasks.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7401
        private const val IMMEDIATE_JOB_ID = 7403
        fun requestImmediate(context: Context) {
            if (WeatherRepository.get(context).state.value.location == null) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(IMMEDIATE_JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(IMMEDIATE_JOB_ID, ComponentName(context, WeatherUpdateService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build())
        }
        fun ensureScheduled(context: Context) {
            if (WeatherRepository.get(context).state.value.location == null) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, WeatherUpdateService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60 * 1_000L, 5 * 60 * 1_000L)
                .setPersisted(true).build())
        }
    }
}
