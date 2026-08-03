package com.example.deskpet.data

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/**
 * Minimal Supabase REST client for desk pet.
 * Reads credentials from config/supabase.properties in assets.
 */
object SupabaseClient {

    private var baseUrl: String = ""
    private var anonKey: String = ""
    private var isConfigured: Boolean = false

    private const val TAG = "SupabaseClient"

    fun init(propertiesStream: java.io.InputStream) {
        try {
            val props = Properties()
            props.load(propertiesStream)
            baseUrl = props.getProperty("SUPABASE_URL", "")
            anonKey = props.getProperty("SUPABASE_ANON_KEY", "")
            isConfigured = baseUrl.isNotBlank() && anonKey.isNotBlank()
            if (isConfigured) {
                Log.i(TAG, "Supabase configured: $baseUrl")
            } else {
                Log.w(TAG, "Supabase config incomplete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load supabase.properties", e)
        }
    }

    /**
     * POST JSON to a Supabase REST endpoint.
     * Returns the response body as a string, or null on failure.
     */
    fun post(table: String, jsonBody: String): String? {
        if (!isConfigured) return null
        return try {
            val url = URL("$baseUrl/rest/v1/$table")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=representation")
            }
            conn.outputStream.use { os -> os.write(jsonBody.toByteArray()) }

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                Log.e(TAG, "POST $table failed: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $table error", e)
            null
        }
    }

    /**
     * GET from a Supabase REST endpoint.
     * Returns the response body as a string, or null on failure.
     */
    fun get(table: String, queryParams: String = ""): String? {
        if (!isConfigured) return null
        return try {
            val queryString = if (queryParams.isNotBlank()) "?$queryParams" else ""
            val url = URL("$baseUrl/rest/v1/$table$queryString")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
            }

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                Log.e(TAG, "GET $table failed: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $table error", e)
            null
        }
    }

    /**
     * Log a gesture event to the gesture_log table.
     */
    fun logGesture(type: String, detail: String = "") {
        val json = buildString {
            append("{")
            append("\"gesture_type\":\"$type\"")
            if (detail.isNotBlank()) append(",\"detail\":\"$detail\"")
            append("}")
        }
        post("gesture_log", json)
    }

    /**
     * Log foreground app to the foreground_app_log table.
     */
    fun logApp(packageName: String, appName: String = "") {
        val json = """{"package_name":"$packageName","app_name":"$appName"}"""
        post("foreground_app_log", json)
    }

    /**
     * Read current clawd state.
     */
    fun getClawdState(): String? {
        return get("clawd_state", "limit=1&order=created_at.desc")
    }
}
