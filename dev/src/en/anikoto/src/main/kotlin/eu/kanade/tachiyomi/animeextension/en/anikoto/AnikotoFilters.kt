package eu.kanade.tachiyomi.animeextension.en.anikoto

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Catalog filters for Anikoto.
 * Genre/type/status/year/rating values verified against https://anikototv.to/filter HTML form (2026-06-27).
 *
 * ext-lib v16 makes all AnimeFilter subclasses abstract, so we use concrete subclasses.
 */
object AnikotoFilters {

    fun get(): AnimeFilterList = AnimeFilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
        SeasonFilter(),
        YearFilter(),
        RatingFilter(),
        SourceFilter(),
        SortFilter(),
    )

    fun buildQuery(filters: AnimeFilterList): String {
        val params = mutableListOf<String>()
        filters.list.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("genre[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is TypeFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("term_type[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is StatusFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("status[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is LanguageFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("language[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is SeasonFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("season[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is YearFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("year[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is RatingFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("rating[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is SourceFilter -> filter.state.filter { it.state }.forEach { cb ->
                    params.add("source[]=${java.net.URLEncoder.encode(cb.value, "UTF-8")}")
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        params.add("sort=${filter.values[filter.state]}")
                    }
                }
                else -> {}
            }
        }
        return params.joinToString("&")
    }

    // ── Concrete filter subclasses (ext-lib v16: all AnimeFilter subclasses are abstract) ──

    open class CheckBoxVal(name: String, val value: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    open class CheckBoxGroup(name: String, state: List<CheckBoxVal>) : AnimeFilter.Group<CheckBoxVal>(name, state)
    open class SelectVal(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values)

    // ── Genre filter (values from website's <input name="genre[]" value="...">) ──
    class GenreFilter : CheckBoxGroup("Genre", GENRES.map { CheckBoxVal(it.first, it.second) })

    // ── Type filter (values from website's <input name="term_type[]" value="...">) ──
    class TypeFilter : CheckBoxGroup("Type", listOf(
        CheckBoxVal("TV", "TV"), CheckBoxVal("TV Short", "TV_SHORT"),
        CheckBoxVal("Movie", "Movie"), CheckBoxVal("ONA", "ONA"),
        CheckBoxVal("OVA", "OVA"), CheckBoxVal("Special", "Special"),
        CheckBoxVal("Music", "Music"),
    ))

    // ── Status filter (values from website) ──
    class StatusFilter : CheckBoxGroup("Status", listOf(
        CheckBoxVal("Currently Airing", "currently-airing"),
        CheckBoxVal("Finished Airing", "finished-airing"),
        CheckBoxVal("Not Yet Aired", "not-yet-aired"),
    ))

    // ── Language filter (values from website) ──
    class LanguageFilter : CheckBoxGroup("Language", listOf(
        CheckBoxVal("Sub", "sub"), CheckBoxVal("Dub", "dub"),
    ))

    // ── Season filter (values from website) ──
    class SeasonFilter : CheckBoxGroup("Season", listOf(
        CheckBoxVal("Spring", "spring"), CheckBoxVal("Summer", "summer"),
        CheckBoxVal("Fall", "fall"), CheckBoxVal("Winter", "winter"),
    ))

    // ── Year filter (checkboxes for multi-select, matching website) ──
    class YearFilter : CheckBoxGroup("Year", (2026 downTo 1980).map { CheckBoxVal(it.toString(), it.toString()) })

    // ── Rating filter (values from website's <input name="rating[]" value="...">) ──
    class RatingFilter : CheckBoxGroup("Rating", listOf(
        CheckBoxVal("G", "G"), CheckBoxVal("PG", "PG"), CheckBoxVal("PG-13", "PG-13"),
        CheckBoxVal("R", "R"), CheckBoxVal("R+", "R+"), CheckBoxVal("Rx", "Rx"),
    ))

    // ── Source filter (values from website's <input name="source[]" value="...">) ──
    class SourceFilter : CheckBoxGroup("Source", listOf(
        CheckBoxVal("Manga", "manga"), CheckBoxVal("Original", "original"),
        CheckBoxVal("Light Novel", "light_novel"), CheckBoxVal("Web Novel", "web_novel"),
        CheckBoxVal("Novel", "novel"), CheckBoxVal("Web Manga", "web_manga"),
        CheckBoxVal("Visual Novel", "visual_novel"), CheckBoxVal("Game", "game"),
        CheckBoxVal("Video Game", "video_game"), CheckBoxVal("Card Game", "card_game"),
        CheckBoxVal("4-Koma Manga", "4-koma_manga"), CheckBoxVal("Music", "music"),
        CheckBoxVal("Book", "book"), CheckBoxVal("Picture Book", "picture_book"),
        CheckBoxVal("Mixed Media", "mixed_media"), CheckBoxVal("Radio", "radio"),
        CheckBoxVal("Other", "other"), CheckBoxVal("Unknown", "unknown"),
    ))

    // ── Sort filter (values from website's <option value="...">) ──
    class SortFilter : SelectVal("Sort", arrayOf(
        "default", "latest-updated", "latest-added", "score", "name-az",
        "release-date", "most-viewed", "number_of_episodes",
    ))

    // ── Genre list — verified against https://anikototv.to/filter form (2026-06-27) ──
    // Each pair is (displayName, formValue) where formValue matches the website's checkbox value.
    private val GENRES = listOf(
        "Action" to "1",
        "Adventure" to "2",
        "Cars" to "538",
        "Comedy" to "8",
        "Dementia" to "453",
        "Demons" to "119",
        "Drama" to "62",
        "Ecchi" to "214",
        "Fantasy" to "3",
        "Game" to "180",
        "Harem" to "215",
        "Historical" to "70",
        "Horror" to "222",
        "Isekai" to "74",
        "Josei" to "404",
        "Kids" to "46",
        "Magic" to "203",
        "Mahou Shoujo" to "2310",
        "Martial Arts" to "114",
        "Mecha" to "123",
        "Military" to "125",
        "Music" to "242",
        "Mystery" to "57",
        "Parody" to "162",
        "Police" to "136",
        "Psychological" to "73",
        "Romance" to "28",
        "Samurai" to "163",
        "School" to "14",
        "Sci-Fi" to "12",
        "Seinen" to "50",
        "Shoujo" to "252",
        "Shoujo Ai" to "235",
        "Shounen" to "15",
        "Shounen Ai" to "233",
        "Slice of Life" to "35",
        "Space" to "124",
        "Sports" to "29",
        "Super Power" to "16",
        "Supernatural" to "9",
        "Suspense" to "2316",
        "Thriller" to "54",
        "Vampire" to "58",
    )
}
