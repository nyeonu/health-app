package dev.nyeonu.healthapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Privacy and health permissions", style = MaterialTheme.typography.headlineSmall)
                        Text("This is the local privacy notice for the P0 proof of concept. It is not a published online policy.")
                        Text("Health App requests read access to steps, weight, heart rate, sleep sessions and exercise sessions in Health Connect. It reads only the categories you allow, when you start a manual sync in the foreground. It does not write health records or run scheduled background reads.")
                        Text("With your separate Google authorization and private-folder consent, the app converts authorized records into CSV files and uploads them over HTTPS to an app-created private folder in your Google Drive. Health Connect permission does not by itself authorize a Drive upload. The app does not automatically share these files with others or ChatGPT.")
                        Text("Health records, generated CSV content and Google access tokens are held in memory for the foreground flow; this P0 app does not intentionally save local health exports or tokens. App backup is disabled. Uploaded files remain in your Drive until you delete them, and syncing the same day can update that day's files. Health Connect retains its source records under your system settings.")
                        Text("You can deny individual categories or withdraw Health Connect access in system Health Connect settings. You can revoke Google authorization in your Google account's connected-app settings and use the app's forget-local-state action. Revoking access or uninstalling the app does not delete files already uploaded to Drive or source records in Health Connect. To remove exports, delete them in Drive and empty its trash according to Google's retention controls.")
                    }
                }
            }
        }
    }
}
