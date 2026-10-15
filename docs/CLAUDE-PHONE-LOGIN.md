# Claude-Anmeldung am Handy

Browser-Freigabe ohne Computer. Optional lassen sich Zugangsdaten vom Computer einfügen.

## Auf dem Handy

1. AI Usage öffnen, **Mit Claude Code verbinden** wählen. Bei bereits verbundenem
   Account: Einstellungen → Anmeldungen → **Neu anmelden**.
2. **Claude im Browser öffnen**. Bei Claude mit Google oder E-Mail anmelden
   und die angezeigte Freigabe bestätigen.
3. Den vollständigen einmaligen Code von Claudes Rückgabeseite kopieren,
   einschließlich `#` und dem folgenden State. Zu AI Usage wechseln, in
   **Anmeldecode** einfügen und **Anmeldung abschließen** wählen.

Die App erhält eine eigene Handy-Anmeldung und fragt die persönlichen Limits
direkt bei Anthropic ab. Sie erneuert nur den Refresh-Token dieser Anmeldung.
Der gemeldete Plan wird bei Anmeldung optional über `/api/oauth/profile`
gelesen (maximal vier Sekunden); fehlende Planangaben bleiben unbekannt.
Ein vorhandener Login in der offiziellen Claude-App wird nicht übernommen.
Tokens/Code niemals in GitHub-Issues oder Chatnachrichten veröffentlichen.

## Grenzen und Quellen

Der Weg ist experimentell. Es gibt keine dokumentierte öffentliche
Drittanbieter-Registrierung für diese Abo-Verbrauchsanzeige. Die Freigabe nutzt
den öffentlichen Claude-Code-Client-Identifier, nicht einen eigenen
AI-Usage-OAuth-Client. Die Freigabe heißt daher beim Anbieter Claude Code.
Anthropic empfiehlt für Drittanbieter-Software API-Key-Authentifizierung;
API-Keys liefern aber nicht die persönlichen Abo-Zeitfenster. Die App bietet
keine Inferenzfunktion und sendet keine Modellanfragen, um Verbrauch zu messen.

Primäre Implementierungsreferenz, gelesen am 12.10.2026:
[G-biggy/claude-pulse-android](https://github.com/G-biggy/claude-pulse-android),
Commit `1f915e257f162052bef6b11e9c189a8e2f32dba7`,
`app/src/main/java/com/ghayyath/claudepulse/TokenManager.kt` und `ApiClient.kt`.
Diese Android-Implementierung verwendet den Hosted-Code-Flow mit
`user:profile user:inference` und direktes `GET /api/oauth/usage`.
Zusätzlich geprüft: [ClaudexBar AUTH](https://github.com/ipangdz/claudexbar/blob/main/docs/AUTH.md),
[offizielle Claude-Code-Anmeldung](https://code.claude.com/docs/en/authentication),
[Anthropic-Anmeldehinweise](https://support.claude.com/en/articles/13189465-log-in-to-your-claude-account).
Profil-Response-Referenz:
[GooddyLife/claude-usage-monitor-plus](https://github.com/GooddyLife/claude-usage-monitor-plus/blob/main/docs/api-reference.md)
(anonymisierte Originalantworten des Autors; unbekannte Felder werden nicht
als Abo-Plan ausgegeben).

Technisch umgesetzt, noch kein erfolgreicher Live-Login mit einem persönlichen
Claude-Account behauptet. Anbieter-Endpunkte, Freigabe und Zugriff können sich
ändern. Scheitert der Austausch, eine neue Anmeldung starten. HTTP 429
respektiert die Wartezeit. Der bisherige Import bleibt als optionale Alternative.
`claude setup-token` besitzt nicht den nötigen Profilzugriff.

## Sicherheit und Lebenszyklus

- Systembrowser, keine eingebettete Login-WebView und keine Cookie-Extraktion.
- Authorization Code + PKCE S256, unabhängige zufällige 256-Bit-Werte für
  Verifier und State. Vollständiger `code#state` wird vor dem Austausch geprüft.
- Anbieter-Rückgabe: `https://platform.claude.com/oauth/code/callback`.
  Keine erfundenen App-Deep-Links und kein offener Loopback-Server.
- Token-Austausch/Refresh: `https://platform.claude.com/v1/oauth/token`.
  JSON-POST ohne Redirects und ohne automatische Transport-Wiederholungen.
- Pending-State/Verifier für höchstens zehn Minuten AES-GCM-verschlüsselt im
  `noBackupFilesDir`. Nach Prozessneustart kann die Code-Eingabe weitergehen.
  Der Versuch wird vor dem einmaligen Austausch gelöscht; unklarer Austausch
  wird nicht automatisch wiederholt. Zurück/Abbruch löscht den Pending-State.
- Zugriff/Refresh getrennt von Codex verschlüsselt. Rotation wird vor Nutzung
  des neuen Tokens gespeichert. Nur `phoneLogin=true` erlaubt Erneuerung.
  Desktop-Import verwirft weiterhin alle kopierten Refresh-Tokens.
- Kein automatisches Lesen der Zwischenablage, kein Speichern des eingefügten
  Codes in Compose Saved State, keine Tokens/Antwortbodies/URLs in Logs.
- Abbruch/Trennen invalidiert die Sessiongeneration. Neue Accounts werden
  zusammen mit leerem Verlauf atomar gegen eine inzwischen abgebrochene
  Session geschützt; ein alter Account-Verlauf wird nicht übernommen.

## Prüfung

MockWebServer-Tests prüfen PKCE/State, Teilcodes/fremde States, Ablauf,
Wiederaufnahme, einmaligen Austausch, Fehler ohne Replay, parallele Rotation,
Legacy-Imports ohne Refresh, abgelehnte Refresh-Ketten und 429-Backoff.
Robolectric prüft die Login-Seite mit Zurück-Pfeil, Eingabefeld, gesperrten
Aktionen während des Austauschs und Neustart nach Fehler. Diese Tests ersetzen keinen echten Gerätelogin.
