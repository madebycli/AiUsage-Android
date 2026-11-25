# Übersetzen

Alle sichtbaren Texte liegen in Android-String-Ressourcen:

| Datei | Sprache |
| --- | --- |
| `app/src/main/res/values/strings.xml` | Englisch, Ausgangssprache |
| `app/src/main/res/values-de/strings.xml` | Deutsch |

Mehrzahl über `<plurals>`, Listen über `<string-array>` (Wochentage, Monate). Texte mit `translatable="false"` bleiben
unübersetzt. Platzhalter wie `%1$s` müssen in jeder Sprache erhalten bleiben.

Eine neue Sprache: `values-xx/strings.xml` anlegen und `xx` in `app/src/main/res/xml/locales_config.xml` eintragen.
Ab Android 13 lässt sich die Sprache dann in der App wählen (Einstellungen → Erscheinungsbild).

## Weblate

Komponente anlegen mit:

- Dateiformat: *Android String Resource*
- Dateimaske: `app/src/main/res/values-*/strings.xml`
- Einsprachige Ausgangsdatei: `app/src/main/res/values/strings.xml`
- Vorlage für neue Übersetzungen: `app/src/main/res/values/strings.xml`
- Sprachfilter: `^[a-z]{2}(-r[A-Z]{2})?$`

`lintDebug` meldet fehlende Übersetzungen und falsche Platzhalter.
