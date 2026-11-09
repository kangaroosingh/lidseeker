package com.lidseeker.app.music
import org.junit.Assert.*
import org.junit.Test
class CarLibraryTest {
 @Test fun idsPreserveServerIdsAndRejectForeignMedia() {
  assertEquals("song" to "abc:123",CarLibrary.parse("song:abc:123"))
  listOf("https://example.com/audio","music:root","song:","untyped").forEach { try { CarLibrary.parse(it);fail() } catch(_: IllegalArgumentException) { } }
 }
 @Test fun pagesAreBoundedAndOverflowSafe() {
  assertEquals(listOf(3,4),CarLibrary.page((1..5).toList(),1,2));assertTrue(CarLibrary.page((1..5).toList(),Int.MAX_VALUE,Int.MAX_VALUE).isEmpty())
  try { CarLibrary.page(listOf(1),-1,2);fail() } catch(_: IllegalArgumentException) { }
 }
}
