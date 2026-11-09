package com.lidseeker.app.music

import android.content.ComponentName
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CarBrowserSmokeTest {
 @Test fun discoversCarLibraryAndFeedbackCommandsWithoutOpeningPhoneUi() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  lateinit var pending: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>
  instrumentation.runOnMainSync { pending=MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
  val browser=pending.get(60,TimeUnit.SECONDS)
  try {
   lateinit var rootPending: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<androidx.media3.common.MediaItem>>
   instrumentation.runOnMainSync { rootPending=browser.getLibraryRoot(null) }
   val root=rootPending.get(15,TimeUnit.SECONDS)
   assertEquals(0,root.resultCode);assertEquals(CarLibrary.ROOT,root.value?.mediaId)
   lateinit var childrenPending: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.LibraryResult<com.google.common.collect.ImmutableList<androidx.media3.common.MediaItem>>>
   instrumentation.runOnMainSync { childrenPending=browser.getChildren(CarLibrary.ROOT,0,10,null) }
   val children=childrenPending.get(15,TimeUnit.SECONDS)
   assertEquals(0,children.resultCode)
   assertEquals(listOf(CarLibrary.LIKED,CarLibrary.RECENT,CarLibrary.ARTISTS,CarLibrary.PLAYLISTS),children.value?.map { it.mediaId })
   assertTrue(children.value!!.all { it.mediaMetadata.isBrowsable==true && it.mediaMetadata.isPlayable==false })
   instrumentation.runOnMainSync { PlaybackService.commands.forEach { assertTrue(browser.availableSessionCommands.contains(androidx.media3.session.SessionCommand(it,android.os.Bundle.EMPTY))) } }
  } finally { instrumentation.runOnMainSync { browser.release() } }
 }
}
