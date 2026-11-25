# AI Usage 2.0: Plan

Stand 25.11.2026. Ausgangspunkt 0.9.1 (`fb7af4c`).

## 1. Befund im Ist-Stand

Belegt im Code:

- **Statistik zählt zu wenig, nicht zu viel.** Der Ledger von 0.9 zählt pro Reset-Zyklus nur den Anstieg über den
  bisher höchsten Wert. Wiederholtes Aktualisieren erzeugt keinen Doppelzähler mehr (der „20 × 60 %“-Fehler ist in
  0.9 behoben und hat einen Test). Gezählt wird aber nur, was die App *gesehen* hat: Liegt die letzte Messung eines
  5-Stunden-Zyklus bei 30 % und der Zyklus endet bei 100 %, fehlen 70 Punkte. Ein Zyklus, in dem die App gar nicht
  gemessen hat, fehlt ganz. Genau das passt zu „1 statt 4 Limits an einem Tag“.
- **Die Messung kurz vor dem Reset läuft über WorkManager mit langer Startverzögerung** (`ResetTicks`). Im Doze hält
  Android solche Jobs zurück, sie laufen oft erst nach dem Reset. Dann ist der Endwert des Zyklus verloren.
- **Periodischer Abruf** standardmäßig alle 3 Stunden mit 3 Stunden Startverzögerung. Ein 5-Stunden-Zyklus kann
  zwischen zwei Läufen fast vollständig liegen.
- **Tageszuordnung** nach Messzeit statt nach Zyklusbeginn. Ein Zyklus von 22:00 bis 03:00, gemessen um 08:00,
  landet am falschen Tag.
- **Kein Nachweis, ob der Hintergrund läuft.** Es gibt nur „letzter Lauf“; keine Liste der Läufe.
- Alle Texte sind fest im Kotlin-Code (Deutsch). Keine Übersetzungsdateien.

## 2. Datenverarbeitung neu (Ledger v2)

### Zyklen statt Tageswerte als Grundlage

Pro Account und Fenster (5 Std., Woche) wird jeder Zyklus als eigener Datensatz gespeichert:

| Feld | Bedeutung |
| --- | --- |
| `end` | gemeldeter Reset-Zeitpunkt, Schlüssel des Zyklus (Toleranz 5 Min.) |
| `start` | `end − Fensterlänge`; exakt, weil der Anbieter das Fenster ab erster Nutzung zählt |
| `peak` | höchster gemeldeter Wert |
| `firstSeen`, `lastSeen`, `samples` | wann und wie oft gemessen |
| `closedAt`, `early` | wann und ob vorzeitig beendet (globaler Reset, eingelöste Gutschrift) |
| `complete` | letzte Messung lag höchstens 10 Min. vor `end` → Endwert gesichert |

Regeln (jede mit Test):

1. Gleicher Zyklus = gleicher `end` ± 5 Min. `peak = max(peak, neu)`. Wiederholtes Lesen ändert nichts.
2. Ältere oder gleich alte Messungen werden verworfen.
3. Ein 5-Stunden-Zyklus zählt am lokalen Tag seines `start`. Ein Zyklus über Mitternacht zählt am Starttag.
4. Ein Tag kann höchstens so viele 5-Stunden-Zyklen haben, wie zeitlich hineinpassen. Das ist kein Abschneiden,
   sondern eine Invariante, die ein Test über Zufallsfolgen prüft (keine zwei Zyklen überlappen sich).
5. Woche: Verbrauch eines Tages = Anstieg des Wochenwerts zwischen zwei Messungen desselben Wochenzyklus,
   dem Tag der späteren Messung zugeordnet.

### Lücken sichtbar machen statt verschweigen

- Ein Zyklus ohne gesicherten Endwert ist „mindestens x %“.
- Steigt der Wochenwert stärker, als die gesehenen 5-Stunden-Zyklen erklären, ist das unbeobachtete Nutzung.
  Das Verhältnis „Wochenpunkte pro vollem 5-Stunden-Limit“ wird pro Account aus Messpaaren gelernt, in denen
  beide Fenster im selben Zyklus gemessen wurden. Erst mit genug Daten erscheint eine Schätzung, getrennt
  gekennzeichnet (heller Balkenteil, „≈“). Gemessene Werte werden nie durch Schätzungen ersetzt.

### Migration

Die 0.9-Tageswerte bleiben als „früherer Verlauf“ lesbar. Die laufenden Zyklen aus 0.9 werden übernommen.
Keine Neuanmeldung nötig.

## 3. Hintergrund neu

- Periodischer Abruf, Standard **1 Stunde** (15 Min. bis 6 Std. wählbar), ohne Startverzögerung beim ersten Mal.
  Bei ≤ 5 Std. Abstand wird jeder 5-Stunden-Zyklus mindestens einmal gesehen.
- **Messung vor jedem Zyklusende über AlarmManager** (exakt, falls erlaubt, sonst `setAndAllowWhileIdle`), 3 Min.
  vor `end`, danach expedited Work. Dadurch wird der Endwert jedes Zyklus gesichert, auch im Doze.
