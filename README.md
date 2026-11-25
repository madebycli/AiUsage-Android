# AI Usage für Android

Zeigt die Limits von Codex, Claude Code und Gemini auf dem Handy: 5-Stunden- und Wochenfenster, wann sie zurückgesetzt
werden, wie viel du wann verbraucht hast und welchen Account du als Nächstes nutzen solltest. Alle Werte stammen vom
jeweiligen Anbieter. Fehlende Angaben erscheinen als „nicht gemeldet“, nie als 0 %.

Kotlin, Jetpack Compose, Material 3 Expressive, Android 8.0 oder neuer. Sprachen: Englisch und Deutsch.

## Funktionen

- **Verbrauch**: eine Karte pro Account. Zwei Stile: *Kompakt* (Zahlen und Balken) und *Verspielt*
  (Material-Formen, die sich mit dem Verbrauch füllen). Reset als Uhrzeit, Restzeit darunter.
- **Planer**: welcher Account jetzt dran ist, die nächsten sieben Tage als Kalenderstreifen mit vorgeschlagenen
  5-Stunden-Fenstern und Wochenresets, Budget bis zum Reset (wie viele volle 5-Stunden-Fenster das Wochenlimit noch
  erlaubt, was ungenutzt verfällt) und deine üblichen Startzeiten. Siehe [docs/PLANNER.md](docs/PLANNER.md).
- **Statistiken**: Woche, Monat, Jahr; pro Account oder gesamt; 5-Stunden- oder Wochenlimit. Jedes 5-Stunden-Fenster
  einzeln, am Tag seines Beginns gezählt. Wie gezählt wird: [docs/USAGE-TRACKING.md](docs/USAGE-TRACKING.md).
- **Resets**: deine nächsten Resets aller Accounts, dazu je Anbieter Wahrscheinlichkeit, letzter globaler Reset,
  Verlauf und gespeicherte Resets.
- **Hintergrund**: Abruf im gewählten Intervall (15 Minuten bis 5 Stunden) und kurz vor dem Ende jedes laufenden
  Fensters, über AlarmManager. Ein Protokoll der letzten 100 Abrufe zeigt, ob das auf deinem Gerät funktioniert.
- **Widget**, **Reset-Hinweise**, **mehrere Accounts pro Anbieter**, Einstellungen im Iris-Stil.

## Anbieter

| Anbieter | Anmeldung | Quelle |
| --- | --- | --- |
| Codex | Gerätecode von OpenAI | `chatgpt.com/backend-api/wham/usage` |
| Claude Code | Browser-Freigabe mit Code ([Details](docs/CLAUDE-PHONE-LOGIN.md)) | `api.anthropic.com/api/oauth/usage` |
| Gemini (experimentell) | Google im Browser, Rückleitung an die App | Antigravity `retrieveUserQuotaSummary` |

Alle Verbrauchsendpunkte sind inoffiziell und können sich ändern. Gemini für private Google-Konten läuft seit
18.06.2026 über Antigravity statt über die Gemini CLI. Der Ablauf folgt
[OpenUsage](https://github.com/robinebers/openusage) (MIT).

## Bauen

JDK 17, Android SDK Platform 37, Build Tools 37.0.0:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Gemini braucht einen Google-OAuth-Client für installierte Apps. Er gehört nicht ins Repository; ohne ihn blendet
die App Gemini aus. Lokal in `local.properties`:

```properties
gemini.clientId=…
gemini.clientSecret=…
```

In der CI als Secrets `GEMINI_OAUTH_CLIENT_ID` und `GEMINI_OAUTH_CLIENT_SECRET`.

## Übersetzen

Alle Texte stehen in `app/src/main/res/values/strings.xml` (Englisch) und `values-de/strings.xml`.
Einrichtung mit Weblate: [docs/TRANSLATING.md](docs/TRANSLATING.md).

## Aufbau

- `data/`: Anbieter-Clients, Parser, Ledger (Zyklen), Planer, Formatierung, verschlüsselter Speicher
- `account/`: Account-Liste, eine Laufzeit pro Account, Anmeldungen
- `background/`: periodischer Abruf, Abruf vor Fensterende, Protokoll, Reset-Hinweise
- `widget/`: Renderer, Provider und Konfiguration
- `ui/`: Navigation, Seiten, Einstellungen (Iris), gemeinsame Bausteine

## Herkunft

Basis [AniHyou-android](https://github.com/axiel7/AniHyou-android) (GPL-3.0, [docs/UPSTREAM.md](docs/UPSTREAM.md)).
Designbausteine aus [Iris](https://github.com/madebycli/iris-android) und
[Orion Store](https://github.com/RookieEnough/Orion-Store) (GPL-3.0, [docs/IRIS_DESIGN.md](docs/IRIS_DESIGN.md)).

GPL-3.0, siehe [LICENSE](LICENSE). Inoffizielle App, unabhängig von OpenAI, Anthropic und Google.
