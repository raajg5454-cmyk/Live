package com.livehelp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private fun call(b: Request.Builder, token: String?): Pair<Int, JSONObject> {
        token?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            return r.code to (try { JSONObject(t) } catch (e: Exception) { JSONObject() })
        }
    }

    fun post(path: String, body: JSONObject) = call(
        Request.Builder().url(BuildConfig.SERVER_URL + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())), null)

    fun get(path: String, token: String) = call(Request.Builder().url(BuildConfig.SERVER_URL + path), token)
}
