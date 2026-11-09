package com.example.healthconnectviewer.ui

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.healthconnectviewer.data.AggregatedDataPoint
import com.example.healthconnectviewer.data.AnalysisQuality
import com.example.healthconnectviewer.data.AppDataType
import com.example.healthconnectviewer.data.DesaturationAnalysis
import com.example.healthconnectviewer.data.HealthConnectAvailability
import com.example.healthconnectviewer.data.HealthConnectManager
import com.example.healthconnectviewer.data.SleepWindow
import com.example.healthconnectviewer.data.TypeDiagnostic
import com.example.healthconnectviewer.data.diagnoseType
import com.example.healthconnectviewer.data.SleepWindowStrategy
import com.example.healthconnectviewer.data.Spo2Sample
import com.example.healthconnectviewer.data.analyseOvernightSpo2
import com.example.healthconnectviewer.data.mixAndGroupData
import com.example.healthconnectviewer.data.resolveSleepWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit


enum class Timeframe { DAY, WEEK, MONTH }

/**
 * Explicit load state. The app used to represent a failed read, a missing
 * permission and a genuinely empty day all as an empty list, which left the user
 * staring at "no data" with no way to tell which had happened.
 */
sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val message: String) : LoadState
}

data class AnalysisResult(
    val spo2Data: List<AggregatedDataPoint>,
    val hrData: List<AggregatedDataPoint>,
    val riskLabel: String,
    val riskColor: Int,
    val desaturation: DesaturationAnalysis,
    val avgSpo2: Double?,
    val minSpo2: Double?,
    val avgHr: Double?,
    val sleepStart: String,
    val sleepEnd: String,
    val windowStrategy: SleepWindowStrategy,
    val sourcesUsed: List<String>
)

class MainViewModel(private val healthConnectManager: HealthConnectManager) : ViewModel() {

    val availability: StateFlow<HealthConnectAvailability> = healthConnectManager.availability

    private val _grantedTypes = MutableStateFlow<Set<AppDataType>>(emptySet())
    val grantedTypes: StateFlow<Set<AppDataType>> = _grantedTypes.asStateFlow()

    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _selectedDataType = MutableStateFlow(AppDataType.STEPS)
    val selectedDataType: StateFlow<AppDataType> = _selectedDataType.asStateFlow()

    private val _timeframe = MutableStateFlow(Timeframe.DAY)
    val timeframe: StateFlow<Timeframe> = _timeframe.asStateFlow()

    private val _selectedDate = MutableStateFlow(ZonedDateTime.now())
    val selectedDate: StateFlow<ZonedDateTime> = _selectedDate.asStateFlow()

    private val _chartData = MutableStateFlow<List<AggregatedDataPoint>>(emptyList())
    val chartData: StateFlow<List<AggregatedDataPoint>> = _chartData.asStateFlow()

    private val _viewerState = MutableStateFlow<LoadState>(LoadState.Idle)
    val viewerState: StateFlow<LoadState> = _viewerState.asStateFlow()

    private val _analysisState = MutableStateFlow<LoadState>(LoadState.Idle)
    val analysisState: StateFlow<LoadState> = _analysisState.asStateFlow()

    private val _availableOrigins = MutableStateFlow<List<String>>(emptyList())
    val availableOrigins: StateFlow<List<String>> = _availableOrigins.asStateFlow()

    private val _selectedOrigins = MutableStateFlow<Set<String>>(emptySet())
    val selectedOrigins: StateFlow<Set<String>> = _selectedOrigins.asStateFlow()

    private val _diagnostics = MutableStateFlow<List<TypeDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<TypeDiagnostic>> = _diagnostics.asStateFlow()

    private val _diagnosticsState = MutableStateFlow<LoadState>(LoadState.Idle)
    val diagnosticsState: StateFlow<LoadState> = _diagnosticsState.asStateFlow()

    private val _analysisResult = MutableStateFlow<AnalysisResult?>(null)
    val analysisResult: StateFlow<AnalysisResult?> = _analysisResult.asStateFlow()

    private val _sleepSessions = MutableStateFlow<List<SleepSessionRecord>>(emptyList())
    val sleepSessions: StateFlow<List<SleepSessionRecord>> = _sleepSessions.asStateFlow()

    /** Percentage change against the equivalent preceding period. */
    private val _trendPercent = MutableStateFlow<Double?>(null)
    val trendPercent: StateFlow<Double?> = _trendPercent.asStateFlow()

