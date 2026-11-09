package com.lidseeker.app.music
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class LikedSongsTest {
 private class Api: LikesApi {
  val lists=mutableListOf<LibraryItem>();val rows=mutableMapOf<String,MutableList<LibraryItem>>();var creates=0
  override suspend fun playlists()=lists.toList()
  override suspend fun songs(playlist: LibraryItem)=rows[playlist.id]?.toList().orEmpty()
  override suspend fun create(name: String): LibraryItem { creates++;return LibraryItem("p$creates",name,kind="playlist",owner="Harsh").also { lists+=it;rows[it.id]=mutableListOf() } }
  override suspend fun add(playlist: String,song: String) { rows.getValue(playlist)+=LibraryItem(song,song) }
  override suspend fun remove(playlist: String,index: Int) { rows.getValue(playlist).removeAt(index) }
 }
 @Test fun likeCreatesOnceAndDoesNotDuplicate()=runTest {
  val api=Api();val likes=LikedSongs(api,"Harsh");likes.set("song",true);likes.set("song",true)
  assertEquals(1,api.creates);assertEquals(listOf("song"),likes.songs().map { it.id })
 }
 @Test fun unlikeRemovesAllCopiesButPreservesOtherEntries()=runTest {
  val api=Api();val likes=LikedSongs(api,"Harsh");likes.set("keep",true);likes.set("remove",true)
  api.add("p1","keep2");api.add("p1","remove");likes.set("remove",false)
  assertEquals(listOf("keep","keep2"),likes.songs().map { it.id })
 }
 @Test fun neverTakesOverAnotherUsersPlaylist()=runTest {
  val api=Api();api.lists+=LibraryItem("other",LikedSongs.NAME,kind="playlist",owner="Gagan")
  LikedSongs(api,"Harsh").set("song",true);assertEquals(1,api.creates);assertFalse(api.rows.containsKey("other"))
 }
 @Test fun ambiguousNamesFailWithoutMutation()=runTest {
  val api=Api();repeat(2) { api.lists+=LibraryItem("$it",LikedSongs.NAME,kind="playlist",owner="Harsh") }
  try { LikedSongs(api,"Harsh").set("song",true);fail() } catch(_: IllegalStateException) { };assertEquals(0,api.creates)
 }
 @Test fun dislikeLikeAndClearAreExclusive()=runTest {
  val api=Api();val f=SongFeedback(LikedSongs(api,"Harsh"),LikedSongs(api,"Harsh",SongFeedback.DISLIKED_NAME))
  f.set("song",Feedback.DISLIKED);assertEquals(Feedback.DISLIKED,f.states()["song"])
  f.set("song",Feedback.LIKED);assertEquals(Feedback.LIKED,f.states()["song"])
  assertTrue(api.songs(api.lists.single { it.title==SongFeedback.DISLIKED_NAME }).isEmpty())
  f.set("song",Feedback.NONE);assertFalse(f.states().containsKey("song"))
 }
}
