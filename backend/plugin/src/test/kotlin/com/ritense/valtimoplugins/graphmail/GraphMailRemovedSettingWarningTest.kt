package com.ritense.valtimoplugins.graphmail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.env.MockEnvironment

/**
 * `graph-mail.require-secret-for-allowlist-change` was removed in 1.1.0. A deployment that set it must hear that it no
 * longer does anything, rather than believe the control is still active.
 */
class GraphMailRemovedSettingWarningTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(GraphMailRemovedSettingWarning::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    @Test
    fun `a deployment without the setting says nothing`() {
        GraphMailRemovedSettingWarning(MockEnvironment()).warnWhenSet()

        assertTrue(appender.list.isEmpty(), "nothing to report: ${appender.list}")
    }

    @Test
    fun `the setting is reported as ignored`() {
        GraphMailRemovedSettingWarning(
            MockEnvironment().withProperty(REQUIRE_SECRET_FOR_ALLOWLIST_CHANGE, "true"),
        ).warnWhenSet()

        val event = appender.list.single()
        assertEquals(Level.WARN, event.level)
        assertTrue(event.formattedMessage.contains("$REQUIRE_SECRET_FOR_ALLOWLIST_CHANGE=true"), event.formattedMessage)
        assertTrue(event.formattedMessage.contains("is ignored"), event.formattedMessage)
    }
}
