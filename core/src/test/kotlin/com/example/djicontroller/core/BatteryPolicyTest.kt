package com.example.djicontroller.core

import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.orchestrator.BatteryDecision
import com.example.djicontroller.core.orchestrator.BatteryPolicy
import com.example.djicontroller.core.orchestrator.BatteryPolicyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {
    private val station = GeoPoint(52.2297, 21.0122)
    private val policy = BatteryPolicy(
        BatteryPolicyConfig(
            landingReservePercent = 15.0,
            returnCostPercentPerKm = 8.0,
            marginFactor = 1.25,
            minReturnThresholdPercent = 20,
            maxReturnThresholdPercent = 90,
            criticalPercent = 10,
        ),
    )

    private fun at(distanceM: Double, battery: Int?) =
        DroneState(batteryPercent = battery, location = station.offsetBy(distanceM, 0.0))

    @Test fun `threshold grows with distance`() {
        assertEquals(20, policy.returnThresholdPercent(0.0))      // 15 → min 20
        assertEquals(25, policy.returnThresholdPercent(1_000.0))  // 15 + 10
        assertEquals(35, policy.returnThresholdPercent(2_000.0))  // 15 + 20
        assertEquals(90, policy.returnThresholdPercent(20_000.0)) // limit górny
    }

    @Test fun `unknown position uses the configured assumption`() {
        assertEquals(policy.returnThresholdPercent(1_000.0), policy.returnThresholdPercent(null))
    }

    @Test fun `decisions`() {
        assertTrue(policy.evaluate(at(1_000.0, 60), station) is BatteryDecision.Continue)
        assertTrue(policy.evaluate(at(1_000.0, 25), station) is BatteryDecision.ReturnNow)
        assertTrue(policy.evaluate(at(1_000.0, 9), station) is BatteryDecision.LandNow)
        assertTrue(policy.evaluate(at(1_000.0, null), station) is BatteryDecision.Unknown)
    }

    @Test fun `drone warnings override the computed threshold`() {
        val low = at(10.0, 80).copy(isLowBatteryWarning = true)
        assertTrue(policy.evaluate(low, station) is BatteryDecision.ReturnNow)
        val critical = at(10.0, 80).copy(isSeriousLowBatteryWarning = true)
        assertTrue(policy.evaluate(critical, station) is BatteryDecision.LandNow)
    }
}
