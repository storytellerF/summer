package com.storytellerf.summer

import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetSocketAddress
import java.net.Proxy

/** Optional ADB reverse CONNECT tunnel; TLS and certificate verification remain end to end. */
internal fun liveRecognitionTransport(): HttpClient {
    val port = InstrumentationRegistry.getArguments().getString("recognition_proxy_port")?.toInt()
    require(port == null || port in 1..65535)
    return HttpClient(OkHttp) {
        if (port != null) engine {
            config { proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) }
        }
    }
}
