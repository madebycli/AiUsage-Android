# Wie die Nutzung gezählt wird (ab 2.0)

Grundlage ist nur, was der Anbieter meldet: wie viel Prozent des 5-Stunden- und des Wochenlimits verbraucht sind und
wann das Fenster zurückgesetzt wird. Keine Tokens, Credits oder Kosten.

## Zyklen

Ein **Zyklus** ist ein Durchlauf eines Fensters bis zu seinem Reset. Er wird am gemeldeten Reset-Zeitpunkt erkannt
(Toleranz 5 Minuten). Jeder 5-Stunden-Zyklus wird einzeln gespeichert mit Ende, Höchstwert, erster und letzter Messung
und ob sein Ende gesehen wurde.

- Ein Zyklus zählt mit seinem **höchsten Wert**, einmal. Zwanzigmal 60 % im selben Zyklus sind 60 %, nicht 1200 %.
- Gleich alte oder ältere Messungen werden verworfen (doppelte Abrufe, parallele Worker).
- Ein 5-Stunden-Zyklus zählt am **lokalen Tag seines Beginns** (Reset minus 5 Stunden). Ein Fenster von 22:00 bis
  03:00 gehört zum ersten Tag.
- Zwei 5-Stunden-Zyklen eines Accounts überschneiden sich nie; ein Tag kann höchstens fünf beginnen. Ein Test prüft
  das mit zufälligen Abruffolgen inklusive Duplikaten und verspäteter Antworten.
- Wochenverbrauch eines Tages ist der Anstieg des Wochenwerts zwischen zwei Messungen desselben Wochenzyklus.

## Reset

| Beobachtung | Folge |
| --- | --- |
| Anderer Reset-Zeitpunkt gemeldet | alter Zyklus endet (vorzeitig), Reset am Tag der Messung |
| Reset-Zeitpunkt des alten Zyklus vorbei | alter Zyklus endet zu diesem Zeitpunkt |
| Kein Zeitpunkt gemeldet, Wert fällt um mehr als 1 Punkt | neuer Zyklus |
| Fenster fehlt in der Antwort, Zeitpunkt noch nicht erreicht | nichts |
| Fenster steht auf 0 % | kein Zyklus, der Tag gilt als gemessen |

## Wenn die App etwas nicht gesehen hat

Gezählt wird nur Gemessenes. Damit möglichst nichts fehlt, liest die App im gewählten Intervall (höchstens 5 Stunden,
also wird jeder Zyklus mindestens einmal gesehen) und **3 Minuten vor dem Ende jedes laufenden Fensters** über
AlarmManager. Mit „exakten Weckern“ hält Android diesen Termin auch im Ruhemodus ein, sonst bis zu 10 Minuten früher.

- Ein beendeter Zyklus, dessen letzte Messung mehr als 15 Minuten vor seinem Ende lag, gilt als **„mindestens“**.
  Statistik und Tagesansicht markieren das.
- Steigt der Wochenwert über eine solche Lücke stärker, als die gesehenen 5-Stunden-Zyklen erklären, zeigt die
  Statistik den Rest getrennt als **„≈ … ungesehen“**. Das Verhältnis „Wochenpunkte pro vollem 5-Stunden-Limit“ wird
  pro Account aus Messpaaren gelernt, in denen beide Fenster im selben Zyklus liefen (mindestens 150 Fünf-Stunden-
  und 3 Wochenpunkte, nie bei einem Wochenwert ab 99 %). Unter einem Viertel Limit pro Tag gilt der Unterschied als
  Rundung. Gemessene Werte werden nie durch die Schätzung ersetzt.

## Daten aus 0.9

Die Tageswerte aus 0.9 bleiben als früherer Verlauf. Ein beim Update laufender Zyklus wird übernommen und zählt nur
noch mit dem Teil über dem, was 0.9 bereits gezählt hatte. Keine Neuanmeldung nötig.

## Grenzen

- Die Endpunkte der Anbieter sind inoffiziell.
- Android kann Hintergrundarbeit trotz allem verzögern; das Protokoll unter Einstellungen → Hintergrund zeigt jeden Abruf.
