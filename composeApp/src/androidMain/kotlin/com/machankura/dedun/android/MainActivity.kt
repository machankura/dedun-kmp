package com.machankura.dedun.android

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.NFCActivity
import com.machankura.dedun.compose.PlatformContext
import com.machankura.dedun.compose.DedunApp
import com.machankura.dedun.compose.DedunGlobal
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.android.services.HceService

class MainActivity : ComponentActivity(), NFCActivity {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()

            DedunApp(
                navController = navController,
                dedunGlobal = DedunGlobal(
                    platformContext = PlatformContext(
                        applicationContext = applicationContext
                    )
                ),
                phoenixGlobal = PhoenixGlobal(
                    ctx = fr.acinq.phoenix.utils.PlatformContext(
                        applicationContext = applicationContext
                    )
                )

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

}