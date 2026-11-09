package com.lidseeker.app.music

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lidseeker.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    @Test fun serviceKeepsPlayingWithScreenOff() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        Thread.sleep(1500)
        fun screenshot(name: String) { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } }
        screenshot("music-welcome.png")
        val file=File(context.cacheDir,"playback-test.wav")
        val rate=22050;val samples=rate*45;val dataSize=samples*2
        val buffer=ByteBuffer.allocate(44+dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+dataSize).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        repeat(samples) { buffer.putShort(0) }
        file.writeBytes(buffer.array())
        val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        val player=future.get(15,TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                player.setMediaItem(MediaItem.Builder().setMediaId("test-audio").setUri(android.net.Uri.fromFile(file)).setMediaMetadata(MediaMetadata.Builder().setTitle("Background playback test").setArtist("Local test audio").build()).build())
                player.prepare();player.play()
            }
            Thread.sleep(2500)
            var before=0L
            instrumentation.runOnMainSync { assertTrue("Player must start",player.isPlaying);before=player.currentPosition }
            screenshot("music-player-test.png")
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            Thread.sleep(4000)
            assertFalse("Display must be off for this test",(context.getSystemService(PowerManager::class.java)).isInteractive)
            instrumentation.runOnMainSync {
                assertTrue("Playback must continue after display off",player.isPlaying)
                assertTrue("Audio position must advance with display off",player.currentPosition-before>2500)
            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            instrumentation.runOnMainSync { player.stop();player.clearMediaItems();player.release();activity.finish() }
            file.delete()
        }
    }
}
