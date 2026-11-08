package app.imagestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.imagestudio.ui.ImageStudioRoot
import app.imagestudio.ui.theme.ImageStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ImageStudioTheme { ImageStudioRoot() } }
    }
}
