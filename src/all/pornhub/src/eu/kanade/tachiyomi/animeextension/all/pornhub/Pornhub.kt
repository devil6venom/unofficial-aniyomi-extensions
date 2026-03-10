package eu.kanade.tachiyomi.animeextension.all.pornhub

import android.os.Build
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.pornhub.extractors.PhCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Pornhub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pornhub"
    override val baseUrl = "https://www.pornhub.com"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // =============================== User-Agent ==============================

    companion object {
        // Latest desktop Chrome UA strings (Chrome 135, March 2025)
        private const val UA_CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val UA_CHROME_MACOS =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        private const val UA_FIREFOX_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

        private val UA_MAP = mapOf(
            "Chrome (Windows)" to UA_CHROME_WINDOWS,
            "Chrome (macOS)"   to UA_CHROME_MACOS,
            "Firefox (Windows)" to UA_FIREFOX_WINDOWS,
        )

        const val PREF_UA        = "user_agent"
        const val PREF_AGE       = "age_restriction"
        const val PREF_QUALITY   = "preferred_quality"
        const val PREF_HLS_ONLY  = "hls_only"
    }

    /** Returns the User-Agent string chosen in preferences. */
    private val userAgent: String
        get() = UA_MAP[preferences.getString(PREF_UA, "Chrome (Windows)")]
            ?: UA_CHROME_WINDOWS

    /** Builds the shared request headers including the chosen User-Agent. */
    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", userAgent)
            .set("Referer", "$baseUrl/")

    /** Builds a Request with the age-verified cookie appended when the toggle is on. */
    private fun buildRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .headers(headersBuilder().build())
        if (preferences.getBoolean(PREF_AGE, true)) {
            builder.header("Cookie", "age_verified=1; platform=pc")
        }
        return builder.build()
    }

    // =============================== Popular ================================

    override fun popularAnimeSelector(): String =
        "ul.nf-videos.videos.search-video-thumbs li.pcVideoListItem.js-pop.videoblock.videoBox"

    override fun popularAnimeRequest(page: Int): Request =
        buildRequest("$baseUrl/video?o=tr&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            "$baseUrl${element.select("div.wrap div.phimage a").attr("href")}"
        )
        anime.title = fromHtml(
            element.select("div.wrap div.thumbnail-info-wrapper.clearfix span.title a").text()
        ).toString()
        anime.thumbnail_url = element.select("div.wrap div.phimage a img").attr("src").ifEmpty {
            element.select("div.wrap div.phimage a img").attr("data-mediumthumb")
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next a"

    // =============================== Latest =================================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request =
        buildRequest("$baseUrl/video?o=n&page=$page")

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Episodes ===============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]")?.data()
        val epDate = if (jsonString != null) {
            try {
                val jsonData = json.decodeFromString<VideoDetail>(jsonString)
                val dateParts = jsonData.uploadDate?.split("-")
                if (dateParts != null && dateParts.size >= 3) {
                    SimpleDateFormat("yyyy-MM-dd").parse("${dateParts[0]}-${dateParts[1]}-${dateParts[2]}")
                } else null
            } catch (e: Exception) { null }
        } else null

        val episode = SEpisode.create()
        episode.name = "Video"
        if (epDate != null) episode.date_upload = epDate.time
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)
        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")
    override fun episodeFromElement(element: Element) = throw Exception("not used")

    // =============================== Videos =================================

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return PhCdnExtractor(
            client = client,
            userAgent = userAgent,
            ageRestricted = preferences.getBoolean(PREF_AGE, true),
            hlsOnly = preferences.getBoolean(PREF_HLS_ONLY, false),
        ).videoFromUrl(url)
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, "720p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // =============================== Search =================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        buildRequest("$baseUrl/video/search?search=$query&page=$page")

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String =
        "ul.nf-videos.videos.search-video-thumbs li.pcVideoListItem.js-pop.videoblock.videoBox," +
        "ul.videos.search-video-thumbs li.pcVideoListItem.js-pop.videoblock.videoBox"

    // =============================== Details ================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val jsonString = document.selectFirst("script[type=\"application/ld+json\"]")?.data()
        if (jsonString != null) {
            try {
                val jsonData = json.decodeFromString<VideoDetail>(jsonString)
                anime.title = fromHtml(jsonData.name.toString()).toString()
                anime.author = jsonData.author.toString()
                anime.thumbnail_url = jsonData.thumbnailUrl
                anime.description = fromHtml(jsonData.description.toString()).toString()
            } catch (e: Exception) {
                anime.title = document.select("h1.title").text()
            }
        } else {
            anime.title = document.select("h1.title").text()
        }
        anime.genre = document.select("div.video-info-row div.categoriesWrapper a.item")
            .joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // =============================== Helpers ================================

    private fun fromHtml(html: String?): Spanned? {
        return if (html == null) SpannableString("")
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        else Html.fromHtml(html)
    }

    // =============================== Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        // 1. User-Agent selector
        ListPreference(screen.context).apply {
            key = PREF_UA
            title = "Desktop User-Agent"
            dialogTitle = "Desktop User-Agent"
            val uaKeys = UA_MAP.keys.toTypedArray()
            entries = uaKeys
            entryValues = uaKeys
            setDefaultValue("Chrome (Windows)")
            summary = "Active: %s\n\nSets the User-Agent header on every request. Chrome (Windows) is recommended — it is the most common desktop UA and least likely to be flagged."
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        // 2. Age restriction bypass toggle (default ON = 18+ content)
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AGE
            title = "18+ Age Restriction Bypass"
            summary = "Sends the age_verified=1 cookie on every request so Pornhub skips the age-gate and shows adult content. Enabled by default."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        // 3. HLS-only mode toggle (default OFF = include MP4 streams too)
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HLS_ONLY
            title = "HLS Streams Only"
            summary = "When enabled, only adaptive HLS streams are returned and direct MP4 downloads are skipped. Useful if your player handles HLS better than fixed-quality MP4s."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        // 4. Preferred quality
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "240p")
            entryValues = arrayOf("1080p", "720p", "480p", "240p")
            setDefaultValue("720p")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // =============================== Data Classes ===========================

    @Serializable
    data class VideoDetail(
        @SerialName("@context") var context: String? = null,
        @SerialName("@type") var type: String? = null,
        @SerialName("name") var name: String? = null,
        @SerialName("embedUrl") var embedUrl: String? = null,
        @SerialName("duration") var duration: String? = null,
        @SerialName("thumbnailUrl") var thumbnailUrl: String? = null,
        @SerialName("uploadDate") var uploadDate: String? = null,
        @SerialName("description") var description: String? = null,
        @SerialName("author") var author: String? = null,
        @SerialName("interactionStatistic") var interactionStatistic: ArrayList<InteractionStatistic> = arrayListOf(),
    )

    @Serializable
    data class InteractionStatistic(
        @SerialName("@type") var type: String? = null,
        @SerialName("interactionType") var interactionType: String? = null,
        @SerialName("userInteractionCount") var userInteractionCount: String? = null,
    )
}
