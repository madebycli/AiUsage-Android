# Wie die Nutzung gezählt wird

Die Statistik besteht nur aus dem, was der Anbieter als Verbrauch meldet: wie viel Prozent des
5-Stunden- und des Wochenlimits verbraucht sind und wann das Fenster zurückgesetzt wird. Es gibt
keine Tokens, Credits oder Kosten.

## Läufe statt Messungen

Ein **Lauf** ist ein Reset-Zyklus eines Fensters. Er wird über den gemeldeten Reset-Zeitpunkt
erkannt (Toleranz 5 Minuten). Jede Messung wird in ihren Lauf einsortiert. Zur Nutzung zählt nur
der Teil über dem bisher höchsten Wert dieses Laufs. Dadurch gilt:

- Wer in einem Lauf fünfmal 99 % sieht, hat 99 % verbraucht, nicht fünfmal.
- 50 % zehn Minuten nach einem Reset und 80 % eine Stunde später sind zusammen 80 %, nicht 130 %.
- Sinkt der Wert im selben Lauf (Rundung, Fehlmeldung), wird nichts abgezogen und nichts doppelt
  gezählt.

## Wann ein neuer Lauf beginnt (Reset)

| Beobachtung | Folge |
| --- | --- |
| Der gemeldete Reset-Zeitpunkt ist ein anderer | Alter Lauf endet; Reset zählt am Tag der Messung (vorzeitiger Reset, zum Beispiel eingelöster Reset). |
| Der Reset-Zeitpunkt des alten Laufs ist vorbei | Alter Lauf endet; der Reset zählt am Tag des Zeitpunkts, auch wenn die App tagelang zu war. |
| Kein Reset-Zeitpunkt gemeldet, Wert fällt um mehr als 1 Punkt | Neuer Lauf. |
| Das Fenster fehlt in der Antwort, der Zeitpunkt ist noch nicht erreicht | Nichts passiert, kein Reset. |
| Fenster steht auf 0 % (auch mit wanderndem Zeitpunkt) | Kein Lauf, keine Nutzung. Der Tag gilt als gemessen. |

Gleiche oder ältere Messungen (zum Beispiel die Anreicherung derselben Abfrage oder zwei Worker
gleichzeitig) werden ignoriert.

## Tage

Die Nutzung eines Zeitraums zwischen zwei Messungen wird dem lokalen Tag der späteren Messung
zugeordnet. Ein Tag mit mindestens einer Messung gilt als **gemessen**: 0 ist dann eine echte Null.
Tage ohne Messung bleiben unbekannt und zählen weder als Null noch in Wochentags-Durchschnitten.

Pro Tag werden gespeichert: verbrauchte Punkte im 5-Stunden- und im Wochenfenster (100 Punkte im
5-Stunden-Fenster sind ein volles Limit), gestartete Läufe, Resets, höchster 5-Stunden-Wert,
Zahl der Messungen. Bis zu 800 Tage bleiben erhalten. Der alte Verlauf (nur der höchste Wert
eines Tages) wird einmalig als Untergrenze übernommen und als „früherer Verlauf“ markiert.

## Grenzen

- Nutzung, die zwischen der letzten Messung eines Laufs und dem Reset entsteht, fehlt. Deshalb liest
  die App kurz vor jedem Reset noch einmal. Android kann das verzögern.
- Die Messzeit ist der Bezugstag, nicht der Moment der Nutzung.
- Die Endpunkte der Anbieter sind inoffiziell und können sich ändern.
