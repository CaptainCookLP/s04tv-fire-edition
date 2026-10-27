# S04TV Fire Edition

**Version 0.3.1 — Salesforce-Login-Autofill und GitHub-Updates**

Inoffizielle Fire-TV-WebView-App für https://tv.schalke04.de/.
Keine Verbindung zum FC Schalke 04. Keine Weiterverbreitung der Videos.

## Bedienung

- **Menü**: Einstellungen
- **Zurück zweimal binnen 2 Sekunden**: App beenden
- **Zurück im Video-Vollbild**: Vollbild verlassen
- **Mauszeiger standardmäßig aktiviert**: Steuerkreuz bewegt den sichtbaren
  Mauszeiger (auch bei Cookie-Bannern und im Videovollbild).
- **OK**: Mausklick an der Cursorposition (synthetischer Android-Touch,
  auch für im WebView eingebettete Frames).
- **Menü → Mauszeiger ausschalten/einschalten**: zwischen Mausmodus und
  normaler Webseiten-D-Pad-Navigation wechseln.
- **Menü → Mausgeschwindigkeit**: Langsam / Normal / Schnell.
- **Menü → Mauszeiger zentrieren**: Cursor in Bildschirmmitte versetzen.
- **Menü → Auf Updates prüfen**: prüft öffentliche GitHub Releases.
- **Automatische Prüfung** beim Start höchstens einmal pro 24 Stunden.
- **Update auf Bestätigung herunterladen**: SHA-256-Prüfung und Systeminstallation.
- **Menü → Heruntergeladenes Update installieren**: geprüften Download erneut öffnen.
- Eigenes blaues **S04TV-Icon** und TV-Banner mit Wortmarke (kein Vereinswappen).
- Website im Vollbild, Navigation über die Fire-TV-Fernbedienung.

## Anmeldung

Die App erhält Cookies und WebStorage für bestehende S04-Anmeldesitzungen.
Unter **Menü → Zugangsdaten** können E-Mail und Passwort optional mit
Android Keystore (AES-GCM) lokal verschlüsselt abgelegt werden.
Die Daten werden ausschließlich auf HTTPS-Seiten von
`account.schalke04.de` mit Login-Pfad (`/s/login/`, `/login/` oder
Startseite) in **sichtbare, leere** Loginfelder eingetragen.
Die Salesforce-Anmeldeseite kann ihre Formularfelder dynamisch nachladen:
Die App überwacht das Login-Formular deshalb bis zu 30 Sekunden lang und
erkennt auch Eingabefelder mit Beschriftungen **E-Mail-Adresse** und
**Passwort** sowie offene Shadow-DOM-Felder. Bereits von Hand ausgefüllte
Felder werden nicht überschrieben. Die Anmeldung wird **niemals**
automatisch abgeschickt. Sitzungen können weiterhin ablaufen.

Die langen Parameter `startURL` und `source` der Salesforce-
Anmeldeweiterleitung werden von der App nicht gespeichert oder
protokolliert. Die Website führt die Weiterleitung selbst aus.

**Erste Anmeldung:** Unter **Menü → Zugangsdaten** einmalig E-Mail und
Passwort speichern. Danach werden beide Felder beim nächsten Besuch der
S04-Account-Anmeldeseite automatisch ausgefüllt. Ohne vorher gespeicherte
Zugangsdaten gibt es kein Autofill. Feldnamen oder Seitenskripte können sich
ändern; die Bedienung muss auf einem echten Fire TV geprüft werden.

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

Der S04TV-Webplayer und der Mauszeiger wurden nicht auf einem realen Fire TV getestet.
Der Mausmodus sendet Touch-Events an das WebView bzw. die Vollbild-Player-Ansicht;
falls einzelne Cookie-Dialoge oder fremde Player-Overlays Touch-Eingaben
ablehnen, ist ein zusätzlicher Fix erforderlich.
Der Cursor wird nicht in Android-Systemdialogen angezeigt; diese werden
weiter mit dem normalen Steuerkreuz bedient.
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

