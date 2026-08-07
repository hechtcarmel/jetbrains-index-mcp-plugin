package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase

class EdtHeartbeatServiceTest : McpPlatformTestCase() {

    private lateinit var service: EdtHeartbeatService

    override fun setUp() {
        super.setUp()
        service = EdtHeartbeatService.getInstance()
        service.setLastHeartbeat(0)
    }

    fun testServiceReportsNullDurationWhenResponsive() {
        assertNull(
            "Duration should be null when EDT is responsive",
            service.edtUnresponsiveDurationMs()
        )
    }

    fun testServiceReturnsDurationWhenUnresponsive() {
        service.setLastHeartbeat(EdtHeartbeatService.UNRESPONSIVE_THRESHOLD_MS + 5000)

        val duration = service.edtUnresponsiveDurationMs()
        assertNotNull("Duration should be non-null when unresponsive", duration)
        assertTrue("Duration should be >= threshold", duration!! >= EdtHeartbeatService.UNRESPONSIVE_THRESHOLD_MS)
    }

    fun testServiceRecoversAfterFreshHeartbeat() {
        service.setLastHeartbeat(EdtHeartbeatService.UNRESPONSIVE_THRESHOLD_MS + 1000)
        assertNotNull("Should be unresponsive", service.edtUnresponsiveDurationMs())

        service.setLastHeartbeat(0)
        assertNull("Duration should be null after recovery", service.edtUnresponsiveDurationMs())
    }
}
