package com.spraymind

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DayForecast(
    val date: String,
    val precipMm: Double,
    val maxTempC: Double
) {
    val hasSignificantRain: Boolean get() = precipMm > 5.0
    val isHotAndDry: Boolean get() = maxTempC > 35.0 && precipMm < 1.0
}

object WeatherClient {

    // Open-Meteo: free, no API key, GDPR-compliant, 1 km resolution worldwide.
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    suspend fun getForecast(lat: Double, lon: Double): List<DayForecast>? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "$BASE_URL?latitude=$lat&longitude=$lon" +
                    "&daily=precipitation_sum,temperature_2m_max" +
                    "&forecast_days=7&timezone=auto"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout    = 6_000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val daily  = JSONObject(body).getJSONObject("daily")
                val dates  = daily.getJSONArray("time")
                val precip = daily.getJSONArray("precipitation_sum")
                val temps  = daily.getJSONArray("temperature_2m_max")

                (0 until dates.length()).map { i ->
                    DayForecast(
                        date      = dates.getString(i),
                        precipMm  = precip.optDouble(i, 0.0),
                        maxTempC  = temps.optDouble(i, 25.0)
                    )
                }
            } catch (e: Exception) {
                Log.w("SprayMind.Weather", "Forecast unavailable: ${e.message}")
                null
            }
        }
}
