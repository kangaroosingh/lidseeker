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
        lateinit var vm: MusicViewModel
        instrumentation.runOnMainSync { vm=ViewModelProvider(activity)[MusicViewModel::class.java] }
        repeat(30) { if(!vm.ready) Thread.sleep(100) }
        assertTrue(vm.ready)
        fun screenshot(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } }
        try {
            instrumentation.runOnMainSync { vm.tab="Search";vm.detail=null }
            Thread.sleep(1000)
            screenshot("music-smart-search.png")
            instrumentation.runOnMainSync { vm.showDiagnostics=true }
            Thread.sleep(1500)
            screenshot("music-diagnostics.png")
            val results=kotlinx.coroutines.runBlocking { ConnectionDiagnostics.check(context,vm.navidrome,vm.requests,false) }
            assertEquals(listOf("Phone connection","Navidrome","Requests"),results.map { it.name })
            assertFalse(results[1].ok)
            assertFalse(results[2].ok)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
