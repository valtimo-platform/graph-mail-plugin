/*
 * Copyright 2015-2025 Ritense BV, the Netherlands.
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

/**
 * Thrown by [GraphMailClientImpl] when the Graph API returns 401 even after the
 * implementation has already refreshed and retried with a fresh token.
 *
 * Reaching this exception almost always means the Mail.Send permission is missing
 * or the configured mailbox does not exist for the Azure tenant — token refresh
 * has been attempted internally already.
 */
class GraphMailTokenExpiredException(message: String, cause: Throwable? = null) : GraphMailException(message, cause)
