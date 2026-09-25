package lt.a777.muzika.data

/**
 * One playable item, whatever it came from.
 *
 * [url] is the page URL that a source needs to resolve a stream; YouTube can
 * rebuild it from [id], other sources cannot, which is why it is carried.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val duration: Int = 0,          // seconds
    val thumb: String? = null,
    val source: String = "youtube",
    val url: String? = null,
    val album: String? = null,
    val trackNumber: Int = 0,
) {
    val subtitle: String get() = artist

    /**
     * The first credited artist, which is what grouping a library wants.
     *
     * Only separators actually surrounded by spaces count, so names carrying
     * their own punctuation - AC/DC, G&G Sindikatas, Tyler, The Creator -
     * survive whole instead of being cut down to a fragment.
     */
    val primaryArtist: String
        get() {
            val name = artist.trim()
            if (name.isEmpty()) return ""
            var cut = name.length
            for (separator in SEPARATORS) {
                val at = name.indexOf(separator, ignoreCase = true)
                if (at > 0) cut = minOf(cut, at)
            }
            return name.substring(0, cut).trim().trimEnd(',')
        }

    private companion object {
        val SEPARATORS = listOf(
            ", ", " & ", " x ", " feat. ", " feat ", " ft. ", " ft ",
            " with ", " and ", " \u2022 ", " / ", " \u00d7 ",
        )
    }
}
