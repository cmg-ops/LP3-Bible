package com.dailyhobbyist.bible

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns everything about Bible data on disk.
 *
 * Layout inside the tool's private files directory:
 *   bibles/
 *     KJV/
 *       meta.txt            (translation title, marks download complete)
 *       book_0.json         (Genesis — one BookJson per file)
 *       ...
 *       book_65.json        (Revelation)
 *
 * Downloading fetches the single whole-bible JSON, splits it into
 * per-book files, then writes meta.txt last so a half-finished
 * download is never mistaken for a complete one.
 */
class BibleStore(private val rootDir: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp)
    private val biblesDir = File(rootDir, "bibles").also { it.mkdirs() }

    // Small read cache: the last few parsed books for the current version.
    private val cache = LinkedHashMap<String, BookJson>()
    private val cacheLimit = 4

    // ── Download state ──

    fun isDownloaded(versionId: String): Boolean =
        File(versionDir(versionId), "meta.txt").exists()

    fun downloadedVersionIds(): List<String> =
        BibleVersions.catalog.map { it.id }.filter { isDownloaded(it) }

    suspend fun download(version: VersionInfo, onProgress: (Float) -> Unit) {
        // Phase 1 (0.0 – 0.7): network download of the whole-bible JSON
        val body: String = withContext(Dispatchers.IO) {
            val response = client.get(version.url) {
                onDownload { sent, total ->
                    if (total != null && total > 0L) {
                        onProgress(0.7f * (sent.toFloat() / total.toFloat()))
                    }
                }
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Download failed: HTTP ${response.status.value}")
            }
            response.bodyAsText()
        }

        // Phase 2 (0.7 – 1.0): parse and split into per-book files
        withContext(Dispatchers.IO) {
            onProgress(0.72f)
            val whole = json.decodeFromString<WholeBibleJson>(body)
            if (whole.books.size < 66) {
                throw IllegalStateException("Unexpected data: only ${whole.books.size} books")
            }

            val dir = versionDir(version.id)
            dir.deleteRecursively()
            dir.mkdirs()

            whole.books.take(66).forEachIndexed { index, book ->
                val cleaned = book.copy(
                    chapters = book.chapters.map { ch ->
                        ch.copy(
                            verses = ch.verses.map { v ->
                                v.copy(text = v.text.replace("[", "").replace("]", "").trim())
                            }
                        )
                    }
                )
                File(dir, "book_$index.json").writeText(json.encodeToString(BookJson.serializer(), cleaned))
                onProgress(0.72f + 0.27f * ((index + 1) / 66f))
            }

            // meta written last = download complete marker
            File(dir, "meta.txt").writeText(whole.translation)
            onProgress(1f)
        }
    }

    fun delete(versionId: String) {
        versionDir(versionId).deleteRecursively()
        cache.keys.removeAll { it.startsWith("$versionId/") }
    }

    // ── Reading ──

    suspend fun loadBook(versionId: String, bookIndex: Int): BookJson =
        withContext(Dispatchers.IO) {
            val key = "$versionId/$bookIndex"
            cache[key]?.let { return@withContext it }
            val file = File(versionDir(versionId), "book_$bookIndex.json")
            val book = json.decodeFromString<BookJson>(file.readText())
            if (cache.size >= cacheLimit) cache.remove(cache.keys.first())
            cache[key] = book
            book
        }

    suspend fun loadChapter(versionId: String, bookIndex: Int, chapter: Int): List<VerseJson> {
        val book = loadBook(versionId, bookIndex)
        return book.chapters.firstOrNull { it.chapter == chapter }?.verses ?: emptyList()
    }

    // ── Fuzzy search ──

    data class SearchHit(
        val bookIndex: Int,
        val chapter: Int,
        val verse: Int,
        val text: String,
        val score: Int,
    )

    /**
     * Approximate search across the whole downloaded version.
     * Scoring: exact phrase > all words present > some words > stem matches.
     */
    suspend fun search(versionId: String, query: String, limit: Int = 40): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return@withContext emptyList()
            val words = q.split(Regex("\\s+")).filter { it.length > 1 }
            val stems = words.map { stem(it) }.filter { it.length > 3 }

            val hits = ArrayList<SearchHit>()
            for (bookIndex in 0 until 66) {
                val book = try {
                    loadBook(versionId, bookIndex)
                } catch (e: Exception) {
                    continue
                }
                for (ch in book.chapters) {
                    for (v in ch.verses) {
                        val score = scoreVerse(v.text.lowercase(), q, words, stems)
                        if (score > 0) {
                            hits.add(SearchHit(bookIndex, ch.chapter, v.verse, v.text, score))
                        }
                    }
                }
            }
            hits.sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.bookIndex }
                    .thenBy { it.chapter }
            ).take(limit)
        }

    private fun scoreVerse(text: String, phrase: String, words: List<String>, stems: List<String>): Int {
        if (text.contains(phrase)) return 100
        if (words.isEmpty()) return 0
        val matched = words.count { text.contains(it) }
        if (matched == words.size) return 80
        if (matched > 0) return 40 + (40 * matched / words.size)
        val stemMatched = stems.count { text.contains(it) }
        if (stemMatched > 0) return 20 + (10 * stemMatched / stems.size)
        return 0
    }

    private fun stem(word: String): String =
        word.replace(Regex("(ing|tion|eth|est|ed|ness|ful|ous|ies|es|s)$"), "")

    // ── API.Bible (user's own key unlocks NIV, NKJV, etc. — fetched live) ──

    private val apiBibleCache = LinkedHashMap<String, Pair<List<VerseJson>, String>>()
    private val apiBibleCacheLimit = 6
    private val apiBibleBase = "https://api.scripture.api.bible/v1"

    /** Validates the key and returns the Bibles it has access to. */
    suspend fun apiBibleList(key: String): List<ApiBibleBible> =
        withContext(Dispatchers.IO) {
            val response = client.get("$apiBibleBase/bibles") {
                headers.append("api-key", key)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    if (response.status.value == 401 || response.status.value == 403) {
                        "Key rejected — check it and try again."
                    } else {
                        "API.Bible error ${response.status.value}"
                    }
                )
            }
            val parsed = json.decodeFromString<ApiBibleListResponse>(response.bodyAsText())
            // English versions first, then everything else alphabetically
            parsed.data.sortedWith(
                compareBy({ if (it.language.id == "eng") 0 else 1 }, { it.name })
            )
        }

    /** Returns the verses plus the publisher copyright line for the chapter. */
    suspend fun apiBibleChapter(
        key: String,
        bibleId: String,
        bookIndex: Int,
        chapter: Int,
    ): Pair<List<VerseJson>, String> =
        withContext(Dispatchers.IO) {
            val cacheKey = "$bibleId/$bookIndex/$chapter"
            apiBibleCache[cacheKey]?.let { return@withContext it }

            val book = BibleBooks.all[bookIndex]
            val response = client.get(
                "$apiBibleBase/bibles/$bibleId/chapters/${book.osis}.$chapter" +
                    "?content-type=text&include-verse-numbers=true" +
                    "&include-titles=false&include-chapter-numbers=false"
            ) {
                headers.append("api-key", key)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Fetch failed: HTTP ${response.status.value}")
            }
            val parsed = json.decodeFromString<ApiBibleChapterResponse>(response.bodyAsText())
            val raw = parsed.data.content
            if (raw.isBlank()) throw IllegalStateException("No passage returned")

            val verses = Regex("\\[(\\d+)\\]\\s*([\\s\\S]*?)(?=\\[\\d+\\]|$)")
                .findAll(raw)
                .map { m ->
                    VerseJson(
                        verse = m.groupValues[1].toInt(),
                        text = m.groupValues[2].replace(Regex("\\s+"), " ").trim(),
                    )
                }
                .filter { it.text.isNotEmpty() }
                .toList()
            if (verses.isEmpty()) throw IllegalStateException("Could not read passage")

            val result = Pair(verses, parsed.data.copyright.trim())
            if (apiBibleCache.size >= apiBibleCacheLimit) {
                apiBibleCache.remove(apiBibleCache.keys.first())
            }
            apiBibleCache[cacheKey] = result
            result
        }

    suspend fun apiBibleSearch(
        key: String,
        bibleId: String,
        query: String,
        limit: Int = 20,
    ): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val encoded = query.trim().replace(" ", "+")
            val response = client.get(
                "$apiBibleBase/bibles/$bibleId/search?query=$encoded&limit=$limit"
            ) {
                headers.append("api-key", key)
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Search failed: HTTP ${response.status.value}")
            }
            val parsed = json.decodeFromString<ApiBibleSearchResponse>(response.bodyAsText())
            parsed.data.verses.mapNotNull { v ->
                val ref = BibleBooks.parseReference(v.reference) ?: return@mapNotNull null
                val ch = ref.chapter ?: return@mapNotNull null
                SearchHit(
                    bookIndex = ref.book.index,
                    chapter = ch,
                    verse = ref.verse ?: 1,
                    text = v.text.trim(),
                    score = 100,
                )
            }
        }

    private fun versionDir(versionId: String) = File(biblesDir, versionId)

    // ── ESV (Crossway official API — fetched live, never stored to disk) ──

    private val esvCache = LinkedHashMap<String, List<VerseJson>>()
    private val esvCacheLimit = 6

    /** Quick key check: fetches one verse. Throws on a bad key or no network. */
    suspend fun validateEsvKey(key: String) {
        withContext(Dispatchers.IO) {
            val response = client.get(
                "https://api.esv.org/v3/passage/text/?q=John+3:16" +
                    "&include-verse-numbers=false&include-footnotes=false" +
                    "&include-headings=false&include-short-copyright=false"
            ) {
                headers.append("Authorization", "Token $key")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    if (response.status.value == 401 || response.status.value == 403) {
                        "Key rejected — check it and try again."
                    } else {
                        "ESV API error ${response.status.value}"
                    }
                )
            }
        }
    }

    suspend fun esvChapter(key: String, bookIndex: Int, chapter: Int): List<VerseJson> =
        withContext(Dispatchers.IO) {
            val cacheKey = "$bookIndex/$chapter"
            esvCache[cacheKey]?.let { return@withContext it }

            val book = BibleBooks.all[bookIndex]
            val query = "${book.name} $chapter".replace(" ", "+")
            val response = client.get(
                "https://api.esv.org/v3/passage/text/?q=$query" +
                    "&include-verse-numbers=true&include-footnotes=false" +
                    "&include-headings=false&include-short-copyright=false" +
                    "&include-passage-references=false&indent-poetry=false" +
                    "&indent-paragraphs=0"
            ) {
                headers.append("Authorization", "Token $key")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("ESV fetch failed: HTTP ${response.status.value}")
            }
            val parsed = json.decodeFromString<EsvTextResponse>(response.bodyAsText())
            val raw = parsed.passages.firstOrNull()
                ?: throw IllegalStateException("No passage returned")

            val verses = Regex("\\[(\\d+)\\]\\s*([\\s\\S]*?)(?=\\[\\d+\\]|$)")
                .findAll(raw)
                .map { m ->
                    VerseJson(
                        verse = m.groupValues[1].toInt(),
                        text = m.groupValues[2].replace(Regex("\\s+"), " ").trim(),
                    )
                }
                .filter { it.text.isNotEmpty() }
                .toList()
            if (verses.isEmpty()) throw IllegalStateException("Could not read passage")

            if (esvCache.size >= esvCacheLimit) esvCache.remove(esvCache.keys.first())
            esvCache[cacheKey] = verses
            verses
        }

    suspend fun esvSearch(key: String, query: String, limit: Int = 20): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val encoded = query.trim().replace(" ", "+")
            val response = client.get(
                "https://api.esv.org/v3/passage/search/?q=$encoded&page-size=$limit"
            ) {
                headers.append("Authorization", "Token $key")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("ESV search failed: HTTP ${response.status.value}")
            }
            val parsed = json.decodeFromString<EsvSearchResponse>(response.bodyAsText())
            parsed.results.mapNotNull { r ->
                val ref = BibleBooks.parseReference(r.reference) ?: return@mapNotNull null
                val ch = ref.chapter ?: return@mapNotNull null
                SearchHit(
                    bookIndex = ref.book.index,
                    chapter = ch,
                    verse = ref.verse ?: 1,
                    text = r.content,
                    score = 100,
                )
            }
        }
}

/**
 * App-wide singleton holder, initialized by the first screen that runs.
 * Same pattern as the audiobook app's PlaybackController.
 */
object Bible {
    @Volatile
    private var storeInstance: BibleStore? = null

    fun init(filesDir: File) {
        if (storeInstance == null) {
            synchronized(this) {
                if (storeInstance == null) storeInstance = BibleStore(filesDir)
            }
        }
    }

    val store: BibleStore
        get() = storeInstance ?: throw IllegalStateException("Bible.init() not called yet")
}
