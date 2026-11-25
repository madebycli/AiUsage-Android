# Planer

Der Planer rechnet nur mit gemeldeten Werten und mit dem, was die App gemessen hat.

## Reihenfolge

1. Was jetzt nutzbar ist (5-Stunden-Fenster nicht voll, Woche nicht aufgebraucht).
2. Davon zuerst der Account, dessen Wochenreset am nächsten ist: was dort übrig ist, verfällt zuerst.
3. Bei gleichem Reset (innerhalb einer Stunde) der mit mehr Rest.

## Vorgeschlagene Fenster

- Zeitfenster beginnen jetzt und zu deinen üblichen Startzeiten: den bis zu drei häufigsten Startstunden der letzten
  28 Tage, mindestens 5 Stunden auseinander. Mit weniger als sechs gemessenen Fenstern gelten 09, 14 und 19 Uhr.
- Jedes Fenster bekommt der Account mit dem frühesten Wochenreset, der zu dem Zeitpunkt frei ist und noch mindestens
  ein Viertel Limit übrig hat.
- Wie viele volle 5-Stunden-Fenster das Wochenbudget erlaubt: verbleibende Wochenprozent geteilt durch die gelernten
  Wochenpunkte pro vollem 5-Stunden-Limit ([USAGE-TRACKING.md](USAGE-TRACKING.md)). Ohne dieses Verhältnis schlägt
  der Planer keine Fenster vor.
- Nach einem Wochenreset füllt sich das Budget wieder.
- Was bei einem Reset in den nächsten sieben Tagen übrig bleibt, steht als „verfällt ungenutzt“ beim Account.
- Wochenresets mehrerer Accounts innerhalb von 24 Stunden werden als „dicht beieinander“ genannt.
