# 0.7.1: Nutzungsstatistiken als optionale Beta

Kalender, zusätzliche Codex-Account-/Cloud-Berichte, Claude-Dateiimport und
Organisations-API-Statistiken sind standardmäßig ausgeschaltet, auch nach einem
Update von 0.7.0. Einschalten: Einstellungen → Beta-Funktionen →
Nutzungsstatistiken (Beta). Verfügbarkeit hängt vom Account und Datenweg ab;
dies ist keine vollständige Codex-Cloud- oder Claude-Gesamthistorie.

Ausgeschaltet verschwinden Kalender und Statistik-Einstiege für beide Anbieter.
Es laufen keine zusätzlichen Statistik-, Profil- oder API-Berichtsabfragen.
Deaktivieren beendet Statistikjobs und verlässt offene Statistik-Unterseiten.
Bereits laufende Hintergrundantworten werden anhand einer Opt-in-Revision
verworfen, auch nach erneutem Einschalten. Vorhandene verschlüsselte Berichte
und freiwillig verbundene API-Keys bleiben erhalten; Abmelden löscht sie weiter.

Persönliche 5-Stunden-/Wochenlimits, Resets, Forecasts und deren Einstellungen
bleiben verfügbar. Der Vierstunden-Accountjob fragt weiter nur die Limits ab;
den kleinen Codex-Tagesprofilbericht zusätzlich ausschließlich bei aktiver Beta.
Android-Berechtigungen und Zustellungsgrenzen bleiben wie in 0.7.0.

Version 0.7.1 / Code 9, gleicher Update-Signer und gleiche Accountspeicherung.
