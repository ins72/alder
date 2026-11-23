package dev.alder

/**
 * The order the player tries its sources in: every source in the wanted audio language first (best quality first),
 * then every English/original source, then the rest. A source that is slow to start or fails gives way to the next
 * one in the SAME language, so a start timeout on one Hindi link tries the other Hindi links before an English or
 * other dub — it never jumps straight to another language just because one link was slow.
 */
internal object PlaybackOrder {
    private val HEIGHT = Regex("""(\d+)p""")

    /** Best quality for a dub/original group: the highest resolution at or under 1080p, else the lowest above it;
     *  HEVC wins a tie at the same height. Lower is better. The same pick the audio tab makes for a language. */
    fun score(s: Stream): Int {
        val h = HEIGHT.find(s.description)?.groupValues?.get(1)?.toIntOrNull() ?: s.height
        val resScore = when {
            h in 1..1080 -> 1080 - h
            h > 1080 -> 10_000 + h
            else -> 100_000
        }
        val hevc = s.description.contains("HEVC", true) || s.description.contains("H265", true)
        return resScore * 10 + (if (hevc) 0 else 1)
    }

    /**
     * [list] reordered for playback: the wanted language's sources first, then English/original, then the rest; each
     * group best quality first. [originalFirst] puts untagged (original) sources ahead of English when no language is
     * wanted — the "original" dub preference. With no wanted language and no such preference, English and original
     * share the first fallback group.
     */
    fun order(list: List<Stream>, wantAudio: String?, originalFirst: Boolean): List<Stream> {
        if (list.size < 2) return list
        val want = wantAudio?.takeIf { it.isNotBlank() }
        val first = ArrayList<Stream>()
        val second = ArrayList<Stream>()
        val rest = ArrayList<Stream>()
        for (s in list) when {
            want != null && s.audioLanguages.any { Languages.match(it, want) > 0 } -> first += s
            originalFirst && s.audioLanguages.isEmpty() -> first += s
            s.audioLanguages.isEmpty() || s.audioLanguages.any { Languages.same(it, "en") } -> second += s
            else -> rest += s
        }
        val byQuality = compareBy<Stream> { score(it) }
        return first.sortedWith(byQuality) + second.sortedWith(byQuality) + rest.sortedWith(byQuality)
    }
}