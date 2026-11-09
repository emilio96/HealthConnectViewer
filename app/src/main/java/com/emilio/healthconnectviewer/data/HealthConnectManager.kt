package com.emilio.healthconnectviewer.data

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Whether Health Connect can be used on this device at all.
 * Kept separate from the permission state: "not installed" and
 * "installed but not authorized" need very different UI.
 */
enum class HealthConnectAvailability {
    AVAILABLE,
    NOT_INSTALLED,
    UPDATE_REQUIRED
}

class HealthConnectManager(private val context: Context) {

    /**
     * Health Connect is only queried through this client, and the client is only
     * safe to create when the SDK reports itself available — [HealthConnectClient.getOrCreate]
     * throws otherwise. Every call site must check [availability] first.
     */
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /**
     * One read permission per data type the app actually consumes.
     * Requesting a permission the app never reads gets the listing rejected on Play,
     * so this map is the single source of truth: it drives both the request and the
     * per-type "do we have access?" checks.
     */
    val permissionsByType: Map<AppDataType, String> = mapOf(
        AppDataType.STEPS to HealthPermission.getReadPermission(StepsRecord::class),
        AppDataType.HEART_RATE to HealthPermission.getReadPermission(HeartRateRecord::class),
        AppDataType.SPO2 to HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        AppDataType.SLEEP to HealthPermission.getReadPermission(SleepSessionRecord::class),
        AppDataType.HRV to HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    val permissions: Set<String> = permissionsByType.values.toSet()

    private val _availability = MutableStateFlow(HealthConnectAvailability.AVAILABLE)
    val availability: StateFlow<HealthConnectAvailability> = _availability.asStateFlow()

    init {
        refreshAvailability()
    }

    /**
     * Re-read the SDK status. Worth calling on resume: the user can install or
     * update Health Connect while the app sits in the background.
     */
    fun refreshAvailability(): HealthConnectAvailability {
        val status = HealthConnectClient.getSdkStatus(context)
        val result = when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_INSTALLED
        }
        _availability.value = result
        return result
    }

    val isAvailable: Boolean
        get() = _availability.value == HealthConnectAvailability.AVAILABLE

    suspend fun getGrantedPermissions(): Set<String> {
        if (!isAvailable) return emptySet()
        return healthConnectClient.permissionController.getGrantedPermissions()
    }

    fun requestPermissionsActivityContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    /**
     * Read every record of [type] in the window.
     *
     * Health Connect caps a response at [PAGE_SIZE] records and hands back a page
     * token for the rest; reading only the first page silently truncates any busy
     * window (a month of heart rate, or a single day with two devices syncing).
     * This walks the pages to exhaustion.
     *
     * Exceptions are deliberately NOT swallowed — the caller needs to tell
     * "no data" apart from "the read failed".
     */
    suspend fun <T : Record> readRecords(
        type: KClass<T>,
        start: Instant,
        end: Instant
    ): List<T> {
        if (!isAvailable) return emptyList()
        if (!end.isAfter(start)) return emptyList()

        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken
                )
            )
            all += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    /**
     * Aggregate steps by hour using Health Connect's built-in deduplication:
     * when several apps write the same walk, the platform counts it once.
     * Returns sparse results (only hours with data).
     */
    suspend fun aggregateStepsByHour(start: Instant, end: Instant): List<HourlySteps> {
        if (!isAvailable) return emptyList()
        if (!end.isAfter(start)) return emptyList()

        val request = AggregateGroupByDurationRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(start, end),
            timeRangeSlicer = Duration.ofHours(1)
        )

        return healthConnectClient.aggregateGroupByDuration(request).map { result ->
            HourlySteps(
                startTime = result.startTime,
                endTime = result.endTime,
                count = result.result[StepsRecord.COUNT_TOTAL] ?: 0L
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000
    }
}

data class HourlySteps(
    val startTime: Instant,
    val endTime: Instant,
    val count: Long
)
