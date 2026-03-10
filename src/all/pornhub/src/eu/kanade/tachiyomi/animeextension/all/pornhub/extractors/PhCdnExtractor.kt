package eu.kanade.tachiyomi.animeextension.all.pornhub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request

class PhCdnExtractor(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val ageRestricted: Boolean = true,
    private val hlsOnly: Boolean = false,
) {

    fun videoFromUrl(videoUrl: String): List<Video> {
        val key = Regex("(?:viewkey=|embed/)([a-zA-Z0-9]+)").find(videoUrl)
            ?.groupValues?.get(1) ?: return emptyList()

        val embedRequest = Request.Builder()
            .url("https://www.pornhub.com/embed/$key")
            .header("User-Agent", userAgent)
            .header("Referer", "https://www.pornhub.com/")
            .apply {
                if (ageRestricted) header("Cookie", "age_verified=1; platform=pc")
            }
            .build()

        val document = client.newCall(embedRequest).execute().asJsoup()

        val scriptContent = document.select("script").map { it.html() }
            .firstOrNull { it.contains("flashvars_") && it.contains("mediaDefinitions") }
            ?: return emptyList()

        // Collapse JS string concatenation e.g. keywords=" + "current" → keywords="current"
        val sanitizedScript = scriptContent.replace(Regex(""""\s*\+\s*""""), "")

        val videoList = mutableListOf<Video>()

        val entryRegex = Regex(
            """"videoUrl"\s*:\s*"([^"]+)"(?:[^}]*?)"quality"\s*:\s*"([^"]+)"(?:[^}]*?)"format"\s*:\s*"([^"]+)"""",
        )

        for (match in entryRegex.findAll(sanitizedScript)) {
            val rawUrl = match.groupValues[1].replace("\\/", "/")
            val quality = match.groupValues[2].trim()
            val format = match.groupValues[3].trim()

            if (rawUrl.isBlank() || quality.isBlank()) continue

            when {
                format == "hls" || quality.equals("hls", ignoreCase = true) -> {
                    val hlsStreams = parseHlsMaster(rawUrl)
                    if (hlsStreams.isNotEmpty()) {
                        videoList.addAll(hlsStreams)
                    } else {
                        videoList.add(Video(rawUrl, "HLS", rawUrl))
                    }
                }
                !hlsOnly && format == "mp4" && quality.all { it.isDigit() } -> {
                    val finalUrl = resolveRedirect(rawUrl) ?: rawUrl
                    videoList.add(Video(finalUrl, "${quality}p", finalUrl))
                }
            }
        }

        return videoList.sortedByDescending {
            it.quality.replace("p", "").toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    private fun resolveRedirect(apiUrl: String): String? {
        return try {
            val response = client.newCall(
                Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", userAgent)
                    .build()
            ).execute()
            val finalUrl = response.request.url.toString()
            response.body?.close()
            finalUrl.takeIf { it != apiUrl }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHlsMaster(masterUrl: String): List<Video> {
        val body = try {
            client.newCall(
                Request.Builder()
                    .url(masterUrl)
                    .header("User-Agent", userAgent)
                    .build()
            ).execute().body?.string() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }

        val baseUrl = masterUrl.substringBeforeLast("/") + "/"
        val videos = mutableListOf<Video>()

        val streamRegex = Regex("""#EXT-X-STREAM-INF:([^\n]+)\n([^\n]+)""")
        for (match in streamRegex.findAll(body)) {
            val attributes = match.groupValues[1]
            val path = match.groupValues[2].trim()
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attributes)?.groupValues?.get(1)
            val streamUrl = if (path.startsWith("http")) path else baseUrl + path
            val label = if (height != null) "${height}p" else "HLS"
            videos.add(Video(streamUrl, label, streamUrl))
        }
        return videos
    }
                                                          }
                                                          
