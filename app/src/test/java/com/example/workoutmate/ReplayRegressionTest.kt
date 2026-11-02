package com.example.workoutmate

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Replays recorded 17-keypoint sequences through the same pure Kotlin signal
 * and counter modules used by the app. Run this after every algorithm change.
 *
 * Fixtures live in app/src/test/resources/replay, one JSONL file per session,
 * and start with:
 *   # expected=70
 */
class ReplayRegressionTest {

    @Test
    fun replayFixtures() {
        val resource = javaClass.classLoader?.getResource("replay") ?: return
        val directory = File(resource.toURI())
        val fixtures = directory.listFiles { file -> file.extension == "jsonl" } ?: return
        if (fixtures.isEmpty()) return

        for (fixture in fixtures.sortedBy { it.name }) {
            val lines = fixture.readLines()
            val expected = lines
                .firstOrNull { it.startsWith("# expected=") }
                ?.substringAfter("# expected=")
                ?.trim()
                ?.toIntOrNull()
                ?: continue

            val counter = JumpCounter()
            var lastTimestamp = 0L
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val item = JsonParser.parseString(line).asJsonObject
                if (item.get("type")?.asString != "frame") continue
                val timestamp = item.get("t").asLong
                val array = item.get("p").asJsonArray
                val points = ArrayList<PosePoint>(MoveNetIndex.COUNT)
                for (index in 0 until minOf(MoveNetIndex.COUNT, array.size())) {
                    val point = array.get(index).asJsonArray
                    points.add(
                        PosePoint(
                            x = point.get(0).asFloat,
                            y = point.get(1).asFloat,
                            confidence = point.get(2).asFloat
                        )
                    )
                }
                if (points.size < MoveNetIndex.COUNT) continue
                val frame = PoseFrame(points)
                val signal = PoseSignal.extract(frame, 0.2f)
                counter.process(signal, timestamp)
                lastTimestamp = timestamp
            }

            val actual = counter.state.count
            val error = abs(actual - expected)
            val tolerance = max(2, ceil(expected * 0.05).toInt())
            println(
                "Replay ${fixture.name}: expected=$expected actual=$actual " +
                    "error=$error (${"%.1f".format(error * 100f / expected)}%) " +
                    "lastT=$lastTimestamp"
            )
            assertTrue(
                "Replay ${fixture.name} error $error exceeds tolerance $tolerance",
                error <= tolerance
            )
        }
    }
}
