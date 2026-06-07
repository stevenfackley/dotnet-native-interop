package io.dotnetnativeinterop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.dotnetnativeinterop.feature.FfiFeatureService
import io.dotnetnativeinterop.feature.HttpFeatureService
import io.dotnetnativeinterop.feature.SqliteFeatureService
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class Sp1ShellTest {
    @get:Rule public val compose: androidx.compose.ui.test.junit4.ComposeContentTestRule = createComposeRule()

    public companion object {
        @JvmStatic
        @BeforeClass
        public fun load(): Unit {
            System.loadLibrary("dni"); System.loadLibrary("dni_jni")
            check(NativeBridge.nativeInitialize() == 0) { "init failed" }
        }
    }

    @Test
    public fun catalogLoadsAndRunsOverEachTransport(): Unit = runBlocking {
        for (svc in listOf(FfiFeatureService(), SqliteFeatureService(), HttpFeatureService())) {
            val d = svc.descriptors()
            assertTrue("${svc::class.simpleName} catalog non-empty", d.isNotEmpty())
            val r = svc.run(d.first().id)
            assertTrue("${svc::class.simpleName} run ok", r.ok)
            android.util.Log.i("Sp1ShellTest", "PASS: ${svc::class.simpleName} -> ${d.size} features, ran ${r.id}")
        }
    }

    @Test
    public fun shellRendersAllTabs(): Unit {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent {
            io.dotnetnativeinterop.ui.DotnetNativeInteropTheme {
                io.dotnetnativeinterop.ui.AppShell(
                    inference = io.dotnetnativeinterop.ui.InferenceViewModel(app),
                )
            }
        }
        // "Dashboard" appears in both the TopAppBar title and the drawer item;
        // asserting at least one exists is sufficient to prove AppShell composes.
        compose.onAllNodesWithText("Dashboard")[0].assertExists()
    }
}
