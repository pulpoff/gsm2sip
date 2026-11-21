package com.callagent.gateway

import android.content.pm.ServiceInfo
import com.callagent.gateway.service.GatewayService
import org.junit.Assert.assertEquals
import org.junit.Test

class FgsTypesTest {
    private val call = GatewayService.CALL_FGS_TYPES
    private val exempt = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED

    @Test
    fun firstStartTriesCallTypesThenSystemExempted() {
        assertEquals(listOf(call, exempt), GatewayService.fgsTypesToTry(0, promote = false, sdk = 37))
    }

    @Test
    fun noSystemExemptedBeforeApi34() {
        assertEquals(listOf(call), GatewayService.fgsTypesToTry(0, promote = false, sdk = 33))
    }

    @Test
    fun repostKeepsCurrentTypes() {
        assertEquals(listOf(exempt), GatewayService.fgsTypesToTry(exempt, promote = false, sdk = 37))
        assertEquals(listOf(call), GatewayService.fgsTypesToTry(call, promote = false, sdk = 37))
    }

    @Test
    fun promoteRetriesCallTypesAndKeepsFallback() {
        assertEquals(listOf(call, exempt), GatewayService.fgsTypesToTry(exempt, promote = true, sdk = 37))
    }
}
