package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootVerificationScriptTest {

    @Test
    fun commandListFollowsSingleCommandPolicy() {
        val install = RootVerificationScript.installCmd()
        val launch = RootVerificationScript.launchCmd()
        val healthz = RootVerificationScript.healthzCmd()
        for (cmd in listOf(install, launch, healthz)) {
            assertTrue(!cmd.contains("|") && !cmd.contains(">") && !cmd.contains(";"), cmd)
        }
        assertTrue(install.startsWith("pm install -r /sdcard/Download/app-debug.apk"))
        assertTrue(launch.startsWith("am start -n cc.fplj.magpiemobile/.MainActivity"))
        assertTrue(healthz.startsWith("curl -s"))
    }

    @Test
    fun judgePassesOnBothOk() {
        val json = "{\"magpie\":\"ok: magpie 0.1.3188\",\"bun\":\"ok: 1.4.2\"}"
        val v = RootVerificationScript.judge(json)
        val p = v as RootVerificationScript.Verdict.Pass
        assertEquals("ok: magpie 0.1.3188", p.magpie)
    }

    @Test
    fun judgeFailsOnPendingOrError() {
        assertEquals(true, RootVerificationScript.judge("{\"magpie\":\"pending\",\"bun\":\"ok: 1.4.2\"}") is RootVerificationScript.Verdict.Fail)
        val fail = RootVerificationScript.judge("{\"magpie\":\"exit=1: exec format error\",\"bun\":\"ok: 1.4.2\"}") as RootVerificationScript.Verdict.Fail
        assertTrue(fail.reason.contains("exec format error"))
    }

    @Test
    fun judgeFailsOnMissingField() {
        assertTrue(RootVerificationScript.judge("{}") is RootVerificationScript.Verdict.Fail)
    }
}
