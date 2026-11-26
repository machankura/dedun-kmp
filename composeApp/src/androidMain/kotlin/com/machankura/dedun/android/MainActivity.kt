package com.machankura.dedun.android

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.NFCActivity
import com.machankura.dedun.android.background.StayConnectedService
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.DedunApp
import fr.acinq.phoenix.android.services.HceService
import com.machankura.dedun.compose.notifications.NotificationTaps

class MainActivity : ComponentActivity(), NFCActivity {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The application's runtime, not one of our own: an activity recreated over a living
        // process used to build a second set of repositories and a second PhoenixGlobal here.
        val runtime = (application as DedunApplication).runtime

        tapped(intent)

        // From here, in front, because Android will not start a foreground service from the
        // background: a reboot or a killed process brings it back on the next launch.
        if (ExperimentalFeatures.backgroundSync && StayConnectedService.isOn(this)) {
            StayConnectedService.start(this)
        }

        setContent {
            val navController = rememberNavController()

            DedunApp(
                navController = navController,
                runtime = runtime,
            )
        }
    }

    override fun stopHceService() {
        stopService(Intent(this@MainActivity, HceService::class.java))
    }

    override fun stopNfcReader() {
        if (com.machankura.compose.ui.composable.widgets.nfc.NfcStateRepository.isReading()) {
            com.machankura.compose.ui.composable.widgets.nfc.NfcStateRepository.updateState(
                com.machankura.compose.ui.composable.widgets.nfc.NfcState.Inactive)
        }
        nfcAdapter?.disableReaderMode(this@MainActivity)
    }

    /** A notification tapped while this activity was already up (it is `singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tapped(intent)
    }

    /** Hands a tapped notification's room to the navigation host. Phase 6. */
    private fun tapped(intent: Intent?) {
        val userPublicKey = intent?.getStringExtra(NotificationTaps.EXTRA_USER_PUBLIC_KEY) ?: return
        val chatRoomId = intent.getStringExtra(NotificationTaps.EXTRA_CHAT_ROOM_ID) ?: return
        NotificationTaps.opened(userPublicKey, chatRoomId)
    }
}