- Nach jedem Neustart und App-Update wird alles neu geplant (bisher schon für Hinweise, jetzt auch für Messungen).
- **Protokoll**: die letzten 100 Läufe mit Zeit, Auslöser (periodisch, Zyklusende, App, Widget), Ergebnis je Account.
  Sichtbar unter Einstellungen → Hintergrund, zusammen mit Akku-Optimierung und Erlaubnis für exakte Wecker.

## 4. Planer (neuer Tab)

Ziel: zeigen, wann welches Kontingent am meisten bringt.

- **Wochenleiste**: 7 Tage ab heute, je Account eine Spur. Darin: Wochenfenster mit Reset-Marke, verbleibendes
  Wochenbudget als Füllung, aktueller 5-Stunden-Zyklus, Überschneidungen der Resets mehrerer Accounts.
- **Jetzt-Empfehlung**: welcher Account zuerst. Rangfolge nach „Budget, das bis zum Reset verfällt“:
  verbleibende Wochenpunkte ÷ Stunden bis Reset. Wer bald zurückgesetzt wird und viel übrig hat, kommt zuerst.
- **Mögliche 5-Stunden-Sitzungen bis zum Reset**: verbleibende Wochenpunkte ÷ gelerntes Verhältnis aus 2.
- **Vorschlagsblöcke** in den Stunden, in denen du laut Verlauf meist arbeitest (Startzeiten der Zyklen).
- Alles aus echten Werten. Ohne gelerntes Verhältnis zeigt der Planer nur Wochenbudget und Resets.

## 5. Design

- **Einstellungen 1:1 wie Iris**: Hub mit Bento-Kacheln (Orion), Seiten mit getöntem Kopf und drehender
  Material-Form, der mit dem Inhalt wegscrollt (keine stehende Leiste), Kachel wächst in die Seite (shared bounds),
  Öffnen mit Slide ⅓ + Fade, Schließen umgekehrt. Bausteine aus Orion Store (GPL-3.0) mit Quellhinweis.
- **Karten**: die hellen Flächen weg, zurück zu den ruhigen Karten aus 0.8/0.9.0, abgestimmt auf die Iris-Töne.
- **Startseite**: bisheriges Layout bleibt als „Kompakt“. Neu wählbar „Verspielt“: je Account eine morphende
  Material-Form, deren Füllstand der Verbrauch ist, Reset als Uhrzeit darunter.
- **Statistik**: oben Account-Auswahl, darunter *eine* Zeile für Fenster (5 Std. | Woche) und eine für Zeitraum
  (Woche | Monat | Jahr). Zahlenkacheln, Diagramm, Tagesdetail mit Zyklenliste, Wochentage, Tageszeiten.
- **Resets**: oben persönliche Zeitleiste (nächste Resets aller Accounts), darunter je Anbieter Wahrscheinlichkeit,
  letzter globaler Reset, Verlauf. Kurze Beschriftungen, keine erklärenden Sätze, keine „Keine Zusage“-Hinweise.
- **Vorrat** (gespeicherte Resets): eigene Karte, sortiert nach Ablauf, Zähler groß, Ablauf als Datum.
- Bewegung: Material 3 Expressive Motion-Scheme überall, verspielte Federn, Formen-Morph, gestaffeltes Einblenden.
- Texte: kurz, keine Hand-an-die-Hand-Sätze, keine „·“-Ketten, gleichmäßige Abstände (8/12/16/24 dp).

## 6. Sprachen

- Alle Texte in `res/values/strings.xml` (Englisch, Basis) und `res/values-de/strings.xml` (Deutsch),
  Mehrzahl über `<plurals>`. Kein Text mehr im Kotlin-Code.
- Format ist Android-Ressourcen, das Weblate direkt unterstützt (Komponente „Android String Resource“).
  `docs/TRANSLATING.md` beschreibt die Einrichtung.
- Sprachwahl in der App (Android 13+ per-App-Sprache, `locales_config.xml`).

## 7. Anbieter

- **Codex**: wie bisher, zusätzlich Gutschriften im Vorrat und zusätzliche Limits je Modell sauber getrennt.
- **Claude**: wie bisher, Modell-Wochenlimits (Opus/Sonnet) als eigene Spuren.
- **Gemini**: siehe offene Frage unten. Login mit Code-Einfügen wie bei Claude, dann `loadCodeAssist` und
  Quota-Abfrage. 5-Stunden- und Wochen-Buckets werden auf die gleichen Fenster abgebildet.

## 8. Reihenfolge

1. Ledger v2, Hintergrund v2, Protokoll, Tests (Korrektheit zuerst).
2. String-Ressourcen EN/DE, Sprachwahl.
3. Iris-Einstellungen, Karten, Bewegung.
4. Statistik und Planer.
5. Resets, Vorrat, zweite Startseite.
6. Gemini.
7. Screenshots aller Seiten hell/dunkel (Robolectric, native Grafik), Prüfung, Version 2.0.0.

## 9. Grenzen

- Kein Android-Emulator in dieser Umgebung (`/dev/kvm` fehlt). Geprüft wird mit gerenderten Screenshots der echten
  Compose-Oberfläche. Echte Hintergrundzustellung auf deinem Gerät kann nur das Protokoll aus 3 belegen.
- Die Verbrauchsendpunkte aller Anbieter sind inoffiziell.
