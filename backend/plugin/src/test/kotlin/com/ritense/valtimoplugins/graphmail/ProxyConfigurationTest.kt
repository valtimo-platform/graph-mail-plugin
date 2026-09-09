package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The JDK's HttpClient installs no ProxySelector unless one is passed in — not even when
 * `https.proxyHost` is set. An earlier refactor moved the plugin from RestTemplate (which went
 * through HttpURLConnection and did honour those properties) to the JDK client without carrying
 * the proxy over, and every send behind a forward proxy then failed at connect. These tests pin
 * that behaviour down so the same regression cannot return unnoticed.
 */
class ProxyConfigurationTest {
    private val graphUri = URI.create("https://graph.microsoft.com/v1.0/users/x/sendMail")

    // Goes through the real HttpClient the bean builds, so the assertions cover the wiring and not
    // just the selector logic in isolation.
    private fun selectorFor(properties: GraphMailHttpProperties): ProxySelector {
        val client = GraphMailAutoConfiguration().graphHttpClient(properties)
        return client.proxy().orElseThrow {
            AssertionError(
                "The HTTP client was built without a ProxySelector. Every send behind a forward " +
                    "proxy fails at connect when this happens.",
            )
        }
    }

    @Test
    fun `an explicitly configured proxy is used for Graph traffic`() {
        val selected =
            selectorFor(
                GraphMailHttpProperties(
                    proxyHost = "proxy.intern.example.nl",
                    proxyPort = 8080,
                ),
            ).select(graphUri).single()

        assertEquals(Proxy.Type.HTTP, selected.type())
        val address = selected.address() as InetSocketAddress
        assertEquals("proxy.intern.example.nl", address.hostString)
        assertEquals(8080, address.port)
    }

    @Test
    fun `hosts on the bypass list skip the proxy`() {
        val selector =
            selectorFor(
                GraphMailHttpProperties(
                    proxyHost = "proxy.intern.example.nl",
                    proxyPort = 8080,
                    nonProxyHosts = "localhost|*.intern.example.nl",
                ),
            )

        assertEquals(
            Proxy.NO_PROXY,
            selector.select(URI.create("https://localhost:8080/x")).single(),
        )
        assertEquals(
            Proxy.NO_PROXY,
            selector.select(URI.create("https://mail.intern.example.nl/x")).single(),
        )
        // Graph itself still goes through the proxy.
        assertEquals(Proxy.Type.HTTP, selector.select(graphUri).single().type())
    }

    @Test
    fun `the bypass wildcard does not leak across a dot boundary`() {
        val selector =
            selectorFor(
                GraphMailHttpProperties(
                    proxyHost = "proxy.intern.example.nl",
                    proxyPort = 8080,
                    nonProxyHosts = "*.intern.example.nl",
                ),
            )

        // Without escaping the literal parts, the dot in the pattern would match any character and
        // an attacker-controlled host could opt itself out of the proxy.
        assertNotEquals(
            Proxy.NO_PROXY,
            selector.select(URI.create("https://axintern.example.nl/x")).single(),
        )
    }

    @Test
    fun `without configuration the JVM proxy settings are honoured`() {
        val previousHost = System.getProperty("https.proxyHost")
        val previousPort = System.getProperty("https.proxyPort")
        try {
            System.setProperty("https.proxyHost", "jvm-proxy.example.nl")
            System.setProperty("https.proxyPort", "3128")

            val selected = selectorFor(GraphMailHttpProperties()).select(graphUri).single()

            assertEquals(Proxy.Type.HTTP, selected.type())
            assertEquals(
                "jvm-proxy.example.nl",
                (selected.address() as InetSocketAddress).hostString,
            )
        } finally {
            restore("https.proxyHost", previousHost)
            restore("https.proxyPort", previousPort)
        }
    }

    @Test
    fun `a proxy host without a port is rejected`() {
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(proxyHost = "proxy.intern.example.nl")
        }
    }

    @Test
    fun `a proxy port outside the valid range is rejected`() {
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(proxyHost = "proxy.intern.example.nl", proxyPort = 70000)
        }
    }

    @Test
    fun `a proxy host with a scheme is rejected`() {
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(proxyHost = "http://proxy.intern.example.nl", proxyPort = 8080)
        }
    }

    @Test
    fun `a proxy host with the port glued on is rejected`() {
        // Clears the scheme and path checks, then produces an InetSocketAddress whose hostname
        // contains a colon — a DNS failure at connect time instead of a readable startup error.
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(proxyHost = "proxy.intern.example.nl:8080", proxyPort = 8080)
        }
    }

    @Test
    fun `a comma-separated bypass list is rejected instead of silently matching nothing`() {
        // http.nonProxyHosts is pipe-separated. A comma ends up inside the pattern, so the entry
        // never matches and intranet traffic goes through the proxy while the configuration reads
        // as though it does not.
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(
                proxyHost = "proxy.intern.example.nl",
                proxyPort = 8080,
                nonProxyHosts = "localhost,*.intern.example.nl",
            )
        }
    }

    @Test
    fun `a bypass list without a configured proxy is rejected instead of ignored`() {
        assertThrows<IllegalArgumentException> {
            GraphMailHttpProperties(nonProxyHosts = "localhost")
        }
    }

    @Test
    fun `whitespace around bypass entries is still accepted`() {
        // BypassingProxySelector trims each entry, so this has always worked; only whitespace
        // *inside* an entry is a mistake.
        val selector =
            selectorFor(
                GraphMailHttpProperties(
                    proxyHost = "proxy.intern.example.nl",
                    proxyPort = 8080,
                    nonProxyHosts = "localhost | *.intern.example.nl",
                ),
            )

        assertEquals(
            Proxy.NO_PROXY,
            selector.select(URI.create("https://mail.intern.example.nl/x")).single(),
        )
    }

    @Test
    fun `the client still builds when the JVM has no default proxy selector at all`() {
        // ProxySelector.setDefault(null) is a public API and this plugin runs inside somebody
        // else's application. Passing the resulting null to HttpClient.Builder.proxy() throws,
        // which would fail bean creation and stop the whole application from starting.
        val previous = ProxySelector.getDefault()
        try {
            ProxySelector.setDefault(null)

            val client = GraphMailAutoConfiguration().graphHttpClient(GraphMailHttpProperties())

            assertTrue(client.proxy().isPresent, "the client was built without a ProxySelector")
        } finally {
            ProxySelector.setDefault(previous)
        }
    }

    private fun restore(
        key: String,
        previous: String?,
    ) {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}
