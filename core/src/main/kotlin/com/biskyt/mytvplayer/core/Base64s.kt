package com.biskyt.mytvplayer.core

import kotlin.io.encoding.Base64

// Kotlin's Base64, as Android has Java's (java.util.Base64) only from 8.0 and desugaring does not add it. Both take
// text with or without its padding, as Java's decoders do.

/** Base64 as most of the web writes it. */
internal val PlainBase64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** Base64 for URLs (a JWT's parts). */
internal val UrlBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
