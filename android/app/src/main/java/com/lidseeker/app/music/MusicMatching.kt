package com.lidseeker.app.music

import java.text.Normalizer

object MusicMatching {
    fun title(value: String): String {
        val clean=value.replace(Regex("(?i)\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|full\\s+(?:video|audio)|lyrics?\\s+video|lyrical\\s+video|official\\s+visuali[sz]er)\\b")," ")
        return Normalizer.normalize(clean,Normalizer.Form.NFKD).replace(Regex("\\p{M}+"),"")
            .lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
    }
    private fun versions(value: String): Set<String> = title(value).split(' ').filter { it in setOf("live","remix","remastered","acoustic","instrumental","karaoke","sped","slowed") }.toSet()
    private fun artists(value: String): Set<String> = value.split(Regex("(?i);|,|&|\\bfeat\\.?\\s|\\bft\\.?\\s")).map { title(it) }.filter { it.isNotEmpty() }.toSet()
    /** Advisory match: never silently merges or prevents the user's request. */
    fun likelySame(name: String, artist: String, otherName: String, otherArtist: String): Boolean {
        if(versions(name)!=versions(otherName)) return false
        val a=title(name);val b=title(otherName)
        if(a.isBlank() || b.isBlank()) return false
        val exact=a==b
        val embedded=(a.split(' ').size>=2 && " $b ".contains(" $a ")) || (b.split(' ').size>=2 && " $a ".contains(" $b "))
        if(!exact && !embedded) return false
        val first=artists(artist);val second=artists(otherArtist)
        if(first.isNotEmpty() && second.isNotEmpty()) return first.intersect(second).isNotEmpty()
        // Missing credits produce a possible-match warning, never an "already owned" assertion.
        return exact || embedded
    }
    fun rank(query: String, item: LibraryItem): Int {
        val q=title(query);val t=title(item.title)
        return when { q==t -> 0; t.startsWith(q) -> 1; t.contains(q) -> 2; else -> 3 }
    }
}
