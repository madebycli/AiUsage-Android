# AI Usage für Android

Native Codex-Verbrauchsanzeige auf Basis von
[AniHyou-android](https://github.com/axiel7/AniHyou-android).
Kotlin, Jetpack Compose und Material 3 Expressive. Android 8.0 oder neuer.

## Funktionen

- Verbrauch und verbleibendes Budget für die vom Account gemeldeten
  5-Stunden- und Wochenfenster, jeweils mit Reset-Zeit und lokalem Countdown.
- Zusätzlich vom Server gemeldete Modell-/Reserve- und Code-Review-Limits.
- Gespeicherte persönliche Resets: verfügbare Anzahl, Art und Ablaufdetails,
  soweit der Account sie liefert. Keine automatische Einlösung.
- Öffentlicher Reset-Verlauf aus codex-reset.com oder LunarWerx, inklusive
  Quellenlinks, Quellenstand und klarer Trennung von persönlichen Daten.
- Gerätecode-Anmeldung im Browser mit sichtbaren Phasen, Fehlerdialog und automatischer Token-Erneuerung.
- Verschlüsselte lokale Tokens und Messungen im Android Keystore, kein Backup,
  keine Analytics, kein eigener Server.
- Dynamische Android-Farben, System-/Hell-/Dunkelmodus, schwarzer Hintergrund.
- Expliziter Demomodus mit markierten Beispieldaten.

## Aktualisierung

**Jeder Tap auf „Jetzt aktualisieren“ fordert einen neuen persönlichen
Serverabruf an.** Kein lokaler Verbrauchs-TTL, kein HTTP-Diskcache und keine
Zehn-Minuten-Sperre. Netzwerkrequests setzen `Cache-Control: no-cache, no-store`.
Während eines laufenden Abrufs wird ein weiterer Abruf vorgemerkt;
weitere schnelle Taps werden zu diesem Folgeabruf zusammengefasst.

Das erzwingt keine neue Messung auf OpenAIs Server. Gleiche Antworten können
deshalb trotz erfolgreicher neuer Requests vorkommen. Die Anzeige `pp/s`
bedeutet Prozentpunkte pro Sekunde, gemittelt zwischen zwei Abrufen desselben
Fensters. Das ist kein Token-Zähler und keine garantierte Echtzeitmessung.
Der Messwert steigt ausschließlich durch echte Antworten. Auch nach einem
abgelaufenen Countdown wird kein Budget erfunden.

Der Verbrauch erscheint sofort nach der Quota-Antwort. Optionale Reset-Ablaufdetails
werden getrennt geladen und haben einen eigenen Abrufzeitpunkt. Langsame Details
blockieren weder die Anzeige noch weitere persönliche Quota-Abrufe.

HTTP 429 respektiert die vom Anbieter vorgegebene Wartezeit. Fehler lassen die
letzte Messung sichtbar, ohne deren Abrufzeit zu erneuern. Fehlende Fenster
werden als „nicht gemeldet“ bezeichnet, niemals als 0 % Verbrauch.

Öffentliche Reset-Daten sind unabhängig davon: codex-reset.com erlaubt
höchstens einen Abruf pro Minute, LunarWerx einen pro fünf Minuten.
Ein Refresh erneuert deinen Account trotzdem. Quellenstand und Abrufzeit
stehen getrennt im Reset-Tab. Die beiden Quellen sind Community-Projekte,
keine Bestätigung eines Resets auf deinem persönlichen Account.

## Auf einem Gerät testen

1. Debug-APK installieren.
2. Optional „Erst das Design ansehen“ auswählen. Die Beispieldaten bleiben
   sichtbar als Demo gekennzeichnet.
3. Für echte Daten „Mit Codex verbinden“ auswählen. Falls nötig die
   Gerätecode-Anmeldung in den Codex-Sicherheitseinstellungen aktivieren.
4. Den angezeigten einmaligen Code unter
   `https://auth.openai.com/codex/device` im Browser bestätigen.
5. Zu AI Usage zurückkehren. Die App zeigt erst die Übernahme der Freigabe und
   anschließend den ersten Verbrauchsabruf. Fehler bleiben in einem Dialog
   sichtbar, auch wenn die Anmeldung aus den Einstellungen gestartet wurde.
   Falls der Browser bestätigt, aber Werte fehlen: Fehlerdialog beachten.
   Im Logcat erscheinen unter `AiUsage` nur Phase, Fehlerklasse und HTTP-Status,
   keine Codes, Tokens, Account-IDs oder Antwortinhalte. Temporäre Netzwerk-
   und Serverfehler beim Warten auf die Freigabe werden innerhalb der 15 Minuten
   erneut abgefragt. Der einmalige OAuth-Code-Austausch wird niemals automatisch
   wiederholt. Ein Rücksprung-Link aus dem Browser ist nicht erforderlich.
6. Eine Codex-Aufgabe ausführen und mehrfach „Jetzt aktualisieren“ drücken.
   Abrufzeit und Verbrauch prüfen. Bei kleinen Aufgaben kann die gerundete
   Anbieter-Prozentzahl unverändert bleiben.
7. Flugmodus aktivieren und erneut aktualisieren: Fehler anzeigen, alten
   Abrufzeitpunkt beibehalten. Flugmodus aus, erneut aktualisieren.
8. Unter „Resets“ persönliche Guthaben von öffentlichen Meldungen unterscheiden.

Die App verwendet dieselben Account-Endpunkte und den öffentlichen
OAuth-Client wie Codex. Sie ist inoffiziell. Diese Endpunkte sind keine
zugesicherte allgemeine Drittanbieter-API. Anmeldung und reale Account-Werte
müssen auf einem echten Account getestet werden.

## Bauen

JDK 17, Android SDK Platform 37 und Build Tools 37.0.0:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Die GitHub-Actions-Pipeline erstellt dieselbe APK und lädt sie als
`AiUsage-debug` hoch. APKs sind Debug-signiert, keine Play-Store-Releases.

Tests prüfen Parser, fehlende Fenster, fehlerhafte Antworten, getrennte
Banked-/Global-Meldungen, Folgeabrufe, HTTP-Cache-Verhalten, 429 und die
Persistierung rotierter Tokens, parallele Token-Rotation, sofortige Quota-Abrufe
bei langsamen Ablaufdetails, Login-Fehlerphasen und Abbruch-/Speicherfehler. Der Robolectric-UI-Test prüft Demo,
Reset-Quellenwechsel und Dunkelmodus und erzeugt Screenshots unter
`app/build/screenshots/`.

## Herkunft

Siehe [docs/UPSTREAM.md](docs/UPSTREAM.md) für den exakten AniHyou-Commit,
übernommenen Code und Änderungen. GPL-3.0, siehe [LICENSE](LICENSE).

API-Dokumentation:
[codex-reset.com](https://codex-reset.com/developers),
[LunarWerx](https://codex.lunarwerx.com/api).
