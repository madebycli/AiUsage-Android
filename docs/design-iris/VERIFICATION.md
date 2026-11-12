# Prüfung · 12. November 2026

- Vollständiger Lauf: **158 Tests in 17 Suiten**, 0 Fehler, 0 übersprungene Tests; darin **21 Compose-UI-Tests** mit nativer Grafik auf Robolectric API 35. [Zähler je Suite](tests.json).
- Die 21 UI-Tests wurden nach Ergänzung der normalen Einstellungsaufnahme erneut erfolgreich ausgeführt. Die Screenshots stammen aus diesem letzten Lauf.
- Android Lint: **0 Fehler, 58 Warnungen**. Vorhandene Warnungen sind nicht vollständig bereinigt.
- Debug-APK erfolgreich gebaut: **0.9.2 (13)**, Paket `com.madebycli.aiusage`, öffentlicher bestehender Testschlüssel. APK-Signatur v2 gültig, ZIP-CRC geprüft, Orion-Lizenz im APK geprüft. [Größe und SHA-256](apk.json).
- `git diff --check` ohne Befund.
- Live-Prüfung: Beide alten Tracker-Adressen lieferten HTTP 308 zur neuen Domain; beide endgültigen HTTPS-Adressen lieferten HTTP 200. Reale gekürzte Antworten prüfen 48-Stunden-Werte, Zeitstempel und Verlauf. Die allgemeine Schutzregel gegen Weiterleitungen auf andere Domains bleibt unverändert.
- Die Navigationsimplementierung (`UsageApp.kt`), ihre Einstellungen (`NavigationSettings.kt`) und die Tab-Konfiguration (`UiState.kt`) wurden nicht geändert. Bestehende Tests für Sichtbarkeit, Reihenfolge und Startseite bestehen; ein zusätzlicher Test prüft die Rückkehr aus den neuen Einstellungen zur unteren Leiste.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --max-workers=2
./gradlew :app:testDebugUnitTest --tests '*.UiSmokeTest' --max-workers=2
```

Kein Android-Gerät ist angeschlossen, kein Emulator verfügbar. Android-17-Geräteverhalten, Animationsleistung, echte Anmeldung und Hintergrundabrufe auf dem Telefon sind hier nicht abgenommen. Die Test-APK ist ein nicht minifizierter Debug-Build. Keine Veröffentlichung als Release und kein Merge in main.
