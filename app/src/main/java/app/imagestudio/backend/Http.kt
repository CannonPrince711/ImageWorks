package app.imagestudio.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // generation can be slow
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

suspend fun <T> OkHttpClient.call(request: Request, block: (Response) -> T): T =
    withContext(Dispatchers.IO) {
        try {
            newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw BackendException(errorMessage(resp))
                block(resp)
            }
        } catch (e: IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }

/** Pulls a readable message out of the common JSON error shapes. */
private fun errorMessage(resp: Response): String {
    val body = resp.body?.string().orEmpty()
    val detail = runCatching {
        val obj = Http.json.parseToJsonElement(body).jsonObject
        obj["error"]?.let { e ->
            runCatching { e.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: runCatching { e.jsonPrimitive.content }.getOrNull()
        } ?: obj["errors"]?.toString() ?: obj["message"]?.jsonPrimitive?.content
    }.getOrNull() ?: body.take(300)
    val prefix = when (resp.code) {
        401, 403 -> "API key rejected"
        402 -> "Out of credits"
        429 -> "Rate limited, try again shortly"
        in 500..599 -> "Server error"
        else -> "Request failed (${resp.code})"
    }
    return if (detail.isBlank()) prefix else "$prefix: $detail"
}
