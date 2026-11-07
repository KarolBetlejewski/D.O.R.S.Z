package com.example.djicontroller

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Odbiera `USB_ACCESSORY_ATTACHED` po podłączeniu RC-N2 i przenosi do ekranu głównego
 * (wzorzec `UsbAttachActivity` z przykładu DJI). Samo połączenie z akcesorium otwiera MSDK.
 *
 * W odróżnieniu od przykładu nie używamy `FLAG_ACTIVITY_CLEAR_TASK`: ponowne wpięcie kabla
 * w trakcie lotu nie powinno odtwarzać ekranu od zera.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
