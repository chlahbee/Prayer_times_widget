package com.khemisset.prayerwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class PrayerWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_EXPAND) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
                val isExpanded = prefs.getBoolean("expanded_$widgetId", true)
                prefs.edit().putBoolean("expanded_$widgetId", !isExpanded).apply()

                val manager = AppWidgetManager.getInstance(context)
                val lat = prefs.getFloat("lat", ConfigActivity.DEFAULT_LATITUDE).toDouble()
                val lon = prefs.getFloat("lon", ConfigActivity.DEFAULT_LONGITUDE).toDouble()
                updateWidget(context, manager, widgetId, lat, lon)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("lat", ConfigActivity.DEFAULT_LATITUDE).toDouble()
        val lon = prefs.getFloat("lon", ConfigActivity.DEFAULT_LONGITUDE).toDouble()
        updateWidget(context, appWidgetManager, appWidgetId, lat, lon)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
                val lat = prefs.getFloat("lat", ConfigActivity.DEFAULT_LATITUDE).toDouble()
                val lon = prefs.getFloat("lon", ConfigActivity.DEFAULT_LONGITUDE).toDouble()
                for (id in appWidgetIds) {
                    updateWidgetSync(context, appWidgetManager, id, lat, lon)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_EXPAND = "com.khemisset.prayerwidget.ACTION_TOGGLE_EXPAND"

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int, lat: Double, lon: Double) {
            CoroutineScope(Dispatchers.IO).launch {
                updateWidgetSync(context, manager, widgetId, lat, lon)
            }
        }

        private fun updateWidgetSync(context: Context, manager: AppWidgetManager, widgetId: Int, lat: Double, lon: Double) {
            val views = RemoteViews(context.packageName, R.layout.widget_prayer)

            val configIntent = Intent(context, ConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configPending = PendingIntent.getActivity(
                context,
                widgetId,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, configPending)

            val toggleIntent = Intent(context, PrayerWidget::class.java).apply {
                action = ACTION_TOGGLE_EXPAND
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val togglePending = PendingIntent.getBroadcast(
                context,
                widgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_expand, togglePending)

            val prefs = context.getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
            val isExpanded = prefs.getBoolean("expanded_$widgetId", true)

            val options = manager.getAppWidgetOptions(widgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            val isTall = minHeight >= 110 && minHeight > minWidth

            if (isExpanded) {
                if (isTall) {
                    views.setViewVisibility(R.id.ll_all_prayers_horizontal, View.GONE)
                    views.setViewVisibility(R.id.ll_all_prayers_vertical, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.ll_all_prayers_vertical, View.GONE)
                    views.setViewVisibility(R.id.ll_all_prayers_horizontal, View.VISIBLE)
                }
                views.setTextViewText(R.id.btn_expand, "▲")
            } else {
                views.setViewVisibility(R.id.ll_all_prayers_horizontal, View.GONE)
                views.setViewVisibility(R.id.ll_all_prayers_vertical, View.GONE)
                views.setTextViewText(R.id.btn_expand, "▼")
            }

            val cityName = prefs.getString("city_name", null) ?: ConfigActivity.resolveCityName(context, lat, lon)
            views.setTextViewText(R.id.tv_location, cityName)

            val method = PrayerCalculation.methodForCoordinates(context, lat, lon)
            val cacheKey = "${lat.toFloat()}|${lon.toFloat()}|$method"
            val cachedJsonStr = prefs.getString("cached_timings", null)
            val cachedDataMatchesLocation = prefs.getString("cached_timings_key", null) == cacheKey
            var cachedTimingsRendered = false
            if (cachedDataMatchesLocation && !cachedJsonStr.isNullOrEmpty()) {
                try {
                    val cachedObj = JSONObject(cachedJsonStr)
                    val data = cachedObj.getJSONObject("data")
                    val timezoneId = data.getJSONObject("meta").getString("timezone")
                    val responseDate = data.getJSONObject("date").getJSONObject("gregorian").getString("date")
                    val today = LocalDate.now(ZoneId.of(timezoneId))
                    val cachedDate = LocalDate.parse(responseDate, DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT))
                    if (cachedDate == today) {
                        renderPrayerTimes(context, views, data.getJSONObject("timings"), timezoneId)
                        manager.updateAppWidget(widgetId, views)
                        cachedTimingsRendered = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (!cachedTimingsRendered) {
                manager.updateAppWidget(widgetId, views)
            }

            try {
                val timestamp = System.currentTimeMillis() / 1000
                val url = "https://api.aladhan.com/v1/timings/$timestamp?latitude=$lat&longitude=$lon&method=$method"
                val req = Request.Builder().url(url).build()
                val res = client.newCall(req).execute()
                if (!res.isSuccessful) {
                    throw IOException("Prayer timings request failed with HTTP ${res.code}")
                }
                val responseString = res.body?.string() ?: ""

                if (responseString.isNotEmpty()) {
                    val json = JSONObject(responseString)
                    val data = json.getJSONObject("data")
                    val timezoneId = data.getJSONObject("meta").getString("timezone")
                    val timings = data.getJSONObject("timings")

                    prefs.edit()
                        .putString("cached_timings", responseString)
                        .putString("cached_timings_key", cacheKey)
                        .apply()

                    renderPrayerTimes(context, views, timings, timezoneId)
                    manager.updateAppWidget(widgetId, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!cachedTimingsRendered) {
                    views.setTextViewText(R.id.tv_next_name, "خطأ بالشبكة")
                    views.setTextViewText(R.id.tv_countdown, "إضغط للتحديث")
                    manager.updateAppWidget(widgetId, views)
                }
            }
        }

        private fun renderPrayerTimes(context: Context, views: RemoteViews, timings: JSONObject, timezoneId: String) {
            val prayers = listOf(
                "Fajr" to "الفجر",
                "Dhuhr" to "الظهر",
                "Asr" to "العصر",
                "Maghrib" to "المغرب",
                "Isha" to "العشاء"
            )

            val tz = TimeZone.getTimeZone(ZoneId.of(timezoneId))
            val now = Calendar.getInstance(tz)

            var nextName = "الفجر"
            var nextTimeStr = ""
            var minDiff = Long.MAX_VALUE

            for ((en, ar) in prayers) {
                if (!timings.has(en)) continue
                val rawTime = timings.getString(en)
                val cleanTime = rawTime.split(" ")[0].trim()
                val parts = cleanTime.split(":")
                if (parts.size < 2) continue

                val cal = Calendar.getInstance(tz)
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                cal.set(Calendar.MINUTE, parts[1].toInt())
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                val nowZeroSeconds = Calendar.getInstance(tz).apply {
                    timeInMillis = now.timeInMillis
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (cal.before(nowZeroSeconds)) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }

                val diff = cal.timeInMillis - nowZeroSeconds.timeInMillis
                if (diff in 0 until minDiff) {
                    minDiff = diff
                    nextName = ar
                    nextTimeStr = cleanTime
                }
            }

            val hours = (minDiff / 3600000).toInt()
            val mins = ((minDiff % 3600000) / 60000).toInt()
            val countdown = if (hours > 0) "بعد ${hours}س ${mins}د" else "بعد ${mins}د"

            views.setTextViewText(R.id.tv_next_name, nextName)
            views.setTextViewText(R.id.tv_next_time, nextTimeStr)
            views.setTextViewText(R.id.tv_countdown, countdown)

            val fajrTime = if (timings.has("Fajr")) timings.getString("Fajr").split(" ")[0].trim() else "--:--"
            val sunriseTime = if (timings.has("Sunrise")) timings.getString("Sunrise").split(" ")[0].trim() else "--:--"
            val dhuhrTime = if (timings.has("Dhuhr")) timings.getString("Dhuhr").split(" ")[0].trim() else "--:--"
            val asrTime = if (timings.has("Asr")) timings.getString("Asr").split(" ")[0].trim() else "--:--"
            val maghribTime = if (timings.has("Maghrib")) timings.getString("Maghrib").split(" ")[0].trim() else "--:--"
            val ishaTime = if (timings.has("Isha")) timings.getString("Isha").split(" ")[0].trim() else "--:--"

            // Horizontal View Elements
            views.setTextViewText(R.id.tv_time_fajr, fajrTime)
            views.setTextViewText(R.id.tv_time_sunrise, sunriseTime)
            views.setTextViewText(R.id.tv_time_dhuhr, dhuhrTime)
            views.setTextViewText(R.id.tv_time_asr, asrTime)
            views.setTextViewText(R.id.tv_time_maghrib, maghribTime)
            views.setTextViewText(R.id.tv_time_isha, ishaTime)

            // Vertical View Elements
            views.setTextViewText(R.id.tv_time_fajr_v, fajrTime)
            views.setTextViewText(R.id.tv_time_sunrise_v, sunriseTime)
            views.setTextViewText(R.id.tv_time_dhuhr_v, dhuhrTime)
            views.setTextViewText(R.id.tv_time_asr_v, asrTime)
            views.setTextViewText(R.id.tv_time_maghrib_v, maghribTime)
            views.setTextViewText(R.id.tv_time_isha_v, ishaTime)

            val highlightColor = context.getColor(R.color.widget_accent)
            val defaultColor = context.getColor(R.color.widget_text_primary)

            // Highlight horizontal
            views.setTextColor(R.id.tv_time_fajr, if (nextName == "الفجر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_dhuhr, if (nextName == "الظهر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_asr, if (nextName == "العصر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_maghrib, if (nextName == "المغرب") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_isha, if (nextName == "العشاء") highlightColor else defaultColor)

            // Highlight vertical
            views.setTextColor(R.id.tv_time_fajr_v, if (nextName == "الفجر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_dhuhr_v, if (nextName == "الظهر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_asr_v, if (nextName == "العصر") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_maghrib_v, if (nextName == "المغرب") highlightColor else defaultColor)
            views.setTextColor(R.id.tv_time_isha_v, if (nextName == "العشاء") highlightColor else defaultColor)
        }
    }
}
