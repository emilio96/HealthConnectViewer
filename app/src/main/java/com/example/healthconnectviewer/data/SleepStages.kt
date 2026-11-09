package com.example.healthconnectviewer.data

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant

/**
 * Sleep stage reconstruction.
 *
 * Sources disagree about what they bother to sync. Samsung Health writes every
 * stage including Awake; QRing (com.app.cq.ring) writes Awake never, leaving the
 * stretches the user was awake as unlabelled holes inside the session. Rendering
 * those holes as nothing under-reports whichever stage the source omits, and —
 * because the comparison matrix scores overlap between stages — it also skews the
 * agreement between two devices.
 *
 * Two rules matter here:
 *
 * 1. The decision is taken **per source**. A night can contain sessions from several
 *    apps, and what one of them omits says nothing about the other. Deciding on the
 *    union of their stages is how a ring that never reports Awake ends up treated as
 *    if it did, simply because a watch on the same night reported it.
 *
 * 2. A gap is named only when exactly one of REM and Awake is missing from that
 *    source, in which case the gap can only plausibly be that stage. If the source
 *    reports both, or neither, the gap is left as a genuine hole: counted and
 *    reported as unlabelled, never drawn as a stage. Guessing "REM" for a source
 *    that reports only Light and Deep invents the most clinically interesting stage
 *    out of nothing.
 */

/**
 * A stage ready to be drawn or scored. [inferred] marks the ones this file
 * reconstructed: they were never measured and must be presented as such.
 */
data class DisplayStage(
    val startTime: Instant,
    val endTime: Instant,
    val stageType: Int,
    val inferred: Boolean = false
)

/** What was filled into one source's gaps, for the UI to explain itself. */
enum class GapFill {
    /** Gaps left as genuine holes: the source reports both REM and Awake, or neither. */
    NONE,
    REM,
    AWAKE
}

/** What one source contributed, and what had to be reconstructed for it. */
data class SourceReport(
    val origin: String,
    val gapFill: GapFill,
    /** Stage types the source actually wrote, so the UI can say what is really there. */
    val reportedStages: Set<Int>,
    /** Minutes inside its sessions that carry no stage and could not be named. */
    val unlabelledMinutes: Long
)

data class StageReconstruction(
    /** Every source's stages on one timeline, chronological. */
    val stages: List<DisplayStage>,
    val sources: List<SourceReport>
) {
    val unlabelledMinutes: Long get() = sources.sumOf { it.unlabelledMinutes }

    /** Convenience for the single-source case, which is most of the UI. */
    val gapFill: GapFill get() = sources.singleOrNull()?.gapFill ?: GapFill.NONE
}

/** Gaps shorter than this are scheduling noise, not a missing stage. */
private const val MIN_GAP_MINUTES = 5L

fun reconstructStages(sessions: List<SleepSessionRecord>): StageReconstruction {
    if (sessions.isEmpty()) return StageReconstruction(emptyList(), emptyList())

    val allStages = mutableListOf<DisplayStage>()
    val reports = mutableListOf<SourceReport>()

    for ((origin, originSessions) in sessions.groupBy { it.metadata.dataOrigin.packageName }) {
        val reported = originSessions.flatMap { it.stages }.mapTo(mutableSetOf()) { it.stage }
        val reportsRem = SleepSessionRecord.STAGE_TYPE_REM in reported
        val reportsAwake = reported.any { it in AWAKE_TYPES }

        val gapFill = when {
            reportsRem && !reportsAwake -> GapFill.AWAKE
            reportsAwake && !reportsRem -> GapFill.REM
            else -> GapFill.NONE
        }
        val fillType = when (gapFill) {
            GapFill.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
            GapFill.REM -> SleepSessionRecord.STAGE_TYPE_REM
            GapFill.NONE -> null
        }

        var unlabelled = 0L

        fun handleGap(from: Instant, to: Instant) {
            if (Duration.between(from, to).toMinutes() < MIN_GAP_MINUTES) return
            if (fillType == null) {
                unlabelled += Duration.between(from, to).toMinutes()
            } else {
                allStages.add(DisplayStage(from, to, fillType, inferred = true))
            }
        }

        for (session in originSessions.sortedBy { it.startTime }) {
            val realStages = session.stages.sortedBy { it.startTime }

            if (realStages.isEmpty()) {
                // No staging at all: one undifferentiated block, not a gap to guess at.
                allStages.add(
                    DisplayStage(
                        session.startTime,
                        session.endTime,
                        SleepSessionRecord.STAGE_TYPE_UNKNOWN
                    )
                )
                continue
            }

            var lastEnd = session.startTime
            for (stage in realStages) {
                handleGap(lastEnd, stage.startTime)
                allStages.add(DisplayStage(stage.startTime, stage.endTime, stage.stage))
                lastEnd = maxOf(lastEnd, stage.endTime)
            }
            handleGap(lastEnd, session.endTime)
        }

        reports.add(SourceReport(origin, gapFill, reported, unlabelled))
    }

    return StageReconstruction(allStages.sortedBy { it.startTime }, reports.sortedBy { it.origin })
}

private val AWAKE_TYPES = setOf(
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
)

/**
 * Index used by the comparison matrix: 0=Deep, 1=Light, 2=REM, 3=Awake.
 * Unknown folds into Light, which is what the chart does too.
 */
fun stageTypeToIndex(stageType: Int): Int = when (stageType) {
    SleepSessionRecord.STAGE_TYPE_DEEP -> 0
    SleepSessionRecord.STAGE_TYPE_LIGHT,
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> 1
    SleepSessionRecord.STAGE_TYPE_REM -> 2
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> 3
    else -> 1
}

/** Human name for a raw Health Connect stage constant. */
fun stageTypeName(stageType: Int): String = when (stageType) {
    SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
    SleepSessionRecord.STAGE_TYPE_REM -> "REM"
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "Awake in bed"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "Out of bed"
    else -> "Unknown"
}
