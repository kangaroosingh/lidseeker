package com.lidseeker.app.data

import com.lidseeker.app.music.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SmartSearchTest {
    private val dispatcher=StandardTestDispatcher()
    private lateinit var nav: Navidrome
    private lateinit var repo: Repository
    private lateinit var vm: SmartSearchViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        nav=mockk(relaxed=true);repo=mockk(relaxed=true)
        every { nav.connection } returns MusicConnection("https://example.com","u","t","s")
        coEvery { repo.requests() } returns emptyList()
        coEvery { repo.youtube() } returns YouTubeHistory(true,emptyList())
        vm=SmartSearchViewModel(nav,repo)
    }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun libraryResultsSurviveCatalogOutage() = runTest {
        coEvery { nav.search(any()) } returns listOf(LibraryItem("one","Proposal","Mehtab Virk"))
        coEvery { repo.search(any(),any()) } throws java.net.SocketTimeoutException()
        vm.query="Proposal";vm.search();advanceUntilIdle()
        assertEquals("one",vm.songs.single().id)
        assertTrue(vm.notes.isNotEmpty())
        assertFalse(vm.loading)
        coVerify(exactly=0) { repo.youtubeSearch(any()) }
    }
    @Test fun duplicateCheckDoesNotSubmitUntilUserConfirms() = runTest {
        coEvery { nav.search(any()) } returns listOf(LibraryItem("one","Proposal","Mehtab Virk"))
        coEvery { repo.youtubeRequest(any()) } returns YouTubeJob(1,"abcdefghijk","Proposal","Mehtab Virk","queued")
        vm.choose(YouTubeVideo("abcdefghijk","Proposal",artist="Mehtab Virk"));advanceUntilIdle()
        assertTrue(vm.checked);assertTrue(vm.duplicates.isNotEmpty())
        coVerify(exactly=0) { repo.youtubeRequest(any()) }
        vm.submit();advanceUntilIdle()
        coVerify(exactly=1) { repo.youtubeRequest(any()) }
    }
    @Test fun editedDetailsInvalidateOldDuplicateCheck() = runTest {
        coEvery { nav.search(any()) } returns emptyList()
        vm.choose(YouTubeVideo("abcdefghijk","Proposal",artist="Mehtab Virk"));advanceUntilIdle()
        assertTrue(vm.checked)
        vm.title="Another recording";vm.edited();vm.submit();advanceUntilIdle()
        assertFalse(vm.checked)
        coVerify(exactly=0) { repo.youtubeRequest(any()) }
    }
}
