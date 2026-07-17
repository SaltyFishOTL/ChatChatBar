package com.example.chatbar.domain

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

object ProxyAwareClient {

    @Volatile
    var manualProxyHost: String? = null

    @Volatile
    var manualProxyPort: Int? = null

    fun builder(): OkHttpClient.Builder {
        return builderWithCleartextPolicy { false }
    }

    fun modelApiBuilder(allowCleartextHttp: () -> Boolean): OkHttpClient.Builder {
        return builderWithCleartextPolicy(allowCleartextHttp)
    }

    private fun builderWithCleartextPolicy(
        allowCleartextHttp: () -> Boolean
    ): OkHttpClient.Builder {
        val selector = ProxySelectorDelegate()
        return OkHttpClient.Builder()
            .proxySelector(selector)
            .dns(Ipv4OnlyDns)
            .addInterceptor { chain ->
                val request = chain.request()
                if (!CleartextHttpPolicy.isAllowed(request.url.isHttps, allowCleartextHttp())) {
                    throw IOException("明文 HTTP 模型 API 已禁用，请在管理 > 设置中开启后重试")
                }
                chain.proceed(request)
            }
    }

    private object Ipv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return Dns.SYSTEM.lookup(hostname)
                .filterIsInstance<Inet4Address>()
                .takeIf { it.isNotEmpty() }
                ?: Dns.SYSTEM.lookup(hostname)
        }
    }

    private class ProxySelectorDelegate : ProxySelector() {

        override fun select(uri: URI?): List<Proxy> {
            val host = manualProxyHost
            val port = manualProxyPort
            if (!host.isNullOrBlank() && port != null && port > 0 && port <= 65535) {
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)))
            }

            val httpHost = System.getProperty("http.proxyHost")
            val httpPort = System.getProperty("http.proxyPort")?.toIntOrNull()
            if (!httpHost.isNullOrBlank() && httpPort != null && httpPort > 0 && httpPort <= 65535) {
                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(httpHost, httpPort)))
            }

            val socksHost = System.getProperty("socksProxyHost")
            val socksPort = System.getProperty("socksProxyPort")?.toIntOrNull()
            if (!socksHost.isNullOrBlank() && socksPort != null && socksPort > 0 && socksPort <= 65535) {
                return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
            }

            return try {
                ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
            } catch (_: Exception) {
                listOf(Proxy.NO_PROXY)
            }
        }

        override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
        }
    }
}

internal object CleartextHttpPolicy {
    fun isAllowed(isHttps: Boolean, allowCleartextHttp: Boolean): Boolean =
        isHttps || allowCleartextHttp
}

internal fun Request.Builder.addModelApiAuthorization(apiKey: String): Request.Builder = apply {
    apiKey.trim()
        .takeIf(String::isNotEmpty)
        ?.let { addHeader("Authorization", "Bearer $it") }
}