    /** Hourly steps as deduplicated by Health Connect itself (Mixing screen). */
    private val _aggregatedStepsData = MutableStateFlow<List<AggregatedDataPoint>>(emptyList())
    val aggregatedStepsData: StateFlow<List<AggregatedDataPoint>> = _aggregatedStepsData.asStateFlow()

    /** Origins whose values in the current chart are interpolated rather than measured. */
    private val _estimatedOrigins = MutableStateFlow<Set<String>>(emptySet())
    val estimatedOrigins: StateFlow<Set<String>> = _estimatedOrigins.asStateFlow()

    /** Origins that actually have data in the period on screen. */
    private val _originsWithData = MutableStateFlow<Set<String>>(emptySet())
    val originsWithData: StateFlow<Set<String>> = _originsWithData.asStateFlow()

    /**
     * Only one fetch may be in flight. Without cancellation a quick sequence of
     * taps left several reads racing, and whichever finished last won — which is
     * not necessarily the one the user asked for.
     */
    private var fetchJob: Job? = null
    private var analysisJob: Job? = null
    private var diagnosticsJob: Job? = null

    /** Whether the user is parked on "today" and should follow the clock over midnight. */
    private var followToday = true

    private val recordCache = LinkedHashMap<CacheKey, List<Record>>()

    /**
     * Every origin seen since the app started, for any data type or date.
     *
     * The source pills are built from this rather than from the current period, so a
     * tile keeps its position when the user pages to a day where that source happens
     * to have no data. Combined with sorting, this is what stops the tiles reshuffling
     * on every date change or selection.
     */
    private val knownOrigins = mutableSetOf<String>()

    init {
        checkPermissions()
    }

    /**
     * Called from the Activity's ON_RESUME. Permissions can be revoked in the
     * Health Connect app while we are backgrounded, Health Connect itself can be
     * installed or updated, and the day can roll over under a session left open
     * overnight.
     */
    fun onResume() {
        healthConnectManager.refreshAvailability()
        if (followToday) {
            val now = ZonedDateTime.now()
            if (now.toLocalDate() != _selectedDate.value.toLocalDate()) {
                _selectedDate.value = now
            }
        }
        checkPermissions()
    }

    fun checkPermissions() {
        viewModelScope.launch {
            if (healthConnectManager.availability.value != HealthConnectAvailability.AVAILABLE) {
                _grantedTypes.value = emptySet()
                _isAuthorized.value = false
                return@launch
            }
            val granted = try {
                healthConnectManager.getGrantedPermissions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewerState.value = LoadState.Failed(describe(e))
                return@launch
            }

            val types = healthConnectManager.permissionsByType
                .filterValues { it in granted }
                .keys
            _grantedTypes.value = types
            _isAuthorized.value = types.isNotEmpty()

            if (types.isNotEmpty()) {
                fetchData()
            }
        }
    }

    /** True when the app may actually read [type]; drives the per-type UI hints. */
    fun hasPermissionFor(type: AppDataType): Boolean = type in _grantedTypes.value

    fun setDataType(type: AppDataType) {
        if (_selectedDataType.value == type) return
        _selectedDataType.value = type
        fetchData()
    }

    fun setTimeframe(tf: Timeframe) {
        if (_timeframe.value == tf) return
        _timeframe.value = tf
        fetchData()
    }

    fun navigateDate(offset: Long) {
        val newDate = when (_timeframe.value) {
            Timeframe.DAY -> _selectedDate.value.plusDays(offset)
            Timeframe.WEEK -> _selectedDate.value.plusWeeks(offset)
            Timeframe.MONTH -> _selectedDate.value.plusMonths(offset)
        }
        val today = LocalDate.now()
        if (newDate.toLocalDate().isAfter(today)) return

        followToday = newDate.toLocalDate() == today
        _selectedDate.value = newDate
        fetchData()
    }

    fun setDateToToday() {
        followToday = true
        _selectedDate.value = ZonedDateTime.now()
        fetchData()
    }

    fun setOrigin(origin: String) {
        _selectedOrigins.value = setOf(origin)
    }

    fun toggleOrigin(origin: String) {
        val current = _selectedOrigins.value.toMutableSet()
        if (current.contains(origin) && current.size > 1) {
            current.remove(origin)
        } else {
            current.add(origin)
        }
        _selectedOrigins.value = current
    }

    /** Explicit refresh from the drawer: drops the cache and re-reads everything. */
    fun syncData() {
        recordCache.clear()
        knownOrigins.clear()
        checkPermissions()
        fetchAnalysisData()
    }

