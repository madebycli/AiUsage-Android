# AI Usage für Android

Native Codex- und Claude-Code-Verbrauchsanzeige auf Basis von
[AniHyou-android](https://github.com/axiel7/AniHyou-android).
Kotlin, Jetpack Compose und Material 3 Expressive. Android 8.0 oder neuer.

## Funktionen

- Codex und Claude Code mit getrennten verschlüsselten Anmeldungen.
- Nur verbundene Anbieter als Banner auf der Hauptseite; Details per Tap.
- Separate Anmeldeseite, keine Demo oder Vorschau ohne Login.
- Zeitfenster, Modelllimits, persönlicher Reset-Vorrat und Claude-Zusatzverbrauch.
- Reset Radar mit Anbieter-Untertabs, Forecasts und Quellenverläufen.
- Bisherige Farben und AniHyou-Tab-Leiste, gleichmäßig runde Karten und eigene Detailseiten mit Zurück-Pfeil.

Siehe [docs/FEATURES-0.3.md](docs/FEATURES-0.3.md) für Datenumfang, Quellen,
Claude-Import, Einschränkungen, Architektur und den aktuellen Gerätetest.

## Aktualisierung

**Jeder Tap auf „Jetzt aktualisieren“ fordert einen neuen persönlichen
Serverabruf an.** Kein lokaler Verbrauchs-TTL, kein HTTP-Diskcache und keine
Zehn-Minuten-Sperre. Netzwerkrequests setzen `Cache-Control: no-cache, no-store`.
Während eines laufenden Abrufs wird ein weiterer Abruf vorgemerkt;
weitere schnelle Taps werden zu diesem Folgeabruf zusammengefasst.

Das erzwingt keine neue Messung auf OpenAIs Server. Gleiche Antworten können
deshalb trotz erfolgreicher neuer Requests vorkommen. Es gibt keinen Token-Zähler und keine garantierte Echtzeitmessung. Auch nach einem
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

[Gerätetest und Anmeldung](docs/FEATURES-0.3.md#prüfung-auf-einem-gerät).
Codex nutzt den Browser-Gerätecode. Claude importiert vorhandene CLI-Zugangsdaten;
kopierte Refresh-Tokens werden nicht rotiert. Die Endpunkte sind inoffiziell.

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
bei langsamen Ablaufdetails, Login-Fehlerphasen und Abbruch-/Speicherfehler. Der Robolectric-UI-Test prüft Anmeldung ohne Vorschau, Anbieter-Sichtbarkeit,
Verbrauchsdetails, Forecasts, Account-Verwaltung und Dunkelmodus und erzeugt Screenshots unter
`app/build/screenshots/`.

## Herkunft

Siehe [docs/UPSTREAM.md](docs/UPSTREAM.md) für den exakten AniHyou-Commit,
übernommenen Code und Änderungen. GPL-3.0, siehe [LICENSE](LICENSE).

API-Dokumentation:
[codex-reset.com](https://codex-reset.com/developers),
[LunarWerx](https://codex.lunarwerx.com/api).
