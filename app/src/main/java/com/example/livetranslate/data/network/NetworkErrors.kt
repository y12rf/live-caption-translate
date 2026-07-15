package com.example.livetranslate.data.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Classifies transport / HTTP failures for retry policy and user-facing copy.
 */
object NetworkErrors {

    fun isRetryableThrowable(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            when (cur) {
                is CancellationException -> return false
                is SocketTimeoutException -> return true
                is UnknownHostException -> return true
                is ConnectException -> return true
                is SSLException -> return true
                is IOException -> {
                    val m = cur.message.orEmpty().lowercase()
                    if (m.contains("timeout") ||
                        m.contains("connection") ||
                        m.contains("reset") ||
                        m.contains("unreachable") ||
                        m.contains("failed to connect")
                    ) {
                        return true
                    }
                }
            }
            cur = cur.cause
        }
        return true // unknown IO → retry (auth errors come as non-retryable HTTP)
    }

    fun userMessage(t: Throwable, prefix: String = ""): String {
        val core = when (t) {
            is SocketTimeoutException -> "网络超时，请检查弱网或稍后重试"
            is UnknownHostException -> "无法解析服务器地址（可能离线）"
            is ConnectException -> "无法连接服务器"
            else -> t.message?.take(200) ?: t.javaClass.simpleName
        }
        return if (prefix.isBlank()) core else "$prefix: $core"
    }

    fun isRetryableHttp(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599
}
