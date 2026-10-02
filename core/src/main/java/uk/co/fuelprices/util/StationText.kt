package uk.co.fuelprices.util

/**
 * Display-casing for Gov Fuel Finder text, matching fuel-web's `lib/stationText.ts`.
 *
 * The feed has no casing convention: most names are ALL CAPS, a few all lowercase, the rest
 * sensibly cased. Only a string with no case information of its own (entirely upper- or
 * lowercase) is restyled; anything already mixed ("BP Yarnton") was cased deliberately upstream
 * and is passed through untouched.
 */
object StationText {
    /** Initialisms that stay uppercase. Brands written as words (Asda, Esso, Tesco) are absent. */
    private val UPPERCASE_TOKENS = setOf(
        "BP", "EG", "MFG", "MRH", "MWSA", "NTS", "PFS", "PGG", "SF", "TGC", "UK", "JET",
        "LPG", "HGV", "MOT", "EV", "ATM", "WC", "DIY", "AM", "PM", "II", "III",
    )

    /** Ordinals are words: "1ST AVENUE" is "1st Avenue". */
    private val ORDINAL = Regex("^\\d+(?:ST|ND|RD|TH)$")

    /** Road numbers (A34, M25) and postcode halves (OX33, 1RT) stay uppercase. */
    private val ALWAYS_UPPER = Regex("^(?:[A-Z]{1,2}\\d{1,4}[A-Z]?|\\d[A-Z]{2})$")

    private val HOUSE_NUMBER_SUFFIX = Regex("^\\d+[A-Z]$")

    /** Fragments that stay lowercase after a hyphen: "CO-OP" is "Co-op". */
    private val LOWER_AFTER_HYPHEN = setOf("op", "operative", "op's", "operatives")

    /** Lowercase inside a title, unless first or last. */
    private val MINOR_WORDS = setOf(
        "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "nor", "of",
        "on", "or", "the", "to", "via", "with",
    )

    /** Word separators, kept as they are: trade names run words together with dots and "&". */
    private val SEPARATORS = Regex("[\\s\\-/.&,()\\[\\]]+")

    private val O_PREFIX = Regex("^o'[a-z]", RegexOption.IGNORE_CASE)
    private val MC_PREFIX = Regex("^mc[a-z]{2,}$", RegexOption.IGNORE_CASE)

    /** A station or place name as it should be displayed; also collapses runs of whitespace. */
    fun displayName(value: String?): String {
        val trimmed = (value ?: "").replace(Regex("\\s+"), " ").trim()
        if (trimmed.isEmpty()) return ""
        return if (hasNoCaseIntent(trimmed)) toTitleCase(trimmed) else trimmed
    }

    private fun hasNoCaseIntent(value: String): Boolean {
        if (value.none { it.isLetter() }) return false
        return value == value.uppercase() || value == value.lowercase()
    }

    private fun toTitleCase(input: String): String {
        // Alternating word/separator parts, so separators can be put back unchanged.
        val parts = mutableListOf<Pair<String, Boolean>>() // text, isSeparator
        var last = 0
        for (match in SEPARATORS.findAll(input)) {
            if (match.range.first > last) parts += input.substring(last, match.range.first) to false
            parts += match.value to true
            last = match.range.last + 1
        }
        if (last < input.length) parts += input.substring(last) to false

        val wordIndices = parts.indices.filter { !parts[it].second }
        val first = wordIndices.firstOrNull()
        val lastWord = wordIndices.lastOrNull()
        return parts.mapIndexed { i, (text, isSeparator) ->
            if (isSeparator) {
                text
            } else {
                val afterHyphen = i > 0 && parts[i - 1].second && parts[i - 1].first.contains('-')
                restyleWord(text, isFirst = i == first, isLast = i == lastWord, afterHyphen = afterHyphen)
            }
        }.joinToString("")
    }

    private fun restyleWord(word: String, isFirst: Boolean, isLast: Boolean, afterHyphen: Boolean): String {
        val upper = word.uppercase()
        // Before ALWAYS_UPPER, which would otherwise claim "1ST" and "2ND" as postcode halves.
        if (ORDINAL.matches(upper)) return upper.lowercase()
        if (HOUSE_NUMBER_SUFFIX.matches(upper)) return upper
        if (upper in UPPERCASE_TOKENS || ALWAYS_UPPER.matches(upper)) return upper

        val lower = word.lowercase()
        if (afterHyphen && lower in LOWER_AFTER_HYPHEN) return lower
        if (!isFirst && !isLast && lower in MINOR_WORDS) return lower

        // "O'BRIEN" -> "O'Brien"; a possessive 's ("TOUT'S") never takes a capital.
        if (O_PREFIX.containsMatchIn(lower)) return "O'" + lower[2].uppercaseChar() + lower.substring(3)
        // "MCDONALDS" -> "McDonalds"; left alone below 4 letters so "MCS" doesn't become "McS".
        if (MC_PREFIX.matches(lower)) return "Mc" + lower[2].uppercaseChar() + lower.substring(3)

        return lower.replaceFirstChar { it.uppercaseChar() }
    }
}
