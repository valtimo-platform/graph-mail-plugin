package com.ritense.valtimoplugins.graphmail

import org.springframework.web.util.HtmlUtils

// Presentation concern kept separate from GraphMailTestSendController so the controller stays
// focused on request handling; only this file needs to change to restyle the test mail.
internal fun buildTestMailBody(sender: String): String {
    val escapedSender = HtmlUtils.htmlEscape(sender)
    return """
        <html>
        <body style="font-family: Arial, sans-serif; color: #333; padding: 32px; max-width: 600px;">
          <div style="background: #003d82; padding: 20px 24px; border-radius: 6px 6px 0 0;">
            <h2 style="color: #fff; margin: 0; font-size: 18px">Testmail — Microsoft Graph Mail Plugin</h2>
          </div>
          <div style="border: 1px solid #ddd; border-top: none; padding: 24px; border-radius: 0 0 6px 6px;">
            <p>Dit is een <strong>testmail</strong> om te valideren dat de e-mailconfiguratie correct werkt.</p>
            <table style="border-collapse: collapse; width: 100%; margin: 16px 0;">
              <tr style="background: #f5f5f5;">
                <td style="padding: 8px 12px; font-weight: bold; width: 140px; border: 1px solid #e0e0e0">Naam</td>
                <td style="padding: 8px 12px; border: 1px solid #e0e0e0">Pietje van Patje</td>
              </tr>
              <tr>
                <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">E-mailadres</td>
                <td style="padding: 8px 12px; border: 1px solid #e0e0e0">pietje@patje.nl</td>
              </tr>
              <tr style="background: #f5f5f5;">
                <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">Zaaknummer</td>
                <td style="padding: 8px 12px; border: 1px solid #e0e0e0">ZAK-2025-00001</td>
              </tr>
              <tr>
                <td style="padding: 8px 12px; font-weight: bold; border: 1px solid #e0e0e0">Status</td>
                <td style="padding: 8px 12px; border: 1px solid #e0e0e0">In behandeling</td>
              </tr>
            </table>
            <p style="color: #666; font-size: 13px; margin-top: 24px;">
              Als u dit bericht heeft ontvangen, zijn de credentials correct geconfigureerd en werkt
              de verbinding met Microsoft Graph API.
            </p>
          </div>
          <p style="font-size: 11px; color: #aaa; margin-top: 16px; text-align: center;">
            Verzonden via Microsoft Graph API &middot; Graph Mail Plugin configuratietest &middot; $escapedSender
          </p>
        </body>
        </html>
        """.trimIndent()
}
