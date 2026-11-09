package com.lidseeker.app.music

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lidseeker.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SmartScreensSmokeTest {
    @Test fun rendersSmartSearchAndDiagnosticsWithoutCredentials() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            if(android.os.Build.VERSION.SDK_INT>=27) { activity.setShowWhenLocked(true);activity.setTurnScreenOn(true) }
        }
        lateinit var vm: MusicViewModel
        instrumentation.runOnMainSync { vm=ViewModelProvider(activity)[MusicViewModel::class.java] }
        val deadline=android.os.SystemClock.elapsedRealtime()+60_000
        var ready=false
        while(!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
            instrumentation.runOnMainSync { ready=vm.ready }
            if(!ready) Thread.sleep(100)
        }
        assertTrue("Saved connection initialization did not finish within 60 seconds",ready)
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
            if(node==null) return false
            if(node.text?.toString()?.contains(text)==true || node.contentDescription?.toString()?.contains(text)==true) return true
            return (0 until node.childCount).any { contains(node.getChild(it),text) }
        }
        fun awaitVisible(text: String) {
            repeat(50) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()==context.packageName && contains(root,text)) return
                Thread.sleep(200)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir,"music-screen-check-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            fail("Expected app screen was not visible: $text; active package=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}")
        }
        fun screenshot(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } }
        try {
            instrumentation.runOnMainSync { vm.tab="Search";vm.detail=null }
            Thread.sleep(1000)
            awaitVisible("Find it. Play it. Request it.")
            screenshot("music-smart-search.png")
            instrumentation.runOnMainSync { vm.showDiagnostics=true }
            Thread.sleep(1500)
            awaitVisible("Connection diagnostics")
            screenshot("music-diagnostics.png")
            val results=kotlinx.coroutines.runBlocking { ConnectionDiagnostics.check(context,vm.navidrome,vm.requests,false) }
            assertEquals(listOf("Phone connection","Navidrome","Requests"),results.map { it.name })
            assertFalse(results[1].ok)
            assertFalse(results[2].ok)
            instrumentation.runOnMainSync {
                // Rendering fixture only: never sign in, send feedback or modify a server.
                vm.showDiagnostics=false;vm.connected=true;vm.feedbackKnown=true
                vm.tab="Library";vm.libraryType="Disliked";vm.heading="Disliked · pending deletion after 30 days"
                vm.items=listOf(LibraryItem("fixture-song","Song awaiting deletion","Test artist"))
                vm.feedback=mapOf("fixture-song" to Feedback.DISLIKED)
            }
            awaitVisible("Cancel deletion / remove dislike")
            screenshot("music-feedback.png")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
