package org.iicrom.locsim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.iicrom.locsim.ui.AppRoot
import org.iicrom.locsim.ui.theme.LocsimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocsimTheme {
                AppRoot()
            }
        }
    }
}
