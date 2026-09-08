package com.lowkey.backend.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Public privacy policy for Play Console / in-app About link.
 * Hosted on the API so the URL stays stable with the backend.
 */
fun Route.privacyRoutes() {
    get("/privacy") {
        call.respondText(PRIVACY_POLICY_HTML, ContentType.Text.Html)
    }
}

private val PRIVACY_POLICY_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Lowkey Privacy Policy</title>
  <style>
    :root { color-scheme: light dark; }
    body { font-family: ui-serif, Georgia, serif; max-width: 40rem; margin: 2rem auto; padding: 0 1.25rem; line-height: 1.55; }
    h1 { font-size: 1.6rem; font-weight: 600; }
    h2 { font-size: 1.1rem; margin-top: 1.75rem; }
    p, li { opacity: 0.9; }
    .meta { opacity: 0.65; font-size: 0.9rem; }
  </style>
</head>
<body>
  <h1>Lowkey Privacy Policy</h1>
  <p class="meta">Last updated: 8 September 2026 · Applies to the Lowkey Android app and chapter API.</p>

  <h2>Who we are</h2>
  <p>Lowkey is a private, invite-based messaging network organized as independent local chapters. Contact for privacy questions: <a href="mailto:iaj.evad@gmail.com">iaj.evad@gmail.com</a>.</p>

  <h2>What we collect</h2>
  <ul>
    <li><strong>Account metadata</strong> — display name, chapter membership, invite relationships, account creation time, and cryptographic public keys needed for end-to-end messaging.</li>
    <li><strong>Device push token</strong> — a Firebase Cloud Messaging token so we can wake your device for new activity. Push notifications never include message content.</li>
    <li><strong>Approximate location (optional)</strong> — only when you send a safety (SOS) alert, so nearby chapter members can see that someone needs help. Not collected at launch or continuously.</li>
    <li><strong>Microphone audio (optional)</strong> — only while you hold the mic to record a voice note. Audio is processed for that message; we do not keep a separate voice profile.</li>
    <li><strong>Technical logs</strong> — short-lived server logs (e.g. request timing, errors). Message ciphertext is not readable by us.</li>
  </ul>

  <h2>What we do not collect</h2>
  <ul>
    <li>Phone numbers or email addresses for signup (accounts are invite-based).</li>
    <li>Advertising IDs or third-party marketing trackers.</li>
    <li>Readable contents of your chats — messages are end-to-end encrypted.</li>
  </ul>

  <h2>How we use data</h2>
  <p>To run chapter messaging, contacts, groups, events, and safety alerts; to deliver push wake-ups; to enforce chapter moderation (including removing reported accounts); and to comply with lawful requests where we hold responsive metadata.</p>

  <h2>Sharing</h2>
  <p>We do not sell personal data. Infrastructure providers (hosting, database, push) process data only to operate the service. Chapters do not share user data with each other.</p>

  <h2>Retention</h2>
  <p>Account metadata is retained while the account exists and for a limited period afterward as configured on the server (default retention window on the order of 90 days for certain metadata). Ephemeral chats expire according to chapter/chat settings. You can leave by logging out and discarding local keys; ask a chapter contact if you need an account removed.</p>

  <h2>Security</h2>
  <p>Transport uses HTTPS/WSS. Message content is end-to-end encrypted with Signal Protocol primitives. Device backups of the app are disabled. No security measure is perfect.</p>

  <h2>Children</h2>
  <p>Lowkey is not directed at children under 13. Do not use the app if you are under the age required by your local law for this kind of service.</p>

  <h2>Your choices</h2>
  <p>You can deny location, microphone, and notification permissions in system settings. Denying them disables the related features (SOS location, voice notes, background alerts) but not basic encrypted chat while the app is open.</p>

  <h2>Changes</h2>
  <p>We may update this policy. The “Last updated” date above will change when we do. Continued use after a material change means you accept the updated policy.</p>

  <h2>Play Store / Data safety summary</h2>
  <p>Data collected: account info, messages (encrypted), approximate location (optional/SOS), audio files (optional/voice notes), device IDs (push token). Collected for app functionality; not sold; encrypted in transit; end-to-end for message content.</p>
</body>
</html>
""".trimIndent()
