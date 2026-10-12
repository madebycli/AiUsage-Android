# AI Usage 0.3.0

## Ziel und Umfang

Nur Codex und Claude Code. Native Android-App ab Android 8, Kotlin/Compose.
Hauptseite: kompakte Anbieter-Banner, ausschließlich für verbundene Accounts.
Tap öffnet Zeitfenster, Modelllimits und Zusatzinformationen. Separate
Anmeldeseite, keine Demo oder Quota-Anzeige ohne Anmeldung. Eigene weiche
Lavendel-/Pfirsich-Palette, animierte Anzeigen und kompakte Navigation,
inspiriert von ArchiveTune. Keine ArchiveTune-Markenassets übernommen.

## Claude Code

Import einer Claude-Code-Zugangsdaten-Datei (`claudeAiOauth`) oder eines
OAuth-Access-Tokens. Abo-Typ und 5x/20x-Tier aus den Zugangsdaten;
5h-/Wochenfenster, gemeldete `seven_day_*`-Modelllimits und `weekly_scoped`
Limits, Zusatzverbrauch mit gemeldeter Währung/Minor-Unit-Skalierung,
Reset-Guthaben und Ablaufdaten aus dem Account-Endpunkt.
Fehlende Angaben bleiben unbekannt. Nur `usable_now` und nicht pausierte
Grants zählen als verfügbar. Keine automatische Einlösung.

Das Referenzprojekt ai-usagebar liest den bestehenden CLI-Login; es liefert
keinen mobilen Browser-OAuth-Login. AI Usage implementiert deshalb den Import.
Ein API-Key ist keine Abo-Anmeldung. Der Token benötigt Zugriff auf die
Profil-/Verbrauchs-Endpunkte. Ein nur für Inferenz ausgestellter Token kann
abgelehnt werden. macOS legt Claude-Code-Zugangsdaten teils im Keychain ab;
die Windows/Linux-Datei ist dort nicht zwangsläufig vorhanden.

Die App speichert nur den Access-Token, Ablauf und Plan verschlüsselt.
Importierte Refresh-Tokens werden verworfen, damit die Android-App keine
rotierende Anmeldung des Computers ungültig macht. Nach Ablauf neu importieren.
Desktop-only Session-/Tokenkosten benötigen lokale CLI-Logs und sind nicht
über die Account-Quota-API verfügbar. Keine erfundenen Token-/Dollarzahlen.
Die App nennt ihren eigenen User-Agent; wird ein Reset-Grant an eine
CLI-Kennung gebunden, kann dieser Block fehlen. Keine offizielle API-Garantie.

## Reset Radar

Untertabs Codex / Claude, nur verbundene Anbieter. Account-Guthaben und
Community-Meldungen bleiben getrennt. Keine Wahrscheinlichkeiten mitteln.

| Quelle | Anbieter | Horizont | Zugang | Mindestabstand |
| --- | --- | --- | --- | --- |
| codex-reset.com | Codex | 24h/48h | dokumentierte JSON-API | 60s |
| LunarWerx | Codex | kurzfristig/24h, Intervalle | dokumentierte JSON-API | 300s |
| codexreset.vercel.app/en | Codex | 48h + Verlauf | sichtbares HTML | 1800s |
| codexreset.vercel.app/en/claude | Claude | 48h + Verlauf | sichtbares HTML | 1800s |
| claude-resets.net | Claude | 7 Tage + Verlauf | sichtbares HTML | 1800s |

HTML-Adapter sind empfindlicher als dokumentierte APIs. Änderungen zeigen
Fehler und behalten die letzte erfolgreiche Messung mit ihrem alten Zeitpunkt.
Kein verdeckter API-Endpunkt wird angenommen. Quellenlinks sind direkt an den
Daten sichtbar; Quellenstand und Abrufzeit werden getrennt gezeigt.
Nicht gemeldeter Quellenstand bleibt unbekannt. Bereits vergangene
„expected“-Termine werden als vergangene Schätzungen markiert.

## Architektur, Sicherheit und Betrieb

Eigenständige Claude-, Forecast- und Community-Parser/Repositories; kein
Backend, keine Analytics, keine laufenden Infrastrukturkosten. Anbieter haben
getrennte Keystore-Namensräume und Abmeldung. Neue persönliche Abfragen bei
jedem Refresh, Anbieter-429 wird respektiert. Quellen haben getrennte Backoffs;
Fehler einer Quelle entfernen keine Daten anderer Quellen. Session-Guards
verhindern späte Daten nach Abmeldung. Netzwerkantworten und Dateiimporte sind
begrenzt; keine Tokens oder Antwortinhalte in Logs. Stabile öffentliche
Debug-Testsignatur ab 0.3, ausdrücklich ungeeignet für Produktions-Releases.
0.2 hat eine andere Signatur und muss vor 0.3 deinstalliert werden.

## Prüfung auf einem Gerät

1. 0.2 deinstallieren (lokale Account-Daten gehen verloren), 0.3 installieren.
2. Ohne Login: nur Anmeldeseite, kein Demo-/Dashboard-/Reset-Tab.
3. Codex verbinden; ein Codex-Banner erscheint, Claude bleibt verborgen.
4. Claude-Datei oder geeigneten OAuth-Token separat importieren.
5. Beide Banner öffnen und Account-Werte mit den Anbieter-Einstellungen vergleichen.
6. Reset Radar: Anbieter-Untertabs, passende Quellen und korrekte Horizonte prüfen.
7. Flugmodus: Refresh schlägt sichtbar fehl, Messzeiten bleiben unverändert.
8. Einen Anbieter trennen: sein Banner und seine öffentlichen Daten verschwinden,
   der andere Anbieter bleibt verbunden. Letzten Anbieter trennen: Anmeldeseite.

Echte Claude-/Codex-Accounts und mobile Netzwerkbedingungen müssen praktisch
nachgetestet werden. Automatische Parser-/HTTP-/UI-Prüfung ersetzt das nicht.
