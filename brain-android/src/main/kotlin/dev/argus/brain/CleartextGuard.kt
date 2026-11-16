package dev.argus.brain

import dev.argus.engine.model.LocalNetworkPolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.UnknownServiceException

/**
 * App-level gate for plain `http://` (issue #19). Android's network security config cannot list IP ranges,
 * so the platform now permits cleartext and every OkHttp client of the app carries one of these guards
 * instead: a request to an `http://` host that [allows] refuses fails before a byte leaves the phone, with
 * the same exception the platform threw before ([UnknownServiceException]).
 *
 * Installed both as an application interceptor (the request is refused before connecting) and as a network
 * interceptor (every redirect hop is checked again, should a client ever follow redirects).
 */
class CleartextGuard(private val allows: (host: String) -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !allows(url.host)) {
            // The host is not secret but stays out of the message anyway: errors can end up in reports.
            throw UnknownServiceException("CLEARTEXT communication not permitted by Argus policy")
        }
        return chain.proceed(chain.request())
    }

    companion object {
        /** What the platform allowed before #19, and still the rule of every client but the Custom provider. */
        val LOOPBACK_ONLY = CleartextGuard { host -> LOOPBACK_HTTP_HOSTS.contains(host) }

        /** The Custom provider: this phone or the user's own network ([LocalNetworkPolicy]). */
        val LOCAL_NETWORK = CleartextGuard(LocalNetworkPolicy::allowsLlmCleartext)

        /** [builder] with [guard] as its only cleartext guard, at both interceptor levels. */
        fun install(builder: OkHttpClient.Builder, guard: CleartextGuard): OkHttpClient.Builder = builder.apply {
            interceptors().removeAll { it is CleartextGuard }
            networkInterceptors().removeAll { it is CleartextGuard }
            addInterceptor(guard)
            addNetworkInterceptor(guard)
        }

        /** The guard [client] carries at application level, or null. For tests and checks. */
        fun of(client: OkHttpClient): CleartextGuard? = client.interceptors.filterIsInstance<CleartextGuard>().singleOrNull()
    }

    /** True if this guard lets `http://` through to [host]. */
    fun permits(host: String): Boolean = allows(host)
}
