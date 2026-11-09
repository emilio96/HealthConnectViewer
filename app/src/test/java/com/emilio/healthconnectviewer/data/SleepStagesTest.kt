package com.emilio.healthconnectviewer.data

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SleepStagesTest {

    private val night: Instant = Instant.parse("2026-08-01T23:00:00Z")

    private fun at(minutes: Long): Instant = night.plusSeconds(minutes * 60)

    private fun stage(fromMin: Long, toMin: Long, type: Int) =
        SleepSessionRecord.Stage(at(fromMin), at(toMin), type)

    private fun session(
        fromMin: Long,
        toMin: Long,
        stages: List<SleepSessionRecord.Stage>,
        origin: String = "com.app.cq.ring"
    ) = SleepSessionRecord(
        startTime = at(fromMin),
        startZoneOffset = ZoneOffset.UTC,
        endTime = at(toMin),
        endZoneOffset = ZoneOffset.UTC,
        stages = stages,
        metadata = Metadata(dataOrigin = DataOrigin(origin))
    )

    private fun minutesOf(stages: List<DisplayStage>, type: Int): Long =
        stages.filter { it.stageType == type }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

    @Test
    fun `a source that reports REM but never Awake gets its gaps filled with Awake`() {
        // This is com.app.cq.ring: it writes Light, Deep and REM, and simply omits
        // the stretches the user was awake, leaving holes inside the session.
        val s = session(
            0, 120,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage(30, 60, SleepSessionRecord.STAGE_TYPE_DEEP),
                // 60..90 missing — the user was awake
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_REM)
            )
        )

        val result = reconstructStages(listOf(s))

        assertEquals(GapFill.AWAKE, result.gapFill)
        assertEquals(30L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertTrue(
            result.stages.single { it.stageType == SleepSessionRecord.STAGE_TYPE_AWAKE }.inferred
        )
    }

    @Test
    fun `a source that reports Awake but never REM gets its gaps filled with REM`() {
        val s = session(
            0, 120,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage(30, 60, SleepSessionRecord.STAGE_TYPE_AWAKE),
                // 60..90 missing
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_DEEP)
            )
        )

        val result = reconstructStages(listOf(s))

        assertEquals(GapFill.REM, result.gapFill)
        assertEquals(30L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_REM))
    }

    @Test
    fun `a source that reports both leaves its gaps as holes, not as a stage`() {
        val s = session(
            0, 120,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_AWAKE),
                stage(30, 60, SleepSessionRecord.STAGE_TYPE_REM),
                // 60..90 missing, and we have no basis to name it
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_DEEP)
            )
        )

        val result = reconstructStages(listOf(s))

        assertEquals(GapFill.NONE, result.gapFill)
        // Reported as unlabelled minutes; drawing it as a grey block in the Light
        // band made a hole look like a measured stage.
        assertEquals(30L, result.unlabelledMinutes)
        assertTrue(result.stages.none { it.inferred })
    }

    @Test
    fun `a source that reports neither REM nor Awake gets holes, not invented REM`() {
        // Only Light and Deep. The gap could be either stage, so naming it would be
        // inventing the most clinically interesting one out of nothing.
        val s = session(
            0, 120,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                // 30..90 missing
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_DEEP)
            )
        )

        val result = reconstructStages(listOf(s))

        assertEquals(GapFill.NONE, result.gapFill)
        assertEquals(60L, result.unlabelledMinutes)
        assertEquals(0L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_REM))
    }

    @Test
    fun `each source is judged on its own stages, not on the union`() {
        // The regression this guards: the Mixing screen hands SleepChart the sessions
        // of every selected source at once. Deciding on the union meant a ring that
        // never writes Awake was treated as if it did, because a watch on the same
        // night wrote it — and the ring's gaps silently became unnameable.
        val ring = session(
            0, 120,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                // 30..90 awake, but this source never says so
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_REM)
            ),
            origin = "com.app.cq.ring"
        )
        val watch = session(
            0, 120,
            listOf(
                stage(0, 40, SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage(40, 60, SleepSessionRecord.STAGE_TYPE_REM),
                stage(60, 90, SleepSessionRecord.STAGE_TYPE_AWAKE),
                stage(90, 120, SleepSessionRecord.STAGE_TYPE_DEEP)
            ),
            origin = "com.sec.android.app.shealth"
        )

        val result = reconstructStages(listOf(ring, watch))

        assertEquals(2, result.sources.size)
        val ringReport = result.sources.single { it.origin == "com.app.cq.ring" }
        val watchReport = result.sources.single { it.origin == "com.sec.android.app.shealth" }

        assertEquals(GapFill.AWAKE, ringReport.gapFill)
        assertEquals(GapFill.NONE, watchReport.gapFill)
        assertEquals(0L, watchReport.unlabelledMinutes)
        // The ring's 60-minute hole became Awake, on top of the watch's own 30.
        assertEquals(90L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_AWAKE))
    }

    @Test
    fun `reported stages are surfaced so the UI can say what is really there`() {
        val s = session(
            0, 60,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage(30, 60, SleepSessionRecord.STAGE_TYPE_DEEP)
            )
        )

        val report = reconstructStages(listOf(s)).sources.single()

        assertEquals(
            setOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_DEEP
            ),
            report.reportedStages
        )
        assertEquals("Deep", stageTypeName(SleepSessionRecord.STAGE_TYPE_DEEP))
    }

    @Test
    fun `gaps shorter than five minutes are left alone`() {
        val s = session(
            0, 63,
            listOf(
                stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT),
                // 30..33 is scheduling noise, not a missing stage
                stage(33, 63, SleepSessionRecord.STAGE_TYPE_REM)
            )
        )

        val result = reconstructStages(listOf(s))
        assertTrue(result.stages.none { it.inferred })
    }

    @Test
    fun `head and tail gaps are filled too`() {
        val s = session(
            0, 120,
            listOf(
                // session opens at 0 but the first stage starts at 20
                stage(20, 100, SleepSessionRecord.STAGE_TYPE_REM)
            )
        )

        val result = reconstructStages(listOf(s))

        assertEquals(GapFill.AWAKE, result.gapFill)
        assertEquals(40L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_AWAKE))
    }

    @Test
    fun `the decision is taken across the whole night not per session`() {
        // Only the second session carries a REM block. The first must still be
        // treated as coming from a REM-reporting source, so its gap is Awake.
        val first = session(
            0, 60,
            listOf(stage(0, 30, SleepSessionRecord.STAGE_TYPE_LIGHT))
        )
        val second = session(
            70, 130,
            listOf(stage(70, 130, SleepSessionRecord.STAGE_TYPE_REM))
        )

        val result = reconstructStages(listOf(first, second))

        assertEquals(GapFill.AWAKE, result.gapFill)
        assertEquals(30L, minutesOf(result.stages, SleepSessionRecord.STAGE_TYPE_AWAKE))
    }

    @Test
    fun `a session with no staging at all becomes a single unknown block`() {
        val result = reconstructStages(listOf(session(0, 90, emptyList())))

        assertEquals(1, result.stages.size)
        assertEquals(SleepSessionRecord.STAGE_TYPE_UNKNOWN, result.stages.first().stageType)
        assertFalse(result.stages.first().inferred)
    }

    @Test
    fun `no sessions yields nothing`() {
        val result = reconstructStages(emptyList())
        assertTrue(result.stages.isEmpty())
        assertTrue(result.sources.isEmpty())
        assertEquals(GapFill.NONE, result.gapFill)
    }

    @Test
    fun `stage indices map onto the four displayed bands`() {
        assertEquals(0, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals(1, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals(1, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_SLEEPING))
        assertEquals(2, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_REM))
        assertEquals(3, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertEquals(3, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
        assertEquals(3, stageTypeToIndex(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
    }
}
