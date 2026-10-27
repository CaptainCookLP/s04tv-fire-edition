# S04TV Fire Edition

**Version 0.6.1 – Autoplay, Back-Fix, dauerhafter Bildercache und Startbildschirm**

Inoffizielle Fire-TV-/Android-TV-App für https://tv.schalke04.de/. Kein offizielles Produkt des FC Schalke 04. Anmeldung, Inhalte und Zugriffsrechte werden vollständig von S04TV und 3Q bereitgestellt. Keine Umgehung von DRM, Abonnements oder Ein-Gerät-Regeln.

## Was die App macht

- Der **WebView-Katalog** verwendet Steuerkreuz-Navigation zwischen Videokacheln, hervorgehobenen Fokusrahmen, OK zum Öffnen und Zurück zur letzten Katalogseite.
- Auf S04TV-Videoseiten stellt die App den **tatsächlich beobachteten 3Q-Container** `.video__player.js3q-wrapper` bildschirmfüllend dar. Der TV-Modus ist ausdrücklich nicht mit der HTML-Fullscreen-API gleichzusetzen.
- Für Wiedergabe, Pause, 10-Sekunden-Seeks und echtes Vollbild verwendet der separate `ThreeQPlayerController` nur die **öffentlich dokumentierten 3Q-Web-SDK-Methoden** auf `window.player`. Keine Stream-URL-Extraktion, keine HTML5-Video-Manipulation des 3Q-MSE-Players und keine Tastensimulation.
- Hoch/Runter öffnet die **native, per Steuerkreuz fokussierbare Playerleiste**: 10 Sekunden zurück, Wiedergabe/Pause, 10 Sekunden vor, Vollbild, Diagnose, Schließen.
- Die native Fullscreen-Schaltfläche ruft `player.fullscreen()` auf, sofern die Player-Instanz bereit ist. Die tatsächliche Vollbildaktivierung wird diagnostisch über `document.fullscreenElement` unterschieden. Fire-WebView und Nutzeraktivierung können diese Funktion einschränken.
- Die WebView verwendet `WebChromeClient.onShowCustomView/onHideCustomView`; Zurück wird vor der WebView verarbeitet. Nach dem Video wird der Katalog wieder aufgerufen.
- Die S04-Account-Anmeldeseite behält ihr TV-Design, dynamisches Autofill und die Originalweiterleitungen. Anmeldedaten werden optional lokal über Android Keystore verschlüsselt gespeichert.
- Updates werden von signierten GitHub Releases geladen und vor der Android-Installation per SHA-256 geprüft.

## Fernbedienung

| Kontext | Taste | Aktion |
| --- | --- | --- |
| Katalog | Links/Rechts/Hoch/Runter | Videokachel auswählen |
| Katalog | OK | Ausgewählte Kachel öffnen |
| Videoseite | OK oder Play/Pause | Wiedergabe umschalten |
| Videoseite | Links/Rechts | -10/+10 Sekunden |
| Videoseite | Hoch oder Runter | Native Playerleiste öffnen |
| Playerleiste | Steuerkreuz + OK | Native Buttons bedienen |
| Playerleiste | Zurück | Playerleiste schließen |
| Video-Vollbild | Zurück | Zuerst Android-Vollbild schließen |
| Videoseite | Zurück | Zur vorherigen Katalogseite |
| Startseite | Zweimal Zurück | App beenden |
| Überall | Menü | Einstellungen und Diagnose |

## Diagnose

Auf einer Videoseite: **Menü → Player-Steuerung / Diagnose → Playerdiagnose anzeigen** oder über **Hoch → Info**.

Die Diagnose enthält ausschließlich sichere Daten: Player-Readiness, Wrapper-/Videomaße, Bildschirmgröße, Fullscreen-/TV-Layout-Status, Wiedergabezeit und die Namen der letzten Playerereignisse. **Keine Zugangsdaten, Tokens, Playout-IDs, URLs oder vollständigen Event-Payloads** werden erfasst.

Die Testbefunde der ursprünglichen Website: **3Q 5.4.7**, globales `window.player = new js3q(...)`, direkt im Hauptdokument, HTML5-Video unter `video.sdn-source-element`. Methoden laut offizieller Dokumentation: https://player.docs.3q.video/player-web-sdk/methods.

Ein echter Fire-TV-Test ist weiterhin nötig, insbesondere für HTML-Fullscreen (User Activation), Touch-/Keyevent-Fokus, VoD, Live, Untertitel und die Rückkehr in den Katalog. **Ein erfolgreicher Gradle-Build bestätigt nur die Kompilierung, nicht die Wiedergabe auf dem Gerät.**

## Native Media3-Wiedergabe (geplant, nicht umgesetzt)

