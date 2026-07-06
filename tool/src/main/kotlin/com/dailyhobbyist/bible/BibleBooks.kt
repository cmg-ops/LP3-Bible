package com.dailyhobbyist.bible

/**
 * Metadata for the 66 books, in canonical order.
 * The order matches the scrollmapper JSON source exactly,
 * so book index here == book index in the downloaded data.
 */
data class BookInfo(
    val index: Int,          // 0-based position in the canon / data files
    val name: String,        // display name
    val abbr: String,        // short abbreviation used in lookup
    val chapters: Int,       // chapter count
    val isOldTestament: Boolean,
    val osis: String,        // book code used by API.Bible (GEN, JHN, ...)
)

object BibleBooks {

    val all: List<BookInfo> = buildList {
        var i = 0
        fun ot(name: String, abbr: String, ch: Int, osis: String) =
            add(BookInfo(i++, name, abbr, ch, true, osis))
        fun nt(name: String, abbr: String, ch: Int, osis: String) =
            add(BookInfo(i++, name, abbr, ch, false, osis))

        ot("Genesis", "Gen", 50, "GEN")
        ot("Exodus", "Ex", 40, "EXO")
        ot("Leviticus", "Lev", 27, "LEV")
        ot("Numbers", "Num", 36, "NUM")
        ot("Deuteronomy", "Deut", 34, "DEU")
        ot("Joshua", "Josh", 24, "JOS")
        ot("Judges", "Judg", 21, "JDG")
        ot("Ruth", "Ruth", 4, "RUT")
        ot("1 Samuel", "1Sam", 31, "1SA")
        ot("2 Samuel", "2Sam", 24, "2SA")
        ot("1 Kings", "1Kgs", 22, "1KI")
        ot("2 Kings", "2Kgs", 25, "2KI")
        ot("1 Chronicles", "1Chr", 29, "1CH")
        ot("2 Chronicles", "2Chr", 36, "2CH")
        ot("Ezra", "Ezra", 10, "EZR")
        ot("Nehemiah", "Neh", 13, "NEH")
        ot("Esther", "Est", 10, "EST")
        ot("Job", "Job", 42, "JOB")
        ot("Psalms", "Ps", 150, "PSA")
        ot("Proverbs", "Prov", 31, "PRO")
        ot("Ecclesiastes", "Eccl", 12, "ECC")
        ot("Song of Solomon", "Song", 8, "SNG")
        ot("Isaiah", "Isa", 66, "ISA")
        ot("Jeremiah", "Jer", 52, "JER")
        ot("Lamentations", "Lam", 5, "LAM")
        ot("Ezekiel", "Ezek", 48, "EZK")
        ot("Daniel", "Dan", 12, "DAN")
        ot("Hosea", "Hos", 14, "HOS")
        ot("Joel", "Joel", 3, "JOL")
        ot("Amos", "Amos", 9, "AMO")
        ot("Obadiah", "Obad", 1, "OBA")
        ot("Jonah", "Jon", 4, "JON")
        ot("Micah", "Mic", 7, "MIC")
        ot("Nahum", "Nah", 3, "NAM")
        ot("Habakkuk", "Hab", 3, "HAB")
        ot("Zephaniah", "Zeph", 3, "ZEP")
        ot("Haggai", "Hag", 2, "HAG")
        ot("Zechariah", "Zech", 14, "ZEC")
        ot("Malachi", "Mal", 4, "MAL")
        nt("Matthew", "Matt", 28, "MAT")
        nt("Mark", "Mark", 16, "MRK")
        nt("Luke", "Luke", 24, "LUK")
        nt("John", "John", 21, "JHN")
        nt("Acts", "Acts", 28, "ACT")
        nt("Romans", "Rom", 16, "ROM")
        nt("1 Corinthians", "1Cor", 16, "1CO")
        nt("2 Corinthians", "2Cor", 13, "2CO")
        nt("Galatians", "Gal", 6, "GAL")
        nt("Ephesians", "Eph", 6, "EPH")
        nt("Philippians", "Phil", 4, "PHP")
        nt("Colossians", "Col", 4, "COL")
        nt("1 Thessalonians", "1Thes", 5, "1TH")
        nt("2 Thessalonians", "2Thes", 3, "2TH")
        nt("1 Timothy", "1Tim", 6, "1TI")
        nt("2 Timothy", "2Tim", 4, "2TI")
        nt("Titus", "Titus", 3, "TIT")
        nt("Philemon", "Phlm", 1, "PHM")
        nt("Hebrews", "Heb", 13, "HEB")
        nt("James", "Jas", 5, "JAS")
        nt("1 Peter", "1Pet", 5, "1PE")
        nt("2 Peter", "2Pet", 3, "2PE")
        nt("1 John", "1Jn", 5, "1JN")
        nt("2 John", "2Jn", 1, "2JN")
        nt("3 John", "3Jn", 1, "3JN")
        nt("Jude", "Jude", 1, "JUD")
        nt("Revelation", "Rev", 22, "REV")
    }

    val oldTestament: List<BookInfo> = all.filter { it.isOldTestament }
    val newTestament: List<BookInfo> = all.filter { !it.isOldTestament }

    /** Lookup map for reference parsing: lowercase name/abbr/aliases -> BookInfo */
    private val refMap: Map<String, BookInfo> = buildMap {
        all.forEach { b ->
            put(b.name.lowercase(), b)
            put(b.abbr.lowercase(), b)
            put(b.name.lowercase().replace(" ", ""), b)
        }
        // Common aliases
        all.first { it.name == "Psalms" }.let { put("psalm", it) }
        all.first { it.name == "Song of Solomon" }.let {
            put("song of songs", it); put("songofsongs", it); put("sos", it)
        }
        all.first { it.name == "John" }.let { put("jn", it) }
        all.first { it.name == "Matthew" }.let { put("mt", it) }
        all.first { it.name == "Mark" }.let { put("mk", it) }
        all.first { it.name == "Luke" }.let { put("lk", it) }
        all.first { it.name == "Revelation" }.let { put("rev", it); put("revelations", it) }
        all.first { it.name == "Genesis" }.let { put("gn", it) }
        all.first { it.name == "Philippians" }.let { put("php", it) }
    }

    /**
     * Parse a reference like "John 3:16", "Ps 23", "1 Cor 13:4", or just "Genesis".
     * Returns null when the text doesn't look like a reference.
     */
    fun parseReference(input: String): ParsedReference? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val match = Regex(
            "^([1-3]?\\s*[A-Za-z]+(?:\\s+[A-Za-z]+){0,2})\\s*(\\d+)?(?:\\s*:\\s*(\\d+))?$"
        ).find(trimmed) ?: return null

        val bookKey = match.groupValues[1].trim().lowercase().replace(Regex("\\s+"), " ")
        val book = refMap[bookKey] ?: refMap[bookKey.replace(" ", "")] ?: return null
        val chapter = match.groupValues[2].toIntOrNull()
        val verse = match.groupValues[3].toIntOrNull()

        if (chapter != null && (chapter < 1 || chapter > book.chapters)) return null
        return ParsedReference(book, chapter, verse)
    }
}

data class ParsedReference(
    val book: BookInfo,
    val chapter: Int?,   // null = book only
    val verse: Int?,     // null = chapter only
)
