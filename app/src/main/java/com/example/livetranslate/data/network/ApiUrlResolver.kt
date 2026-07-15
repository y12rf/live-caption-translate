package com.example.livetranslate.data.network

/**
 * Resolves user-entered API URLs without per-vendor hardcoding.
 *
 * Rules (as product requires):
 * 1. If the URL already has a real path **after** `/v1/`
 *    (e.g. `https://host/v1/chat/completions` or `.../v1/audio/transcriptions`),
 *    use it as-is (only trim whitespace / trailing `/`).
 * 2. Otherwise treat input as a **base** (`https://host` or `https://host/v1`)
 *    and append the OpenAI-style default path
 *    (e.g. `/v1/audio/transcriptions` or `/v1/chat/completions`).
 */
object ApiUrlResolver {

    private val pathAfterV1 = Regex("""(?i)/v1/([^?#]+)$""")

    /**
     * @param userUrl whatever the user typed in settings
     * @param openAiStylePath default path starting with `/`, e.g. `/v1/chat/completions`
     * @param fullUrl when true, never append paths — use [userUrl] as the final endpoint
     */
    fun resolve(
        userUrl: String,
        openAiStylePath: String,
        fullUrl: Boolean = false
    ): String {
        val trimmed = userUrl.trim()
        require(trimmed.isNotEmpty()) { "API URL is empty" }

        val url = trimmed.trimEnd('/')
        if (fullUrl) {
            return url
        }

        val path = if (openAiStylePath.startsWith("/")) openAiStylePath else "/$openAiStylePath"

        // Complete endpoint: .../v1/<something>
        val m = pathAfterV1.find(url)
        if (m != null && m.groupValues[1].isNotBlank()) {
            return url
        }

        // Base only: https://host  or  https://host/v1
        val root = url.removeSuffix("/v1").trimEnd('/')
        return root + path
    }

    /** True if user already provided a full endpoint path after /v1/. */
    fun isFullEndpoint(userUrl: String): Boolean {
        val url = userUrl.trim().trimEnd('/')
        val m = pathAfterV1.find(url) ?: return false
        return m.groupValues[1].isNotBlank()
    }
}
