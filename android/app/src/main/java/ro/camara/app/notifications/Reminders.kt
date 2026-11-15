package ro.camara.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.camara.app.MainActivity
import ro.camara.app.R
import ro.camara.app.data.CamaraApi
import ro.camara.app.data.ConnectionStore
import ro.camara.app.data.OfflineStore
import ro.camara.app.data.reminderItems

class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    fun read() = ReminderSettings(prefs.getBoolean("enabled", true), prefs.getInt("hour", 19), prefs.getInt("minute", 0), prefs.getInt("days", 3), prefs.getInt("revision", 0))
    fun save(settings: ReminderSettings) {
        require(settings.hour in 0..23 && settings.minute in 0..59 && settings.days in 1..7)
        prefs.edit().putBoolean("enabled", settings.enabled).putInt("hour", settings.hour).putInt("minute", settings.minute)
            .putInt("days", settings.days).putInt("revision", read().revision + 1).commit()
    }
    var lastSent: String?
        get() = prefs.getString("last_sent", null)
        set(value) { prefs.edit().putString("last_sent", value).commit() }
}

object ReminderScheduler {
    private const val CHANNEL = "expiry-reminders"
    private const val TAG = "camara-expiry"

    fun allowed(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Termenele alimentelor", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Rezumat zilnic al alimentelor cu termen apropiat sau depășit."
        })
    }

    fun schedule(context: Context) {
        val settings = ReminderStore(context).read()
        if (!settings.enabled) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG); return }
        val now = ZonedDateTime.now()
        val next = ReminderRules.nextTime(settings, now)
        val work = OneTimeWorkRequestBuilder<ExpiryWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("revision" to settings.revision)).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG-${settings.revision}-${next.toLocalDate()}", ExistingWorkPolicy.KEEP, work)
    }

    @SuppressLint("MissingPermission") // allowed() checks permission; a concurrent revocation is caught below.
    fun post(context: Context, title: String, body: String, test: Boolean = false): Boolean {
        channel(context)
        if (!allowed(context)) return false
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(context).notify(if (test) 1901 else 1900, notification) }
        catch (_: SecurityException) { return false }
        return true
    }
}

class ExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = execution.withLock { perform() }

    private suspend fun perform(): Result {
        val context = applicationContext
        val store = ReminderStore(context)
        val settings = store.read()
        if (!settings.enabled || inputData.getInt("revision", -1) != settings.revision) return Result.success()
        try {
            val now = ZonedDateTime.now()
            if (!ReminderRules.eligible(settings, now, store.lastSent) || !ReminderScheduler.allowed(context)) return Result.success()
            val config = ConnectionStore(context).read() ?: return Result.success()
            val remote = try { CamaraApi(config, fast = true).snapshot() }
            catch (e: Exception) { if (e is CancellationException) throw e; null }
            val local = withContext(Dispatchers.IO) { runCatching { OfflineStore(context).read(config) }.getOrNull() }
            val snapshot = if (remote == null) local?.projected() else local?.copy(server = remote)?.projected() ?: remote
            val due = ReminderRules.dueItems(snapshot?.reminderItems().orEmpty(), now.toLocalDate(), settings.days)
            if (due.isEmpty()) return Result.success()
            val expired = due.count { LocalDate.parse(it.expires_on) < now.toLocalDate() }
            val names = due.take(4).joinToString("\n") { "${it.name} · ${it.expires_on}" }
            val body = "$names${if (due.size > 4) "\n+${due.size - 4} alte alimente" else ""}" +
                (if (expired > 0) "\n$expired cu termen depășit." else "") +
                (if (remote == null) "\nDin ultima copie salvată; serverul nu a fost disponibil." else "")
            if (store.read().revision == settings.revision && ReminderScheduler.post(context, "Verifică ${due.size} alimente", body)) store.lastSent = now.toLocalDate().toString()
            return Result.success()
        } finally { ReminderScheduler.schedule(context) }
    }

    companion object { private val execution = Mutex() }
}
