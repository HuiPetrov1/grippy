// SPDX-License-Identifier: MPL-2.0
// Grippy: renders the launcher icon (round and squircle masks) and the splash. Not part of upstream.

package app.grippy.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import run.nuri.getagrip.R
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Icon(shape: Shape) {
        Box(Modifier.size(120.dp).clip(shape)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(120.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
        }
    }

    @Test fun launcherIcon() {
        compose.setContent {
            Row(
                Modifier.background(Color(0xFF808890)).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CircleShape)
                Icon(RoundedCornerShape(36.dp))
                // The splash: the foreground glyph on the splash colour.
                Box(Modifier.size(120.dp).background(Color(0xFF3346EE)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
                }
            }
        }
        val dir = File(System.getProperty("grippy.screenshots.dir") ?: "build/outputs/roborazzi")
        compose.onRoot().captureRoboImage(File(dir, "00-launcher-icon.png"))
    }
}
