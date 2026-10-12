package com.khemisset.prayerwidget

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.io.IOException
import java.util.Locale

object PrayerCalculation {
    const val DEFAULT_METHOD = 3

    fun methodForCoordinates(context: Context, latitude: Double, longitude: Double): Int {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder is unavailable; using the international default calculation method")
            return DEFAULT_METHOD
        }

        return try {
            val geocoder = Geocoder(context, Locale.ROOT)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            val countryCode = addresses?.firstOrNull()?.countryCode
            if (countryCode.isNullOrBlank()) {
                Log.w(TAG, "Could not identify the location's country; using the international default calculation method")
                DEFAULT_METHOD
            } else {
                methodForCountryCode(countryCode)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not identify the location's country; using the international default calculation method", e)
            DEFAULT_METHOD
        }
    }

    fun methodForCountryCode(countryCode: String): Int =
        when (countryCode.uppercase(Locale.ROOT)) {
            "MA" -> 21
            "SA" -> 4
            "EG" -> 5
            "PK", "AF", "BD", "IN" -> 1
            "US", "CA" -> 2
            "IR" -> 7
            "BH", "OM" -> 8
            "KW" -> 9
            "QA" -> 10
            "SG" -> 11
            "FR" -> 12
            "TR" -> 13
            "RU" -> 14
            "AE" -> 16
            "MY" -> 17
            "TN" -> 18
            "DZ" -> 19
            "ID" -> 20
            "JO" -> 23
            else -> DEFAULT_METHOD
        }

    private const val TAG = "PrayerCalculation"
}
