package com.example.djicontroller.core

import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.StationCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kontrakt StationPort pod F5: strumień statusu z flagą łącza i polecenia stacji. */
class StationContractTest {

    @Test fun `status z flaga lacza - bez lacza wszystko zachowawczo false`() = runBlocking {
        val station = FakeStation(FakeClock())
        val offline = station.statusUpdates.first()
        assertFalse(offline.isConnected)
        assertFalse(offline.padClear)
        assertFalse(offline.dronePresent)

        station.publish(charging = true, dronePresent = true, padClear = true, ready = false)
        val online = station.statusUpdates.first()
        assertTrue(online.isConnected)
        assertTrue(online.charging && online.dronePresent && online.padClear)
        assertFalse(online.ready)
    }

    @Test fun `przycisk zasilania i ladowanie ida jako polecenia stacji`() = runBlocking {
        val station = FakeStation(FakeClock())
        station.triggerPowerButton(PowerAction.POWER_ON)
        station.setCharging(true)
        station.setCharging(false)
        assertEquals(
            listOf(StationCommand.PowerButton(PowerAction.POWER_ON), StationCommand.Charge(true), StationCommand.Charge(false)),
            station.commands,
        )
    }
}
