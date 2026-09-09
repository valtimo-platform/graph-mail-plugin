/*
 * Copyright 2015-2022 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.graphmail

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Wraps a proxy selector with a bypass list, mirroring the semantics of the JVM's own
 * `http.nonProxyHosts`: pipe-separated patterns where `*` matches any run of characters, compared
 * case-insensitively against the host.
 *
 * [ProxySelector.of] has no notion of a bypass list, so a deployment that sets an explicit proxy
 * would otherwise send loopback and intranet traffic through it as well.
 */
internal class BypassingProxySelector(
    private val delegate: ProxySelector,
    nonProxyHosts: String,
) : ProxySelector() {
    private val patterns: List<Regex> =
        nonProxyHosts
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { pattern ->
                // Everything that is not the wildcard is escaped, so a dot stays a literal dot
                // rather than "any character" — otherwise `*.example.com` would also match
                // `axexample.com`, and a bypass list would quietly become wider than it reads.
                val escaped = pattern.split('*').joinToString(".*") { Regex.escape(it) }
                Regex("^$escaped$", RegexOption.IGNORE_CASE)
            }

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        if (host != null && patterns.any { it.matches(host) }) {
            return listOf(Proxy.NO_PROXY)
        }
        return delegate.select(uri)
    }

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        ioe: IOException?,
    ) {
        delegate.connectFailed(uri, sa, ioe)
    }
}
