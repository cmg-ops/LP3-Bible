package com.dailyhobbyist.bible

import kotlinx.serialization.Serializable

/** A downloadable translation. All are public domain or freely licensed. */
data class VersionInfo(
    val id: String,          // short id, also the folder name on disk
    val name: String,        // full display name
    val year: String,
    val description: String,
    val url: String,         // whole-bible JSON download URL
)

object BibleVersions {

    private const val BASE =
        "https://raw.githubusercontent.com/scrollmapper/bible_databases/master/formats/json"

    val catalog: List<VersionInfo> = listOf(
        VersionInfo(
            id = "KJV",
            name = "King James Version",
            year = "1769",
            description = "The standard English Bible",
            url = "$BASE/KJV.json",
        ),
        VersionInfo(
            id = "AKJV",
            name = "American King James Version",
            year = "1999",
            description = "KJV with modernized spelling",
            url = "$BASE/AKJV.json",
        ),
        VersionInfo(
            id = "ASV",
            name = "American Standard Version",
            year = "1901",
            description = "Careful revision of the KJV",
            url = "$BASE/ASV.json",
        ),
        VersionInfo(
            id = "BSB",
            name = "Berean Standard Bible",
            year = "2022",
            description = "Modern, accurate, and readable",
            url = "$BASE/BSB.json",
        ),
        VersionInfo(
            id = "YLT",
            name = "Young's Literal Translation",
            year = "1898",
            description = "Strictly word-for-word",
            url = "$BASE/YLT.json",
        ),
        VersionInfo(
            id = "Darby",
            name = "Darby Translation",
            year = "1890",
            description = "J. N. Darby's translation",
            url = "$BASE/Darby.json",
        ),
        VersionInfo(
            id = "BBE",
            name = "Bible in Basic English",
            year = "1949",
            description = "Simple vocabulary, easy reading",
            url = "$BASE/BBE.json",
        ),
        VersionInfo(
            id = "Webster",
            name = "Webster Bible",
            year = "1833",
            description = "Noah Webster's revision of the KJV",
            url = "$BASE/Webster.json",
        ),
    )

    val default: VersionInfo = catalog.first() // KJV

    /**
     * ESV is special: Crossway's license doesn't allow storing the whole
     * translation on the device, so it is fetched chapter-by-chapter from
     * their official free API (api.esv.org) and needs an API key.
     */
    const val ESV_ID = "ESV"

    val esv = VersionInfo(
        id = ESV_ID,
        name = "English Standard Version",
        year = "2001",
        description = "Crossway · online, free key required",
        url = "", // fetched live, never bulk-downloaded
    )

    fun byId(id: String): VersionInfo? =
        if (id == ESV_ID) esv else catalog.firstOrNull { it.id == id }

    fun isOnlineOnly(id: String): Boolean = id == ESV_ID || isApiBible(id)

    /**
     * API.Bible versions (NIV, NKJV, etc. unlocked by the user's own key)
     * are stored with an "AB:" prefix so they can't collide with catalog ids.
     */
    const val APIBIBLE_PREFIX = "AB:"

    fun isApiBible(id: String): Boolean = id.startsWith(APIBIBLE_PREFIX)

    fun apiBibleId(selectedId: String): String = selectedId.removePrefix(APIBIBLE_PREFIX)
}

// ── JSON models matching the scrollmapper file format ──

@Serializable
data class WholeBibleJson(
    val translation: String = "",
    val books: List<BookJson> = emptyList(),
)

@Serializable
data class BookJson(
    val name: String = "",
    val chapters: List<ChapterJson> = emptyList(),
)

@Serializable
data class ChapterJson(
    val chapter: Int = 0,
    val verses: List<VerseJson> = emptyList(),
)

@Serializable
data class VerseJson(
    val verse: Int = 0,
    val text: String = "",
)

// ── ESV API response models ──

@Serializable
data class EsvTextResponse(
    val passages: List<String> = emptyList(),
)

@Serializable
data class EsvSearchResponse(
    val results: List<EsvSearchResult> = emptyList(),
)

@Serializable
data class EsvSearchResult(
    val reference: String = "",
    val content: String = "",
)

// ── API.Bible response models ──

@Serializable
data class ApiBibleListResponse(
    val data: List<ApiBibleBible> = emptyList(),
)

@Serializable
data class ApiBibleBible(
    val id: String = "",
    val name: String = "",
    val abbreviation: String = "",
    val abbreviationLocal: String = "",
    val language: ApiBibleLanguage = ApiBibleLanguage(),
)

@Serializable
data class ApiBibleLanguage(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class ApiBibleChapterResponse(
    val data: ApiBibleChapterData = ApiBibleChapterData(),
)

@Serializable
data class ApiBibleChapterData(
    val content: String = "",
    val copyright: String = "",
)

@Serializable
data class ApiBibleSearchResponse(
    val data: ApiBibleSearchData = ApiBibleSearchData(),
)

@Serializable
data class ApiBibleSearchData(
    val verses: List<ApiBibleSearchVerse> = emptyList(),
)

@Serializable
data class ApiBibleSearchVerse(
    val reference: String = "",
    val text: String = "",
)

/** A user-enabled API.Bible version, persisted as JSON in DataStore. */
@Serializable
data class SavedApiBibleVersion(
    val id: String = "",       // raw API.Bible id, e.g. "de4e12af7f28f599-02"
    val name: String = "",
    val abbr: String = "",
)
