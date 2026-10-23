package be.julien.quotadeck

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun launchesRealActivityAndSecuresLoginDialog() {
        compose.onNodeWithText("0 / 4 comptes connectés").assertIsDisplayed()
        compose.onNodeWithText("Connecter Claude").performClick()
        compose.onNodeWithText("Continuer chez Claude").assertIsDisplayed()
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText("0 / 4 comptes connectés").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
    }
    @Test fun capturesActualDisconnectedDashboard() {
        compose.onNodeWithText("Le plein d’IA.").assertIsDisplayed()
        val image = compose.runOnIdle { compose.activity.window.decorView.drawToBitmap() }
        val file = File(System.getProperty("user.dir"), "build/qa/dashboard.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 1000)
        val sampledColors = mutableSetOf<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) sampledColors.add(image.getPixel(x, y))
        assertTrue("Dashboard screenshot must contain rendered UI, not a blank canvas", sampledColors.size > 20)
        println("QA_SCREENSHOT=" + file.absolutePath)
    }
}