    fun fetchData() {
        if (!_isAuthorized.value) return

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _viewerState.value = LoadState.Loading
            try {
                val (start, end, intervalMin) = periodFor(_selectedDate.value, _timeframe.value)
                val dataType = _selectedDataType.value

                val sleepWindows = readSleepWindows(start, end)
                val records = readFor(dataType, start, end)

                if (dataType == AppDataType.SLEEP) {
                    _sleepSessions.value = records.filterIsInstance<SleepSessionRecord>()
                }

                val grouped = withContext(Dispatchers.Default) {
                    mixAndGroupData(records, dataType, start, end, intervalMin, sleepWindows)
                }
                _chartData.value = grouped
                _estimatedOrigins.value = grouped.flatMapTo(mutableSetOf()) { it.estimatedOrigins }

                _aggregatedStepsData.value = if (dataType == AppDataType.STEPS) {
                    aggregatedSteps(start, end, intervalMin)
                } else {
                    emptyList()
                }

                updateOrigins(dataType, grouped)
                _trendPercent.value = computeTrend(dataType, start, end, intervalMin, grouped)

                _viewerState.value = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chartData.value = emptyList()
                _aggregatedStepsData.value = emptyList()
                _viewerState.value = LoadState.Failed(describe(e))
            }
        }
    }

    private fun updateOrigins(dataType: AppDataType, grouped: List<AggregatedDataPoint>) {
        val present = if (dataType == AppDataType.SLEEP) {
            _sleepSessions.value.mapTo(mutableSetOf()) { it.metadata.dataOrigin.packageName }
        } else {
            grouped.flatMapTo(mutableSetOf()) { it.valuesByOrigin.keys }
        }

        knownOrigins += present
        _originsWithData.value = present
        // Sorted, and drawn from every origin seen so far: position is a function of
        // the name alone, never of the order the records happened to come back in.
        _availableOrigins.value = knownOrigins.sorted()

        val stillHasData = _selectedOrigins.value.intersect(present)
        _selectedOrigins.value = when {
            stillHasData.isNotEmpty() -> stillHasData
            present.isNotEmpty() -> setOf(present.minOrNull()!!)
            else -> emptySet()
        }
    }

    private suspend fun aggregatedSteps(
        start: Instant,
        end: Instant,
        intervalMin: Long
    ): List<AggregatedDataPoint> {
        val hourlySteps = healthConnectManager.aggregateStepsByHour(start, end)
        val formatter = labelFormatter(intervalMin)
        return hourlySteps.map { hs ->
            AggregatedDataPoint(
                timestamp = hs.startTime,
                timeLabel = formatter.format(hs.startTime),
                valuesByOrigin = mapOf(AGGREGATED_ORIGIN to hs.count.toDouble()),
                mixedValue = hs.count.toDouble()
            )
        }
    }

    /**
     * Compare the period against the one immediately before it.
     *
     * The previous period gets its own sleep windows: reusing the current period's
     * — as this used to — distributed last week's steps around this week's nights.
     */
    private suspend fun computeTrend(
        dataType: AppDataType,
        currentStart: Instant,
        currentEnd: Instant,
        intervalMin: Long,
        currentData: List<AggregatedDataPoint>
    ): Double? {
        return try {
            val duration = Duration.between(currentStart, currentEnd)
            val prevStart = currentStart.minus(duration)
            val prevEnd = currentStart

            val prevRecords = readFor(dataType, prevStart, prevEnd)
            if (prevRecords.isEmpty()) return null

            val prevSleepWindows = readSleepWindows(prevStart, prevEnd)
            val prevData = withContext(Dispatchers.Default) {
                mixAndGroupData(prevRecords, dataType, prevStart, prevEnd, intervalMin, prevSleepWindows)
            }

            val currentSummary = summarise(dataType, currentData)
            val prevSummary = summarise(dataType, prevData)

            if (prevSummary == null || currentSummary == null || prevSummary == 0.0) {
                null
            } else {
                ((currentSummary - prevSummary) / prevSummary) * 100.0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun summarise(dataType: AppDataType, data: List<AggregatedDataPoint>): Double? {
        val values = data.mapNotNull { it.mixedValue }
        if (values.isEmpty()) return null
        return if (dataType.isCumulative) values.sum() else values.average()
    }

    /**
     * Read every supported type over a deliberately wide window and report what came
     * back, per writing app. The window spans from noon the day before to noon the day
     * after, so a night is inside it whatever the sleep-window resolution decides — the
     * point is to see the raw contents of Health Connect, not what a screen chose to plot.
     */
    fun fetchDiagnostics() {
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            _diagnosticsState.value = LoadState.Loading
            try {
                val dayStart = _selectedDate.value.truncatedTo(ChronoUnit.DAYS)
                val from = dayStart.minusHours(12).toInstant()
                val to = dayStart.plusHours(36).toInstant()

                _diagnostics.value = AppDataType.entries.map { type ->
                    val granted = hasPermissionFor(type)
                    try {
                        diagnoseType(type, granted, readFor(type, from, to))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TypeDiagnostic(type, granted, emptyList(), describe(e))
                    }
                }
                _diagnosticsState.value = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.value = emptyList()
                _diagnosticsState.value = LoadState.Failed(describe(e))
            }
        }
    }

    fun fetchAnalysisData() {
        if (!_isAuthorized.value) {
            _analysisState.value = LoadState.Failed(
                "Grant Health Connect access to sleep, SpO2 and heart rate to run the overnight analysis."
            )
            return
        }

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _analysisState.value = LoadState.Loading
            try {
                val date = _selectedDate.value
                val dayStart = date.truncatedTo(ChronoUnit.DAYS)

                // A night is looked for from the previous evening to the following
                // afternoon, so late and early sleepers both land inside the window.
                val searchStart = dayStart.minusHours(6).toInstant()
                val searchEnd = dayStart.plusHours(14).toInstant()

                val sleepRecords = readFor(AppDataType.SLEEP, searchStart, searchEnd)
                    .filterIsInstance<SleepSessionRecord>()

                val windowsBySource = sleepRecords
                    .groupBy { it.metadata.dataOrigin.packageName }
                    .map { (_, records) -> records.minOf { it.startTime } to records.maxOf { it.endTime } }

                val window = resolveSleepWindow(
                    windowsBySource = windowsBySource,
                    fallbackStart = dayStart.toInstant(),
                    fallbackEnd = dayStart.plusHours(10).toInstant()
                )

                val spo2Records = readFor(AppDataType.SPO2, window.start, window.end)
                    .filterIsInstance<OxygenSaturationRecord>()
                val hrRecords = readFor(AppDataType.HEART_RATE, window.start, window.end)
                    .filterIsInstance<HeartRateRecord>()

                val analysis = withContext(Dispatchers.Default) {
                    // Event detection runs on the raw readings, never on the binned
                    // series: a desaturation lasts tens of seconds and is averaged
                    // out of existence by a fifteen-minute bucket.
                    val spo2Samples = spo2Records
                        .map { Spo2Sample(it.time, it.percentage.value) }
                        .sortedBy { it.time }
                    analyseOvernightSpo2(spo2Samples, window.start, window.end)
                }

                val spo2Grouped = withContext(Dispatchers.Default) {
                    mixAndGroupData(spo2Records, AppDataType.SPO2, window.start, window.end, ANALYSIS_BIN_MINUTES)
                }
                val hrGrouped = withContext(Dispatchers.Default) {
                    mixAndGroupData(hrRecords, AppDataType.HEART_RATE, window.start, window.end, ANALYSIS_BIN_MINUTES)
                }

                val rawSpo2 = spo2Records.map { it.percentage.value }
                val rawHr = hrRecords.flatMap { rec -> rec.samples.map { it.beatsPerMinute.toDouble() } }

                val sources = buildSet {
                    sleepRecords.forEach { add(it.metadata.dataOrigin.packageName) }
                    spo2Records.forEach { add(it.metadata.dataOrigin.packageName) }
                    hrRecords.forEach { add(it.metadata.dataOrigin.packageName) }
                }

                val (risk, riskColor) = riskFor(analysis)
                val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())

                _analysisResult.value = AnalysisResult(
                    spo2Data = spo2Grouped,
                    hrData = hrGrouped,
                    riskLabel = risk,
                    riskColor = riskColor,
                    desaturation = analysis,
                    avgSpo2 = rawSpo2.average().takeIf { rawSpo2.isNotEmpty() },
                    minSpo2 = rawSpo2.minOrNull(),
                    avgHr = rawHr.average().takeIf { rawHr.isNotEmpty() },
                    sleepStart = timeFormatter.format(window.start),
                    sleepEnd = timeFormatter.format(window.end),
                    windowStrategy = window.strategy,
                    sourcesUsed = sources.map { it.substringAfterLast(".") }.sorted()
                )
                _analysisState.value = LoadState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _analysisResult.value = null
                _analysisState.value = LoadState.Failed(describe(e))
            }
        }
    }

    /**
     * Severity bands follow the usual ODI reading, but only when the recording can
     * support an index at all. A sparse night gets an honest "not assessable"
     * rather than an invented severity.
     */
    private fun riskFor(analysis: DesaturationAnalysis): Pair<String, Int> {
        val index = analysis.indexPerHour
        if (index == null || analysis.quality != AnalysisQuality.ADEQUATE) {
            return "Not assessable" to COLOR_NEUTRAL
        }
        return when {
            index < 5 -> "Low" to COLOR_LOW
            index < 15 -> "Moderate" to COLOR_MODERATE
            index < 30 -> "High" to COLOR_HIGH
            else -> "Very high" to COLOR_SEVERE
        }
    }

    private suspend fun readSleepWindows(start: Instant, end: Instant): List<SleepWindow> {
        if (!hasPermissionFor(AppDataType.SLEEP)) return emptyList()
        return readFor(AppDataType.SLEEP, start, end)
            .filterIsInstance<SleepSessionRecord>()
            .map { SleepWindow(it.startTime, it.endTime) }
    }

    /**
     * Read (and briefly cache) the records backing one data type.
     * Reading a type the user has not granted returns nothing instead of throwing
     * a SecurityException that would blank the whole screen.
     */
    private suspend fun readFor(type: AppDataType, start: Instant, end: Instant): List<Record> {
        if (!hasPermissionFor(type)) return emptyList()

        val key = CacheKey(type, start, end)
        recordCache[key]?.let { return it }

        val records: List<Record> = when (type) {
            AppDataType.STEPS ->
                healthConnectManager.readRecords(StepsRecord::class, start, end)
            AppDataType.HEART_RATE ->
                healthConnectManager.readRecords(HeartRateRecord::class, start, end)
            AppDataType.SPO2 ->
                healthConnectManager.readRecords(OxygenSaturationRecord::class, start, end)
            AppDataType.SLEEP ->
                healthConnectManager.readRecords(SleepSessionRecord::class, start, end)
            AppDataType.HRV ->
                healthConnectManager.readRecords(HeartRateVariabilityRmssdRecord::class, start, end)
        }

        recordCache[key] = records
        while (recordCache.size > CACHE_ENTRIES) {
            recordCache.remove(recordCache.keys.first())
        }
        return records
    }

    private fun describe(e: Exception): String = when (e) {
        is SecurityException ->
            "Health Connect refused the read. Check the app's permissions in Health Connect."
        else ->
            e.message?.takeIf { it.isNotBlank() }?.let { "Could not read from Health Connect: $it" }
                ?: "Could not read from Health Connect."
    }

    private data class CacheKey(val type: AppDataType, val start: Instant, val end: Instant)

    companion object {
        const val AGGREGATED_ORIGIN = "Health Connect (Aggregated)"

        /** Binning used for the overnight charts. Display only — never for event detection. */
        private const val ANALYSIS_BIN_MINUTES = 5L

        private const val CACHE_ENTRIES = 12

        private const val COLOR_NEUTRAL = 0xFF94A3B8.toInt()
        private const val COLOR_LOW = 0xFF10B981.toInt()
        private const val COLOR_MODERATE = 0xFFF59E0B.toInt()
        private const val COLOR_HIGH = 0xFFF97316.toInt()
        private const val COLOR_SEVERE = 0xFFEF4444.toInt()

        /** Start, end and bucket size for a timeframe anchored on [date]. */
        fun periodFor(date: ZonedDateTime, timeframe: Timeframe): Triple<Instant, Instant, Long> =
            when (timeframe) {
                Timeframe.DAY -> {
                    val dayStart = date.truncatedTo(ChronoUnit.DAYS)
                    Triple(dayStart.toInstant(), dayStart.plusDays(1).toInstant(), 15L)
                }
                Timeframe.WEEK -> {
                    val weekStart = date
                        .minusDays(date.dayOfWeek.value.toLong() - 1)
                        .truncatedTo(ChronoUnit.DAYS)
                    Triple(weekStart.toInstant(), weekStart.plusWeeks(1).toInstant(), 1440L)
                }
                Timeframe.MONTH -> {
                    val monthStart = date.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
                    Triple(monthStart.toInstant(), monthStart.plusMonths(1).toInstant(), 1440L)
                }
            }

        fun labelFormatter(intervalMinutes: Long): DateTimeFormatter =
            if (intervalMinutes >= 1440) {
                DateTimeFormatter.ofPattern("MMM dd").withZone(java.time.ZoneId.systemDefault())
            } else {
                DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
            }
    }
}

class MainViewModelFactory(
    private val manager: HealthConnectManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(manager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
