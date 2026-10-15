# AI Usage für Android

Zeigt dein Codex- und Claude-Code-Kontingent auf dem Handy. Alle Werte stammen direkt
vom jeweiligen Anbieter; die App rechnet nichts nach und erfindet nichts. Fehlende
Angaben erscheinen als „nicht gemeldet“, nie als 0 %.

Kotlin, Jetpack Compose, Material 3 Expressive, Android 8.0 oder neuer. Basis ist
[AniHyou-android](https://github.com/axiel7/AniHyou-android), das Design folgt
[AniSync](https://github.com/Marco-9456/AniSync) (siehe [docs/UPSTREAM.md](docs/UPSTREAM.md)).

## Funktionen

- **Anmeldung** für beide Anbieter über eine gleich aufgebaute Seite.
  Codex nutzt den Gerätecode von OpenAI, Claude die Browser-Freigabe mit einmaligem Code
  ([Details](docs/CLAUDE-PHONE-LOGIN.md)). Ein laufender Codex-Versuch übersteht auch einen
  Neustart der App. Tokens liegen verschlüsselt im Android Keystore.
- **Verbrauch**: 5-Stunden- und Wochenfenster, Modelllimits, Reset-Vorrat, Zusatzverbrauch.
- **Statistiken**: Codex zeigt die Berichte, die OpenAI für den Account freigibt
  (Tages-Tokens, Credits). Claude liefert über die Anmeldung nur Limits; der Verlauf
  besteht aus den Werten, die Claude bei den Aktualisierungen gemeldet hat.
- **Resets**: öffentliche Schätzungen und Verlauf (codex-reset.com, LunarWerx,
  Community-Tracker), getrennt vom persönlichen Konto.
- **Benachrichtigungen**: Hinweis zum gemeldeten Reset-Zeitpunkt, getrennt für 5 Stunden
  und Woche. Der Hintergrundabruf läuft alle 3 Stunden; Android kann ihn verzögern.

## Aktualisierung

Der Aktualisieren-Knopf liest immer neu beim Anbieter. Beim Öffnen der App werden nur
Werte gelesen, die älter als zwei Minuten sind. Bei HTTP 429 wartet die App die vom
Anbieter genannte Zeit; bei Fehlern bleibt die letzte Messung sichtbar.
Die Verbrauchsendpunkte beider Anbieter sind inoffiziell und können sich ändern.

## Bauen

JDK 17, Android SDK Platform 37, Build Tools 37.0.0:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Die CI baut dieselbe APK und lädt sie als `AiUsage-debug` hoch. Die APK ist mit einem
öffentlichen Test-Schlüssel signiert und kein Play-Store-Release.

## Aufbau

- `account/`: je ein Controller für Codex, Claude und die öffentlichen Reset-Daten
- `data/`: Anbieter-Clients, Parser, verschlüsselter Speicher
- `background/`: periodischer Abruf und Reset-Benachrichtigungen
- `ui/`: Navigation, Seiten und gemeinsame Komponenten

GPL-3.0, siehe [LICENSE](LICENSE). Inoffizielle App, unabhängig von OpenAI und Anthropic.