Die untersuchte Webseite nutzt HLS mit .m3u8 und .m4s sowie AVC/AAC. Das Format ist für ExoPlayer grundsätzlich geeignet. **Nicht geklärt** sind eine vom Inhalteanbieter unterstützte native Playback-Schnittstelle, der zulässige Berechtigungskontext, Token-Erneuerung, DRM, Heartbeats und Ein-Gerät-Regeln. ExoPlayer wird daher erst integriert, wenn die autorisierte native Stream-Übergabe feststeht. Betreiber-API-Schlüssel dürfen nicht in die App eingebettet werden.

## Projektstruktur

- `MainActivity.java` – WebView, Navigation, Fullscreen-CustomView, Menüs und Lebenszyklus.
- `TvFocusNavigation.java` – räumlicher Videokachel-Fokus im Katalog.
- `ThreeQPlayerController.java` – gesicherte Playerbefehle und bereinigte Diagnosen über die dokumentierte Web-SDK-API.
- `VideoTvMode.java` – separat gekennzeichnete bildschirmfüllende CSS-TV-Darstellung des Originalplayers.
- `TvPlayerControls.java` – native Fire-TV-Bedienleiste.
- `LoginAutoFill.java` / `LoginTvOverlay.java` – Salesforce-Anmeldung.
- `UpdateManager.java` – signiertes GitHub-APK-Update.

## Build und Signatur

**GitHub Actions → Build Fire TV APK** baut Debug und, sofern die folgenden vier GitHub Secrets eingerichtet sind, ein signiertes Release bei Push auf `main`:

- `S04TV_KEYSTORE_BASE64`
- `S04TV_KEYSTORE_PASSWORD`
- `S04TV_KEY_ALIAS`
- `S04TV_KEY_PASSWORD`

JDK 17, Android SDK 35, Gradle 8.10.2; lokaler Build: `gradle assembleDebug`. Das Repository enthält keinen Gradle Wrapper. Die Release-APK heißt `s04tv-fire-edition.apk`.

Einstellungen: **Darstellungsgröße** passt nur die virtuelle CSS-Viewportbreite an, nicht HDMI oder Streamauflösung. **Anmeldesitzung löschen** entfernt Cookies/WebStorage, nicht die verschlüsselten Zugangsdaten.


## v0.6.1 – Start, Cache, Updates und Videosteuerung

- **Automatischer Videostart:** Nach dem Aufruf einer Video-Detailseite versucht die App, den regulären 3Q-Player über `window.player.play()` zu starten, sobald das HTML5-Medium bereit ist. Maximal drei verzögerte Versuche pro neuem Video; die App setzt ein vom Benutzer pausiertes Video nicht wieder fort. Fire-OS-/Autoplay-Vorgaben können den Start verhindern; Fehler sind in der Playerdiagnose erkennbar.
- **Zurück in einem Schritt:** Fire-TV-Back verlässt die Videowiedergabe, beendet eine eventuell aktive native Fullscreen-Ansicht und öffnet die letzte Videoübersicht. Auch die Android-13-Back-Callback-Schnittstelle wird bedient.
- **Startbildschirm:** Natives blau-dunkles S04TV+-Ladebild mit Status und Ladefortschritt. Die Website wird erst nach der Prüfung auf ein GitHub-Update geladen. Bei einem verfügbaren Update erscheint das Installations-/Downloadangebot über dem Ladebild, vor der Website.
- **Updateprüfung bei jedem App-Start:** Der bislang mögliche 24-Stunden-Skip gilt nicht für den Startbildschirm. Netzwerkfehler blockieren die App nicht dauerhaft; nach einem Timeout wird die Website trotzdem geöffnet.
- **Dauerhafter Cache:** Die HTTP-Cache- und DOM-Speicherdaten der WebView bleiben über normale App-Neustarts erhalten. Zusätzlich speichert die App geeignete **öffentliche, nicht signierte Bilddateien** von `tv.schalke04.de` in ihrem privaten App-Datenverzeichnis. Dieser Bildcache bleibt beim Neustart bestehen, ist 128 MB groß und entfernt veraltete Einträge nach maximal sieben Tagen. Die Einstellung **Gespeicherten Bildercache leeren** löscht ihn auf Wunsch.
- **Sicherheit:** Keine dauerhafte Speicherung von geschützten Videostreams, HLS-/DASH-Manifesten, Lizenzantworten, persönlichen HTML-Seiten, Session-Cookies in Bilddateien oder URL-Tokens. Der normale HTTP-Cache berücksichtigt Server-Cache-Richtlinien; das Android-System kann dessen Inhalte bei Bedarf bereinigen.

### Testschwerpunkte
Nach dem APK-Update Startscreen mit GitHub-Updateprüfung, bereits gecachte Videokacheln nach Neustart, automatischen Start von VoD, Pause per OK und Rückkehr mit einer einzigen Zurück-Taste im Videovollbild testen. Geschützte Live-Videos separat prüfen.
