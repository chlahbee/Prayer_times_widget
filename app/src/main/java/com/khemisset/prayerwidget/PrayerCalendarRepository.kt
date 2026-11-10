package com.khemisset.prayerwidget

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object PrayerCalendarRepository {
    private const val PREFS_NAME = "prayer_prefs"
    private const val CACHE_JSON = "cached_monthly_calendar"
    private const val CACHE_KEY = "cached_monthly_calendar_key"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun cacheKey(lat: Double, lon: Double, method: Int): String =
        "${lat.toFloat()}|${lon.toFloat()}|$method"

    fun getCachedCurrentMonth(context: Context, lat: Double, lon: Double, method: Int): JSONObject? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(CACHE_KEY, null) != cacheKey(lat, lon, method)) return null

        val cached = prefs.getString(CACHE_JSON, null) ?: return null
        return try {
            val json = JSONObject(cached)
            if (isCurrentMonth(json)) json else null
        } catch (_: Exception) {
            null
        }
    }

    fun getOrFetchCurrentMonth(context: Context, lat: Double, lon: Double, method: Int): JSONObject {
        getCachedCurrentMonth(context, lat, lon, method)?.let { return it }

        val now = LocalDate.now()
        val url = "https://api.aladhan.com/v1/calendar/${now.year}/${now.monthValue}" +
            "?latitude=$lat&longitude=$lon&method=$method"
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("Prayer calendar request failed with HTTP ${it.code}")
            }
            val responseString = it.body?.string().orEmpty()
            if (responseString.isEmpty()) throw IOException("Prayer calendar response was empty")

            val json = JSONObject(responseString)
            json.getJSONArray("data")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CACHE_JSON, responseString)
                .putString(CACHE_KEY, cacheKey(lat, lon, method))
                .apply()
            return json
        }
    }

    fun timezoneId(calendar: JSONObject): String {
        val topLevelMeta = calendar.optJSONObject("meta")
        val firstDay = calendar.getJSONArray("data").optJSONObject(0)
        return topLevelMeta?.optString("timezone").orEmpty().ifEmpty {
            firstDay?.optJSONObject("meta")?.optString("timezone").orEmpty()
        }.ifEmpty {
            ZoneId.systemDefault().id
        }
    }

    fun dayForDate(calendar: JSONObject, date: LocalDate): JSONObject? {
        val days = calendar.getJSONArray("data")
        for (index in 0 until days.length()) {
            val day = days.getJSONObject(index)
            val gregorian = day.getJSONObject("date").getJSONObject("gregorian")
            if (gregorian.getInt("year") == date.year &&
                gregorian.getJSONObject("month").getInt("number") == date.monthValue &&
                gregorian.getInt("day") == date.dayOfMonth
            ) {
                return day
            }
        }
        return null
    }

    private fun isCurrentMonth(calendar: JSONObject): Boolean {
        val today = LocalDate.now(ZoneId.of(timezoneId(calendar)))
        val firstDay = calendar.getJSONArray("data").optJSONObject(0)
            ?: return false
        val gregorian = firstDay.getJSONObject("date").getJSONObject("gregorian")
        return gregorian.getInt("year") == today.year &&
            gregorian.getJSONObject("month").getInt("number") == today.monthValue
    }
}
