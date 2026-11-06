package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FgsPolicyTest {

    @Test
    fun sdk29to33UsesLegacyFgs() {
        for (sdk in 29..33) {
            assertEquals(FgsPolicy.Policy.LegacyForegroundService, FgsPolicy.policyFor(sdk))
        }
    }

    @Test
    fun sdk34PlusUsesSpecialUse() {
        val p = FgsPolicy.policyFor(34)
        assertTrue(p is FgsPolicy.Policy.SpecialUse)
        assertEquals(FgsPolicy.SPECIAL_USE_SUBTYPE, (p as FgsPolicy.Policy.SpecialUse).subtype)
        // Android 16（本机验证目标）
        assertTrue(FgsPolicy.policyFor(36) is FgsPolicy.Policy.SpecialUse)
    }

    @Test
    fun belowMinSdkRejected() {
        assertFailsWith<IllegalArgumentException> { FgsPolicy.policyFor(28) }
    }

    @Test
    fun dataSyncNotAnOption() {
        // 黑名单钉住：全天网关绝不伪装成这些类型（plan.md §4.6）
        assertTrue("dataSync" in FgsPolicy.forbiddenTypes)
        assertTrue("mediaPlayback" in FgsPolicy.forbiddenTypes)
    }
}
