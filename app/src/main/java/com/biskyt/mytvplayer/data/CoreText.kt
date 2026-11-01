package com.biskyt.mytvplayer.data

import androidx.annotation.StringRes
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.epg.CategoryLanguageDetector
import com.biskyt.mytvplayer.core.xtream.XtreamException
import com.biskyt.mytvplayer.core.xtream.XtreamException.Reason
import com.biskyt.mytvplayer.data.db.CategoryCount
import com.biskyt.mytvplayer.data.db.CategoryEntity
import java.io.IOException
import java.util.Locale

// The app's words for what the core module reports as codes (core has no Android resources).

/** What [e] says to the user: an Xtream failure in the app's language, anything else its own message. */
fun userMessage(e: Throwable): String? = if (e is XtreamException) e.userMessage() else e.message

/** This failure in the app's language ([XtreamException.message] is the English, for logs). */
fun XtreamException.userMessage(): String = when (reason) {
    Reason.LOGIN_FAILED -> appString(R.string.data_xtream_login_failed)
    Reason.NO_MOVIE_DETAILS -> appString(R.string.data_xtream_no_movie_details)
    Reason.NO_EPISODES -> appString(R.string.data_xtream_no_episodes)
    Reason.UNREACHABLE -> appString(R.string.data_xtream_unreachable, cause?.message.toString())
    Reason.SERVER_ERROR -> appString(R.string.data_server_error, code)
    Reason.EMPTY_RESPONSE -> appString(R.string.data_xtream_empty)
    Reason.UNEXPECTED_RESPONSE -> appString(R.string.data_xtream_unexpected)
    Reason.INVALID_ADDRESS -> appString(R.string.data_xtream_invalid_address)
    Reason.OTHER -> message.orEmpty()
}

/**
 * Runs [block] (a call to a provider's server), so that an Xtream failure's message is in the app's language for
 * whoever shows it; the English one stays with its cause.
 */
internal inline fun <T> withUserXtreamErrors(block: () -> T): T = try {
    block()
} catch (e: XtreamException) {
    throw XtreamException(e.userMessage(), e)
}

/** The heading for a language/country group (as CategoryLanguageDetector gives them, null for none). */
fun groupLabel(code: String?): String = when {
    code == null -> appString(R.string.data_lang_other)
    code in groupLabels -> appString(groupLabels.getValue(code))
    code.length == 2 -> Locale("", code).getDisplayCountry(AppText.locale).ifEmpty { code }
    else -> code
}

/** The groups CategoryLanguageDetector.namedGroups names, by code. */
internal val groupLabels: Map<String, Int> = mapOf(
    "UK" to R.string.data_lang_uk, "US" to R.string.data_lang_us, "CA" to R.string.data_lang_ca,
    "AU" to R.string.data_lang_au, "IE" to R.string.data_lang_ie, "FR" to R.string.data_lang_fr,
    "DE" to R.string.data_lang_de, "ES" to R.string.data_lang_es, "IT" to R.string.data_lang_it,
    "PT" to R.string.data_lang_pt, "BR" to R.string.data_lang_br, "NL" to R.string.data_lang_nl,
    "BE" to R.string.data_lang_be, "PL" to R.string.data_lang_pl, "TR" to R.string.data_lang_tr,
    "AR" to R.string.data_lang_ar, "IN" to R.string.data_lang_in, "PK" to R.string.data_lang_pk,
    "GR" to R.string.data_lang_gr, "RO" to R.string.data_lang_ro, "AL" to R.string.data_lang_al,
    "EXYU" to R.string.data_lang_exyu, "SE" to R.string.data_lang_se, "NO" to R.string.data_lang_no,
    "DK" to R.string.data_lang_dk, "FI" to R.string.data_lang_fi, "RU" to R.string.data_lang_ru,
    "LAT" to R.string.data_lang_lat, "MX" to R.string.data_lang_mx, "AF" to R.string.data_lang_af,
    "CN" to R.string.data_lang_cn, "KR" to R.string.data_lang_kr, "JP" to R.string.data_lang_jp,
    CategoryLanguageDetector.GLOBAL to R.string.data_lang_global, CategoryLanguageDetector.NORDIC to R.string.data_lang_nordic,
    CategoryLanguageDetector.ADULT to R.string.data_lang_adult,
)

/** The name of an accent colour offered in Settings (an AccentPalette.Preset id). */
@StringRes
fun accentName(id: String): Int = accentNames.getValue(id)

private val accentNames = mapOf(
    "mint" to R.string.data_accent_mint,
    "amber" to R.string.data_accent_amber,
    "violet" to R.string.data_accent_violet,
    "sky" to R.string.data_accent_sky,
    "coral" to R.string.data_accent_coral,
    "lime" to R.string.data_accent_lime,
    "pink" to R.string.data_accent_pink,
    "silver" to R.string.data_accent_silver,
)

/** A programme's title, or the app's "No title" (in its language) for one its guide gave none. */
fun programmeTitle(title: String): String = title.ifBlank { appString(R.string.common_no_title) }

/** A category's name as the playlist gives it, or the app's "No category" (in its language) for entries in none. */
fun categoryLabel(name: String): String = name.ifEmpty { appString(R.string.data_ungrouped) }

val CategoryEntity.label: String get() = categoryLabel(name)

val CategoryCount.label: String get() = categoryLabel(name)

/**
 * A guide failing, in the app's own words: the server refusing it ([status]), or the download not being saved (null).
 * Kept as a guide's lastError, it's saved as what failed rather than how it's put ([saved]), so it reads in the language
 * of the moment when shown again ([text]). Other errors (a server's, a library's) are kept as they come.
 */
class GuideError private constructor(private val status: Int?) : IOException(words(status)) {
    companion object {
        // No server's or library's message starts with it.
        private const val TAG = "\u001F"

        fun server(status: Int) = GuideError(status)

        fun notSaved() = GuideError(null)

        /** What to keep as a guide's lastError for [e]. */
        fun saved(e: Throwable): String = if (e is GuideError) TAG + e.status?.toString().orEmpty() else describe(e)

        /** A kept lastError, as words: the app's in its language, anything else as it was. */
        fun text(saved: String): String = if (saved.startsWith(TAG)) words(saved.removePrefix(TAG).toIntOrNull()) else saved

        private fun words(status: Int?) =
            status?.let { appString(R.string.data_server_error, it) } ?: appString(R.string.data_guide_save_failed)
    }
}

/** What [e] says to the user (see userMessage), or its kind when it says nothing. */
fun describe(e: Throwable): String = userMessage(e) ?: e.javaClass.simpleName