## GitHub Releases als Updatequelle

Die App prüft ausschließlich den neuesten öffentlichen GitHub Release von
\`CaptainCookLP/s04tv-fire-edition\` über die GitHub-API. Erwarteter Asset-Name:
\`s04tv-fire-edition.apk\`; Release-Tag \`vMAJOR.MINOR.PATCH\`.
Nach der Bestätigung lädt sie die APK über HTTPS herunter, prüft den SHA-256
Digest des offiziellen GitHub-Release-Assets und öffnet den Android-Installer.
Es erfolgt keine stille Installation und kein DRM-Eingriff. Für Fire OS muss
gegebenenfalls unter den Geräteeinstellungen das Installieren von Apps aus
dieser Quelle erlaubt werden.

### Dauerhafte Release-Signatur einmalig einrichten

**Debug-APKs aus GitHub Actions können nicht zuverlässig über neue Versionen
aktualisiert werden**, weil GitHub Runner temporäre Debug-Signaturen erzeugen.
Ein dauerhaft verwendeter privater Release-Keystore ist für die
In-App-Updates erforderlich. Bei Umstellung von einer Debug-APK auf die
erste Release-APK muss die Debug-Installation ggf. einmalig deinstalliert
werden. Dabei gehen lokal gespeicherte Cookies und Anmeldedaten verloren.

Erzeuge einen **privaten** Android-Keystore und bewahre ihn offline auf:

\`\`\`bash
keytool -genkeypair -v -keystore s04tv-release.jks -alias s04tv -keyalg RSA -keysize 3072 -validity 10000
\`\`\`

Unter **Settings → Secrets and variables → Actions → Repository secrets**
sind folgende vier Werte einzutragen:

| Secret | Wert |
| --- | --- |
| \`S04TV_KEYSTORE_BASE64\` | Vollständige JKS-Datei als Base64 ohne Zeilenumbrüche |
| \`S04TV_KEYSTORE_PASSWORD\` | Passwort der JKS-Datei |
| \`S04TV_KEY_ALIAS\` | Alias des Schlüssels (\`s04tv\`) |
| \`S04TV_KEY_PASSWORD\` | Passwort des Schlüssels |

Für Base64 etwa unter PowerShell:

\`\`\`powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ".\\s04tv-release.jks")))
\`\`\`

**Keystore und Passwörter niemals im Repository speichern.**
Falls der Keystore verloren geht, können installierte Versionen nicht mehr
mit derselben Signatur aktualisiert werden.

Der Workflow **Build Fire TV APK** erzeugt auf jedem Push nach \`main\`
ein Debug-APK als Actions-Artifact. Wenn die vier Secrets gesetzt sind,
baut er zusätzlich ein signiertes APK und veröffentlicht es automatisch
als **GitHub Release** mit Tag \`v<versionName>\` und Asset
\`s04tv-fire-edition.apk\`. Ein existierendes Release wird nicht
überschrieben; für das nächste Release müssen \`versionCode\` und
\`versionName\` in \`app/build.gradle\` erhöht werden.

Nach Einrichtung der Secrets kann der Workflow auf \`main\` manuell
über **Actions → Build Fire TV APK → Run workflow** erneut gestartet werden.
Der Workflow benötigt für Releases \`contents: write\` für den
\`GITHUB_TOKEN\`. Falls eine Repository-Richtlinie Schreibzugriffe
für GitHub Actions deaktiviert, muss diese Einstellung angepasst werden.

### Release-Asset-Name

Ab v0.3.1 wird das signierte Release-APK tatsächlich als
`s04tv-fire-edition.apk` hochgeladen (nicht nur mit diesem Anzeigelabel).
Dies entspricht dem Dateinamen, den der In-App-Updater erwartet.
Die ältere v0.3.0-Veröffentlichung hatte dagegen den tatsächlichen
Dateinamen `app-release.apk`.
