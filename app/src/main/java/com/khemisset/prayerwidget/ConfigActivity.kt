package com.khemisset.prayerwidget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import java.util.*

class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var lat = DEFAULT_LATITUDE.toDouble()
    private var lon = DEFAULT_LONGITUDE.toDouble()
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("prayer_prefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_config)

        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }

        val tv = findViewById<TextView>(R.id.tv_coords)
        val etLat = findViewById<EditText>(R.id.et_lat)
        val etLon = findViewById<EditText>(R.id.et_lon)

        if (prefs.getFloat("lat", Float.NaN) == 33.8263f &&
            prefs.getFloat("lon", Float.NaN) == -6.0655f
        ) {
            prefs.edit()
                .putFloat("lat", DEFAULT_LATITUDE)
                .putFloat("lon", DEFAULT_LONGITUDE)
                .putString("city_name", "الدار البيضاء")
                .apply()
        }

        lat = prefs.getFloat("lat", DEFAULT_LATITUDE).toDouble()
        lon = prefs.getFloat("lon", DEFAULT_LONGITUDE).toDouble()

        etLat.setText(lat.toString())
        etLon.setText(lon.toString())

        val savedCity = prefs.getString("city_name", null) ?: resolveCityName(this, lat, lon)
        tv.text = "المدينة: $savedCity (%.4f, %.4f)".format(lat, lon)

        fetchMonthlyCalendar(lat, lon)

        findViewById<Button>(R.id.btn_gps).setOnClickListener {
            fetchLocation()
        }

        val themeToggle = findViewById<Button>(R.id.btn_theme_toggle)
        themeToggle.text = if (isDarkMode) "الوضع النهاري" else "الوضع الليلي"
        themeToggle.setOnClickListener {
            val enableDarkMode = !prefs.getBoolean("dark_mode", false)
            prefs.edit().putBoolean("dark_mode", enableDarkMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (enableDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            try {
                lat = etLat.text.toString().toDouble()
                lon = etLon.text.toString().toDouble()
            } catch (e: Exception) {}

            val city = resolveCityName(this, lat, lon)
            prefs.edit()
                .putFloat("lat", lat.toFloat())
                .putFloat("lon", lon.toFloat())
                .putString("city_name", city)
                .apply()

            val appWidgetManager = AppWidgetManager.getInstance(this)

            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                PrayerWidget.updateWidget(this, appWidgetManager, appWidgetId, lat, lon)
                val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                setResult(RESULT_OK, result)
            } else {
                val componentName = ComponentName(this, PrayerWidget::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                for (id in allWidgetIds) {
                    PrayerWidget.updateWidget(this, appWidgetManager, id, lat, lon)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                    val pinnedWidgetCallbackIntent = Intent(this, PrayerWidget::class.java)
                    val successCallback = PendingIntent.getBroadcast(
                        this,
                        0,
                        pinnedWidgetCallbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    appWidgetManager.requestPinAppWidget(componentName, null, successCallback)
                } else {
                    Toast.makeText(this, "تم حفظ الموقع ($city) وتحديث الويدجت", Toast.LENGTH_LONG).show()
                }
            }
            finish()
        }
    }

    private fun fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                100
            )
            return
        }
        val tv = findViewById<TextView>(R.id.tv_coords)
        val etLat = findViewById<EditText>(R.id.et_lat)
        val etLon = findViewById<EditText>(R.id.et_lon)

        val fused = LocationServices.getFusedLocationProviderClient(this)
        val tokenSource = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    applyLocation(location, tv, etLat, etLon)
                } else {
                    fused.lastLocation
                        .addOnSuccessListener { lastLocation ->
                            if (lastLocation != null) {
                                applyLocation(lastLocation, tv, etLat, etLon)
                            } else {
                                Toast.makeText(this, "تعذر تحديد الموقع، تأكد من تشغيل GPS", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "تعذر تحديد الموقع، حاول مرة أخرى", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "فشل تحديد الموقع عبر GPS، تأكد من تشغيل GPS", Toast.LENGTH_LONG).show()
            }
    }

    private fun applyLocation(
        location: Location,
        tv: TextView,
        etLat: EditText,
        etLon: EditText
    ) {
        lat = location.latitude
        lon = location.longitude
        etLat.setText(lat.toString())
        etLon.setText(lon.toString())
        val city = resolveCityName(this, lat, lon)
        tv.text = "المدينة: $city (%.4f, %.4f)".format(Locale.ROOT, lat, lon)
        Toast.makeText(this, "تم تحديد المدينة: $city", Toast.LENGTH_SHORT).show()
        fetchMonthlyCalendar(lat, lon)
    }

    private fun fetchMonthlyCalendar(lat: Double, lon: Double) {
        val tvLoading = findViewById<TextView>(R.id.tv_loading_table)
        val llRows = findViewById<LinearLayout>(R.id.ll_monthly_rows)
        val tvMonthTitle = findViewById<TextView>(R.id.tv_month_title)

        tvLoading.visibility = View.VISIBLE
        tvLoading.text = "جاري تحميل مواقيت الشهر..."
        llRows.removeAllViews()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val method = PrayerCalculation.methodForCoordinates(this@ConfigActivity, lat, lon)
                val calendar = PrayerCalendarRepository.getOrFetchCurrentMonth(this@ConfigActivity, lat, lon, method)
                val timezoneId = PrayerCalendarRepository.timezoneId(calendar)
                val tz = TimeZone.getTimeZone(timezoneId)
                val now = Calendar.getInstance(tz)
                val todayDay = now.get(Calendar.DAY_OF_MONTH)
                val daysArray = calendar.getJSONArray("data")
                if (daysArray.length() > 0) {
                    val list = mutableListOf<DayPrayerModel>()

                    for (i in 0 until daysArray.length()) {
                        val dayObj = daysArray.getJSONObject(i)
                        val timings = dayObj.getJSONObject("timings")
                        val dateObj = dayObj.getJSONObject("date")
                        val greg = dateObj.getJSONObject("gregorian")
                        val hijri = dateObj.getJSONObject("hijri")

                        val dayNum = greg.getString("day").toInt()
                        val weekdayAr = greg.optJSONObject("weekday")?.optString("ar") ?: ""
                        val gregDateStr = "%02d/%02d".format(Locale.ROOT,
                            greg.getString("day").toInt(),
                            greg.getJSONObject("month").getInt("number")
                        )
                        val hijriMonthAr = hijri.optJSONObject("month")?.optString("ar") ?: ""
                        val hijriDateStr = "${hijri.getString("day")} $hijriMonthAr"

                        val isToday = (dayNum == todayDay)

                        list.add(
                            DayPrayerModel(
                                dayNumber = greg.getString("day"),
                                dayNameAr = weekdayAr,
                                gregorianDate = gregDateStr,
                                hijriDate = hijriDateStr,
                                isToday = isToday,
                                fajr = timings.getString("Fajr").split(" ")[0].trim(),
                                sunrise = timings.getString("Sunrise").split(" ")[0].trim(),
                                dhuhr = timings.getString("Dhuhr").split(" ")[0].trim(),
                                asr = timings.getString("Asr").split(" ")[0].trim(),
                                maghrib = timings.getString("Maghrib").split(" ")[0].trim(),
                                isha = timings.getString("Isha").split(" ")[0].trim()
                            )
                        )
                    }

                    val firstDay = daysArray.optJSONObject(0)?.optJSONObject("date")?.optJSONObject("gregorian")
                    val firstHijri = daysArray.optJSONObject(0)?.optJSONObject("date")?.optJSONObject("hijri")
                    val monthNameAr = firstDay?.optJSONObject("month")?.optString("ar") ?: ""
                    val yearStr = firstDay?.optString("year") ?: ""
                    val hijriMonthName = firstHijri?.optJSONObject("month")?.optString("ar") ?: ""
                    val hijriYear = firstHijri?.optString("year") ?: ""

                    withContext(Dispatchers.Main) {
                        tvMonthTitle.text =
                            "جدول مواقيت الصلاة لشهر $monthNameAr $yearStr / $hijriMonthName $hijriYear"
                        tvLoading.visibility = View.GONE
                        renderMonthlyRows(list)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvLoading.text = "تعذر تحميل الجدول الشهري، تحقق من اتصال الإنترنت."
                }
            }
        }
    }

    private fun renderMonthlyRows(list: List<DayPrayerModel>) {
        val llRows = findViewById<LinearLayout>(R.id.ll_monthly_rows)
        val inflater = LayoutInflater.from(this)

        for ((index, item) in list.withIndex()) {
            val view = inflater.inflate(R.layout.item_day_prayer, llRows, false)
            val rowRoot = view.findViewById<LinearLayout>(R.id.ll_row_root)

            val tvDayNum = view.findViewById<TextView>(R.id.tv_day_num)
            val tvHijriDate = view.findViewById<TextView>(R.id.tv_hijri_date)
            val tvFajr = view.findViewById<TextView>(R.id.tv_fajr)
            val tvSunrise = view.findViewById<TextView>(R.id.tv_sunrise)
            val tvDhuhr = view.findViewById<TextView>(R.id.tv_dhuhr)
            val tvAsr = view.findViewById<TextView>(R.id.tv_asr)
            val tvMaghrib = view.findViewById<TextView>(R.id.tv_maghrib)
            val tvIsha = view.findViewById<TextView>(R.id.tv_isha)

            tvDayNum.text = item.gregorianDate
            tvHijriDate.text = item.hijriDate
            tvFajr.text = item.fajr
            tvSunrise.text = item.sunrise
            tvDhuhr.text = item.dhuhr
            tvAsr.text = item.asr
            tvMaghrib.text = item.maghrib
            tvIsha.text = item.isha

            if (item.isToday) {
                rowRoot.setBackgroundColor(getColor(R.color.config_row_highlight))
                tvDayNum.setTextColor(getColor(R.color.config_row_text))
                tvFajr.setTextColor(getColor(R.color.config_row_accent))
                tvSunrise.setTextColor(getColor(R.color.config_row_text))
                tvDhuhr.setTextColor(getColor(R.color.config_row_accent))
                tvAsr.setTextColor(getColor(R.color.config_row_accent))
                tvMaghrib.setTextColor(getColor(R.color.config_row_accent))
                tvIsha.setTextColor(getColor(R.color.config_row_accent))

                tvFajr.setTypeface(null, Typeface.BOLD)
                tvDhuhr.setTypeface(null, Typeface.BOLD)
                tvAsr.setTypeface(null, Typeface.BOLD)
                tvMaghrib.setTypeface(null, Typeface.BOLD)
                tvIsha.setTypeface(null, Typeface.BOLD)
            } else {
                if (index % 2 == 0) {
                    rowRoot.setBackgroundColor(getColor(R.color.config_row_alternate))
                } else {
                    rowRoot.setBackgroundColor(Color.TRANSPARENT)
                }
            }

            llRows.addView(view)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            fetchLocation()
        }
    }

    companion object {
        fun resolveCityName(context: Context, lat: Double, lon: Double): String {
            return try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale("ar"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: addr.countryName
                        if (!city.isNullOrEmpty()) return city
                    }
                }
                "الدار البيضاء"
            } catch (e: Exception) {
                "الدار البيضاء"
            }
        }

        const val DEFAULT_LATITUDE = 33.5731f
        const val DEFAULT_LONGITUDE = -7.5898f
    }
}

data class DayPrayerModel(
    val dayNumber: String,
    val dayNameAr: String,
    val gregorianDate: String,
    val hijriDate: String,
    val isToday: Boolean,
    val fajr: String,
    val sunrise: String,
    val dhuhr: String,
    val asr: String,
    val maghrib: String,
    val isha: String
)
