# S04TV Fire Edition

Inoffizielle Fire-TV-WebView-App für https://tv.schalke04.de/.
Keine Verbindung zum FC Schalke 04. Keine Weiterverbreitung der Videos.

## Bedienung

- **Menü**: Einstellungen
- **Zurück zweimal binnen 2 Sekunden**: App beenden
- **Zurück im Video-Vollbild**: Vollbild verlassen
- Website im Vollbild, Navigation über die Fire-TV-Fernbedienung (abhängig von der Website)

## Anmeldung

Die App erhält Cookies und WebStorage für bestehende S04-Anmeldesitzungen.
Unter **Menü → Zugangsdaten** können E-Mail und Passwort optional mit
Android Keystore (AES-GCM) lokal verschlüsselt abgelegt werden.
Die Daten werden nur beim Laden von HTTPS-Seiten des Hosts
\`account.schalke04.de\` in leere Loginfelder gesetzt. Die App sendet das
Formular **niemals** selbst ab. Die Eingabefelder werden bei Nutzung
durch JavaScript im Kontext dieser Originalwebsite befüllt.
Login-Feldnamen/Seitenstruktur können sich ändern; dann funktioniert
das Ausfüllen eventuell nicht mehr. Sitzungen können ablaufen.

## Darstellungsgröße / Auflösung

**Menü → Darstellungsgröße** bietet automatisch, 720p, 1080p und 4K.
Diese Werte entsprechen virtuellen **CSS-Viewport-Breiten** (1280,
1920, 3840 Pixel), nicht der tatsächlichen HDMI-Ausgabe, der
Videoauflösung oder der Bitrate. Diese experimentelle Einstellung
ändert das Viewport-Metatag; Webseiten können das ignorieren.
Die tatsächliche Ausgabeauflösung wird in Fire TV selbst eingestellt.

## APK bauen

In GitHub: **Actions → Build Fire TV APK → Run workflow**.
Bei erfolgreichem Build das Artifact \`s04tv-fire-edition-debug-apk\`
herunterladen und die enthaltene \`app-debug.apk\` installieren.

Alternativ: JDK 17, Android SDK 35 und Gradle 8.10.2:

\`\`\`sh
gradle assembleDebug
adb connect FIRETV-IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
\`\`\`

Das Projekt verwendet keinen Gradle-Wrapper. GitHub Actions
installiert Gradle 8.10.2 automatisch.

## Wichtige Einschränkungen

Der S04TV-Webplayer wurde nicht auf einem realen Fire TV getestet.
Videowiedergabe kann wegen WebView-, HLS-, EME- oder DRM-Unterstützung
fehlschlagen. Die App umgeht keinen Kopierschutz und bringt keine
zusätzlichen Decoder oder Drittanbieter-SDKs mit. Keine Garantie für
Login-Autofill bei dynamischen Formularen. Apps können nicht zuverlässig
die physische Fire-TV-HDMI-Auflösung umstellen.

Die App hat keinen eigenen Server und verwendet nur HTTPS.
Android-Backups sind deaktiviert.

## Lizenz

Siehe [LICENSE](LICENSE).
