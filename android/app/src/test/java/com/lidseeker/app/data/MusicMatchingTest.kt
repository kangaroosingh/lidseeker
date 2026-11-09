package com.lidseeker.app.data

import com.lidseeker.app.music.MusicMatching
import org.junit.Assert.*
import org.junit.Test

class MusicMatchingTest {
    @Test fun recognizesPromotionalSuffixAndPunctuation() {
        assertTrue(MusicMatching.likelySame("Pateeli Chah Di (Official Music Video)","Sajjan Adeeb","Pateeli Chah Di","Sajjan Adeeb"))
        assertTrue(MusicMatching.likelySame("CHAN—CHANANI","Babbu Maan","Chan Chanani","Babbu Maan"))
    }
    @Test fun doesNotMergeVersionsOrDifferentArtists() {
        assertFalse(MusicMatching.likelySame("Bloom (Live)","A","Bloom","A"))
        assertFalse(MusicMatching.likelySame("Bloom (Remix)","A","Bloom","A"))
        assertFalse(MusicMatching.likelySame("Bloom","A","Bloom","B"))
        assertFalse(MusicMatching.likelySame("Song 2","A","Song 3","A"))
    }
    @Test fun supportsMultiArtistCreditsAndUnicode() {
        assertTrue(MusicMatching.likelySame("Pyara Pyara","Juss; Pragati Nagpal","Pyara Pyara","Juss, Pragati Nagpal"))
        assertTrue(MusicMatching.likelySame("ਪੰਜਾਬ","Babbu Maan","ਪੰਜਾਬ","Babbu Maan"))
        assertEquals("cafe",MusicMatching.title("Café"))
    }
    @Test fun missingCreditsAreOnlyPossibleMatches() {
        assertTrue(MusicMatching.likelySame("Proposal","","Proposal","Mehtab Virk"))
        assertFalse(MusicMatching.likelySame("","","Proposal","Mehtab Virk"))
    }
}
