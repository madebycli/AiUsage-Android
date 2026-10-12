# Claude-Verbrauch nach erfolgreicher Anmeldung · 0.4.1

Gerätereport vom 12.10.2026: Screenshot zeigt Codex und Claude als verbunden,
Claude meldet nach Code-Eingabe „Das Datenformat des Anbieters hat sich geändert“.
Das beigefügte Log enthält Codex-Phasen bis SAVING/LOADING_USAGE und temporäre
Polling-Netzwerkfehler, keinen Crash und keine Claude-Response/Parserdiagnose.
Das genaue auslösende Feld des privaten Accounts lässt sich daraus nicht belegen.

## Belegte Fehler und Änderungen

- 0.4.0 akzeptiert nur ganzzahlige `extra_usage.used_credits`/`monthly_limit`.
  Ein korrekter Wert wie 434.43 wirft SchemaException und verhindert damit auch
  vorhandene Zeitfenster. Referenz: CodexBar `OAuthExtraUsage` (Double) und
  `UsageStoreSessionQuotaTransitionTests.swift` (434.43); subswap dokumentiert
  ebenfalls den Wechsel von Integer- zu Dezimalwerten. 0.4.1 akzeptiert endliche,
  nichtnegative Nachkommastellen, ohne fehlende Beträge als null Verbrauch zu erfinden.
- Optionaler Zusatzverbrauch, Reset-Inventar, einzelne Modellfenster oder
  Reset-Zeitstempel blockieren keine unabhängig gültigen Quotas mehr.
  Unlesbare Angaben bleiben unbekannt und erhalten einen Hinweis. Sind alle
  gelieferten Quotas ungültig oder ist die Antwort überhaupt nicht lesbar,
  bleibt der Abruf ein Fehler, keine künstliche 0-Prozent-Messung.
- `limits[]` mit `kind=session`/`weekly_all` ergänzt die belegte neue Struktur,
  wenn Legacy-Fenster fehlen. Keine doppelten Fenster oder Prozent-Hochskalierung.
- Persönlicher Reset-Vorrat benötigt bestätigte Eligibility und lesbare
  Verfügbarkeit. Pausierte, zukünftige und abgelaufene Grants zählen nicht;
  eine unlesbare oder unvollständige Antwort ist niemals null verfügbare Resets.
- Keine erfundene USD-Währung bei fehlenden Geld-Metadaten. Angegebene Beträge
  bleiben sichtbar als gemeldete Einheiten; Umrechnung nur mit Währung und Skala.
- Nach erfolgreichem Browser-Code-Austausch öffnet die App Claude-Details.
  Anmeldung und geladene Verbrauchsdaten haben getrennte Statusmeldungen.
  Bei Lesefehlern kann man die gespeicherte Anmeldung behalten und neu abrufen.
- Sichere Logcat-Diagnose für `claude_auth`/`claude_usage`: Fehlerklasse,
  numerischer HTTP-Status und feste RESPONSE/WINDOWS-Kategorie. Keine Tokens,
  Codes, URLs, Antwortbodies oder fremden JSON-Feldnamen.

Primärreferenzen:
[CodexBar](https://github.com/steipete/CodexBar),
[subswap-Datenformat](https://github.com/x0c/subswap/blob/main/docs/PROVIDER_KNOWLEDGE_BASE.md).
Eigenständige Implementierung, keine zusätzlichen Abhängigkeiten oder Server.

## Praktischer Gerätetest

0.4.1 über 0.4.0 installieren, vorhandene Accounts bleiben gespeichert.
Claude auf der Hauptseite öffnen und oben aktualisieren. Wegen des im Screenshot
bestätigten gespeicherten Logins ist eine erneute Code-Eingabe zunächst unnötig.
5h-/Wochenwerte und Reset-Zeiten mit Claude vergleichen. Zusatzverbrauch oder
Reset-Hinweise dürfen gültige Quotas nicht mehr unterdrücken. Falls weiterhin
ein Fehler kommt, nur die neuen `AiUsage`-Diagnosezeilen und den sichtbaren
Fehlertext prüfen; keine Account-Tokens oder privaten API-Antworten anfordern.

MockWebServer/Robolectric prüfen Dezimalbeträge nach Login, beschädigte optionale
Blöcke, null-Felder, strukturierte Limits, fehlende Geld-Metadaten, sichere
Diagnose, Bestandsdaten, neue Login-Navigation und teilweise geladene Werte.
Private Accountwerte wurden nicht in dieser Arbeitsumgebung abgerufen.
Endgültige Farben, untere Tabs, Kartenrundungen und Debug-Update-Signer bleiben
aus 0.4.0 erhalten. Version 0.4.1/Code 5, Android 8+, SDK 37.
