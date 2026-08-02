package com.bitchat.android.net

import android.util.Log
import com.bitchat.android.BuildConfig
import com.bitchat.android.model.EmergencyPacket
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * RescueBridge - Bridges the offline mesh SOS packets to the internet backend.
 */
object RescueBridge {
    private const val TAG = "RescueBridge"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Attempts to forward an emergency packet to the configured backend.
     */
    fun forwardToBackend(packet: EmergencyPacket) {
        val url = BuildConfig.RESCUE_BACKEND_URL
        
        if (url.isBlank()) {
            Log.w(TAG, "Rescue backend URL is not configured. Packet held locally.")
            return
        }

        scope.launch {
            try {
                val json = packet.toJson()
                val body = json.toRequestBody(JSON_MEDIA_TYPE)
                
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("X-ResQMesh-Device", packet.deviceId)
                    .addHeader("X-ResQMesh-Emergency-ID", packet.id)
                    .build()

                Log.i(TAG, "Forwarding SOS ${packet.id} to backend...")
                
                OkHttpProvider.httpClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "✅ SOS packet ${packet.id} successfully delivered to backend.")
                    } else {
                        Log.e(TAG, "❌ Backend delivery failed: ${response.code} ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Network error while forwarding SOS: ${e.message}")
            }
        }
    }
}
