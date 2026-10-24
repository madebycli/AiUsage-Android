# AI Usage für Android

Zeigt dein Codex- und Claude-Code-Kontingent auf dem Handy. Alle Werte stammen direkt
vom jeweiligen Anbieter; die App rechnet nichts nach und erfindet nichts. Fehlende
Angaben erscheinen als „nicht gemeldet“, nie als 0 %.

Kotlin, Jetpack Compose, Material 3 Expressive, Android 8.0 oder neuer. Basis ist
[AniHyou-android](https://github.com/axiel7/AniHyou-android), das Design folgt
[AniSync](https://github.com/Marco-9456/AniSync) (siehe [docs/UPSTREAM.md](docs/UPSTREAM.md)).

## Funktionen

- **Mehrere Accounts**: beliebig viele Codex- und Claude-Code-Accounts, auch mehrere desselben
  Anbieters. Accounts lassen sich umbenennen, erneut anmelden und trennen; dasselbe Konto wird
  beim erneuten Verbinden erkannt und nicht doppelt angelegt. Tokens liegen je Account
  verschlüsselt im Android Keystore.
- **Startseite**: eine Karte pro Account mit 5-Stunden- und Wochenfenster. Der Reset steht als
  Uhrzeit („Reset 17:40 Uhr“) mit Restzeit darunter („in 2 Std. 43 Min.“). Die Reihenfolge ändert
  der **Sortiermodus** (Ziehen am Griff oder Pfeile); sie gilt auch für das Widget.
- **Statistiken**: reine Nutzung, ohne Tokens, Credits oder Kosten. Woche, Monat (Kalender) und Jahr
  (Heatmap), pro Account oder gesamt, als 5-Stunden-Limit oder Wochenlimit. Dazu Resets, aktive
  Tage, bester Tag, Serie und die Auswertung, an welchem Wochentag am meisten verbraucht wird.
  Wie gezählt wird, steht in [docs/USAGE-TRACKING.md](docs/USAGE-TRACKING.md).
- **Widget**: pro Widget einstellbar sind Accounts und deren Reihenfolge, Darstellung (Balken,
  Kompakt, Prozent, Ringe), Zeitfenster, Reset-Anzeige (Uhrzeit, Restzeit oder beides), Farben
  (App, Ampel, Android-Farben), Hintergrund, Deckkraft, Ecken und Schriftgröße. Antippen öffnet
  die App, der Knopf oben rechts liest sofort neu.
- **Navigation**: Statistiken und Resets lassen sich ausblenden und umsortieren, die Startseite
  ist immer sichtbar. Beim vollständigen Neustart öffnet die App die zuletzt genutzte oder eine
  feste Seite; aus dem Hintergrund zurück bleibt sie, wo sie war.
- **Anmeldung** für beide Anbieter über eine gleich aufgebaute Seite.
  Codex nutzt den Gerätecode von OpenAI, Claude die Browser-Freigabe mit einmaligem Code
  ([Details](docs/CLAUDE-PHONE-LOGIN.md)). Ein laufender Codex-Versuch übersteht auch einen
  Neustart der App.
- **Resets**: Zeitplan der nächsten Resets aller Accounts, öffentliche Schätzungen und Verlauf
  (codex-reset.com, LunarWerx, Community-Tracker), getrennt von den persönlichen Konten.
- **Benachrichtigungen**: Hinweis zum gemeldeten Reset-Zeitpunkt, getrennt für 5 Stunden
  und Woche, für jeden Account einzeln geplant.

## Aktualisierung

Der Aktualisieren-Knopf liest immer neu beim Anbieter. Beim Öffnen der App werden nur
Werte gelesen, die älter als zwei Minuten sind. Im Hintergrund liest die App im gewählten
Intervall (1 bis 12 Stunden) sowie kurz vor und nach jedem Reset, damit der Höchstwert eines
Zyklus nicht verloren geht. Bei HTTP 429 wartet die App die vom
Anbieter genannte Zeit; bei Fehlern bleibt die letzte Messung sichtbar.
Die Verbrauchsendpunkte beider Anbieter sind inoffiziell und können sich ändern.

## Bauen

JDK 17, Android SDK Platform 37, Build Tools 37.0.0:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Die CI lädt zwei APKs hoch: `AiUsage-debug` und `AiUsage-performance` (R8-optimiert, nicht
debuggbar, für den Alltag). Beide sind mit demselben öffentlichen Test-Schlüssel signiert,
lassen sich also übereinander installieren, und sind kein Play-Store-Release.
Lokal: `./gradlew assemblePerformance`.

## Aufbau

- `account/`: Account-Liste, eine Laufzeit pro Account, Anmeldungen, öffentliche Reset-Daten
- `data/`: Anbieter-Clients, Parser, Account-Registry, Nutzungs-Ledger, verschlüsselter Speicher
- `background/`: periodischer Abruf, Reset-Ticks und Reset-Benachrichtigungen
- `widget/`: Renderer, Provider und Konfiguration des Homescreen-Widgets
- `ui/`: Navigation, Seiten und gemeinsame Komponenten

GPL-3.0, siehe [LICENSE](LICENSE). Inoffizielle App, unabhängig von OpenAI und Anthropic.
