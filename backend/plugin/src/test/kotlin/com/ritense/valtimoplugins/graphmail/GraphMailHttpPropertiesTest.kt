package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphMailHttpPropertiesTest {
    @Test fun `defaults are the Microsoft commercial cloud endpoints`() {
        val properties = GraphMailHttpProperties()
        assertEquals("https://login.microsoftonline.com", properties.tokenBaseUrl)
        assertEquals("https://graph.microsoft.com", properties.graphBaseUrl)
        assertTrue(properties.isProductionGraphEndpoint())
    }

    @Test fun `sovereign cloud endpoints are accepted`() {
        val properties =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.microsoftonline.us",
                graphBaseUrl = "https://graph.microsoft.us",
            )
        assertTrue(properties.isProductionGraphEndpoint())
    }

    @Test fun `a foreign token endpoint is rejected`() {
        // This is the exfiltration path the allowlist exists for: the client secret is POSTed to
        // tokenBaseUrl as a form field, so a host outside the allowlist means handing it over.
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                GraphMailHttpProperties(tokenBaseUrl = "https://attacker.example")
            }
        assertTrue(ex.message!!.contains("token-base-url"))
    }

    @Test fun `a foreign graph endpoint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(graphBaseUrl = "https://attacker.example")
        }
    }

    @Test fun `plain http is rejected even on an allowed host`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(tokenBaseUrl = "http://login.microsoftonline.com")
        }
    }

    @Test fun `a lookalike host is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(graphBaseUrl = "https://graph.microsoft.com.attacker.example")
        }
    }

    @Test fun `the test escape hatch allows a local endpoint and reports it as non-production`() {
        val properties =
            GraphMailHttpProperties(
                tokenBaseUrl = "http://localhost:8089",
                graphBaseUrl = "http://localhost:8089",
                allowNonMicrosoftEndpoints = true,
            )
        // The strict upload-host check must switch off exactly here, and nowhere else.
        assertFalse(properties.isProductionGraphEndpoint())
    }

    @Test fun `a zero timeout is rejected because it means wait forever`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                GraphMailHttpProperties(connectTimeoutSeconds = 0)
            }
        assertTrue(ex.message!!.contains("connect-timeout-seconds"))
    }

    @Test fun `an out of range attachment concurrency is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphMailHttpProperties(attachmentConcurrency = 0)
        }
    }

    // ── Upload hosts per cloud ─────────────────────────────────────────────
    //
    // The regression these lock down: the configuration accepted a sovereign Graph endpoint (the
    // test above proves it) while the upload-URL check only ever knew commercial hosts. Small mails
    // worked, and the first attachment over 2 MiB failed permanently on host validation.

    @Test fun `a sovereign cloud has upload hosts of its own`() {
        val usGov =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.microsoftonline.us",
                graphBaseUrl = "https://graph.microsoft.us",
            )
        val china =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.partner.microsoftonline.cn",
                graphBaseUrl = "https://microsoftgraph.chinacloudapi.cn",
            )
        assertNotNull(usGov.uploadHostSuffixes())
        assertNotNull(china.uploadHostSuffixes())
    }

    @Test fun `clouds do not share upload hosts`() {
        val commercial = GraphMailHttpProperties().uploadHostSuffixes()!!
        val usGov =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.microsoftonline.us",
                graphBaseUrl = "https://graph.microsoft.us",
            ).uploadHostSuffixes()!!

        // Each cloud accepts its own SharePoint domain and rejects the other's. A single flat list
        // would let a US Gov deployment accept a commercial upload host, which is what the boolean
        // this replaced allowed.
        assertTrue(commercial.contains(".sharepoint.com"))
        assertFalse(commercial.contains(".sharepoint.us"))
        assertTrue(usGov.contains(".sharepoint.us"))
        assertFalse(usGov.contains(".sharepoint.com"))
    }

    @Test fun `both US Gov Graph endpoints share one upload host set`() {
        val gcc =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.microsoftonline.us",
                graphBaseUrl = "https://graph.microsoft.us",
            ).uploadHostSuffixes()
        val dod =
            GraphMailHttpProperties(
                tokenBaseUrl = "https://login.microsoftonline.us",
                graphBaseUrl = "https://dod-graph.microsoft.us",
            ).uploadHostSuffixes()
        assertEquals(gcc, dod)
    }

    @Test fun `every upload host suffix starts with a dot`() {
        // The match in validateUploadUrl is host.endsWith(suffix); without the leading dot,
        // "evilsharepoint.com" would satisfy a ".sharepoint.com" entry written as "sharepoint.com".
        listOf("https://graph.microsoft.com", "https://graph.microsoft.us", "https://microsoftgraph.chinacloudapi.cn")
            .forEach { graph ->
                val token =
                    when {
                        graph.endsWith(".us") -> "https://login.microsoftonline.us"
                        graph.endsWith(".cn") -> "https://login.partner.microsoftonline.cn"
                        else -> "https://login.microsoftonline.com"
                    }
                GraphMailHttpProperties(tokenBaseUrl = token, graphBaseUrl = graph)
                    .uploadHostSuffixes()!!
                    .forEach { assertTrue(it.startsWith("."), "suffix '$it' for $graph must start with a dot") }
            }
    }

    @Test fun `a sandbox endpoint has no upload host set`() {
        val properties =
            GraphMailHttpProperties(
                tokenBaseUrl = "http://localhost:8089",
                graphBaseUrl = "http://localhost:8089",
                allowNonMicrosoftEndpoints = true,
            )
        // Null means "pin the upload URL to exactly this host" — see GraphMailClientImpl.
        assertNull(properties.uploadHostSuffixes())
    }
}
