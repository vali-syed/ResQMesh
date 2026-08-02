package com.bitchat.android.model

import android.os.Parcelable
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Data model for emergency SOS alerts.
 */
@Parcelize
data class EmergencyPacket(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val deviceId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val emergencyType: String,
    val description: String,
    val status: String
) : Parcelable {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): EmergencyPacket? = runCatching {
            Gson().fromJson(json, EmergencyPacket::class.java)
        }.getOrNull()
    }
}
