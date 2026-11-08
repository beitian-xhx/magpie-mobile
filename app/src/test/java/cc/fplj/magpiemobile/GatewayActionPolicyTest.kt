package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests: no Service, DataStore, controller or native process is created. */
class GatewayActionPolicyTest {
    private val start = GatewayActionPolicy.ACTION_START
    private val stop = GatewayActionPolicy.ACTION_STOP
    private val booleans = listOf(false, true)

    private fun decide(
        action: String?,
        mustStop: Boolean = false,
        ready: Boolean = true,
        hasController: Boolean = false,
        replay: Boolean = false,
    ) = GatewayActionPolicy.decide(action, mustStop, ready, hasController, replay)

    @Test
    fun onlyFreshExplicitStartWithForegroundAndNoControllerCanLaunch() {
        assertEquals(GatewayActionPolicy.Decision(GatewayActionPolicy.Operation.START), decide(start))
        assertFalse(decide(start).recordStopIntent)
    }

    @Test
    fun foregroundFailureDoesNotStartOrRecordUserStop() {
        assertEquals(GatewayActionPolicy.Operation.STOP_SELF, decide(start, ready = false).operation)
        assertFalse(decide(start, ready = false).recordStopIntent)
    }

    @Test
    fun duplicateStartKeepsExistingControllerWithoutAnotherLaunch() {
        for (ready in booleans) for (replay in booleans) {
            val result = decide(start, ready = ready, hasController = true, replay = replay)
            assertEquals(GatewayActionPolicy.Operation.KEEP_RUNNING, result.operation)
            assertFalse(result.recordStopIntent)
        }
    }

    @Test
    fun nullIntentNeverLaunchesAndNeverRewritesUserIntent() {
        for (ready in booleans) for (hasController in booleans) for (replay in booleans) {
            val result = decide(null, ready = ready, hasController = hasController, replay = replay)
            assertEquals(if (hasController) GatewayActionPolicy.Operation.KEEP_RUNNING
                else GatewayActionPolicy.Operation.STOP_SELF, result.operation)
            assertFalse(result.recordStopIntent)
        }
    }

    @Test
    fun unknownAndNearMatchActionsAreUntrustedNotStartOrStopCommands() {
        val unknown = listOf("", "START", "STOP", "START_GATEWAY", "STOP_GATEWAY",
            "android.intent.action.BOOT_COMPLETED", "untrusted.start.gateway", start.lowercase(),
            "$start ", " $start", "$start\n", "$start.extra", "$stop.extra")
        for (action in unknown) for (ready in booleans) for (hasController in booleans) {
            val result = decide(action, ready = ready, hasController = hasController)
            assertEquals(if (hasController) GatewayActionPolicy.Operation.KEEP_RUNNING
                else GatewayActionPolicy.Operation.STOP_SELF, result.operation)
            assertFalse(result.recordStopIntent)
        }
    }

    @Test
    fun explicitStopWinsWithoutForegroundControllerOrPreferencePrerequisites() {
        for (mustStop in booleans) for (ready in booleans) for (hasController in booleans)
            for (replay in booleans) {
                val result = decide(stop, mustStop, ready, hasController, replay)
                assertEquals(GatewayActionPolicy.Operation.STOP, result.operation)
                assertTrue(result.recordStopIntent)
            }
    }

    @Test
    fun mustStopWinsOverAnyActionButDoesNotInventUserStopIntent() {
        for (action in listOf(start, stop, null, "unknown")) for (ready in booleans)
            for (hasController in booleans) for (replay in booleans) {
                val result = decide(action, mustStop = true, ready = ready,
                    hasController = hasController, replay = replay)
                assertEquals(GatewayActionPolicy.Operation.STOP, result.operation)
                assertEquals(action == stop, result.recordStopIntent)
            }
    }

    @Test
    fun redeliveryOrRetryNeverAuthorizesAnotherStart() {
        for (ready in booleans) for (hasController in booleans) {
            val result = decide(start, ready = ready, hasController = hasController, replay = true)
            assertEquals(if (hasController) GatewayActionPolicy.Operation.KEEP_RUNNING
                else GatewayActionPolicy.Operation.STOP_SELF, result.operation)
            assertFalse(result.recordStopIntent)
        }
    }

    @Test
    fun startAuthorizationIsConjunctionOfAllRuntimeGuards() {
        for (action in listOf(start, stop, null, "unknown")) for (mustStop in booleans)
            for (ready in booleans) for (hasController in booleans) for (replay in booleans) {
                val result = decide(action, mustStop, ready, hasController, replay)
                val expectedStart = action == start && !mustStop && ready && !hasController && !replay
                assertEquals(expectedStart, result.operation == GatewayActionPolicy.Operation.START)
                assertEquals(action == stop, result.recordStopIntent)
                if (action == stop || mustStop) {
                    assertEquals(GatewayActionPolicy.Operation.STOP, result.operation)
                } else if (hasController) {
                    assertEquals(GatewayActionPolicy.Operation.KEEP_RUNNING, result.operation)
                } else if (!expectedStart) {
                    assertEquals(GatewayActionPolicy.Operation.STOP_SELF, result.operation)
                }
            }
    }

    @Test
    fun serviceActionConstantsPreserveNotificationStopAndExposeExplicitStart() {
        // const val references are inlined; this does not instantiate the Android Service.
        assertEquals("cc.fplj.magpiemobile.START_GATEWAY", GatewayService.ACTION_START)
        assertEquals("cc.fplj.magpiemobile.STOP_GATEWAY", GatewayService.ACTION_STOP)
        assertEquals(GatewayService.ACTION_START, start)
        assertEquals(GatewayService.ACTION_STOP, stop)
    }
}
