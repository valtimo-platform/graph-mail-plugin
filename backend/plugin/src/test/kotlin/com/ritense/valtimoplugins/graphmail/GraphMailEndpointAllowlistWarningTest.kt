package com.ritense.valtimoplugins.graphmail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

/**
 * The escape hatch that switches off the Microsoft endpoint allowlist used to log nothing at all,
 * so a deployment could run with it on indefinitely without any signal.
 */
class GraphMailEndpointAllowlistWarningTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(GraphMailEndpointAllowlistWarning::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    @Test
    fun `a deployment on the Microsoft endpoints says nothing`() {
        GraphMailEndpointAllowlistWarning(GraphMailHttpProperties()).warnWhenDisabled()

        assertTrue(appender.list.isEmpty(), "a normal deployment should not be warned at: ${appender.list}")
    }

    @Test
    fun `switching the allowlist off is reported at ERROR on every startup`() {
        GraphMailEndpointAllowlistWarning(
            GraphMailHttpProperties(
                tokenBaseUrl = "http://localhost:8888",
                graphBaseUrl = "http://localhost:8888",
                allowNonMicrosoftEndpoints = true,
            ),
        ).warnWhenDisabled()

        assertEquals(1, appender.list.size)
        val event = appender.list.single()
        assertEquals(Level.ERROR, event.level)
        assertTrue(
            event.formattedMessage.contains("allow-non-microsoft-endpoints"),
            "the message should name the setting: ${event.formattedMessage}",
        )
        assertTrue(
            event.formattedMessage.contains("localhost:8888"),
            "the message should show where traffic actually goes: ${event.formattedMessage}",
        )
    }
}
