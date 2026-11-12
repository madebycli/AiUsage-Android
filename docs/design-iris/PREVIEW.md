# Signierte Preview · 0.9.2-preview

Die Variante `preview` übernimmt die Release-Konfiguration mit R8 und Ressourcenverkleinerung. Sie ist nicht debuggbar.

- Anzeigename: **AI Usage Preview**
- Paket-ID: **com.madebycli.aiusage.preview**
- Version: **0.9.2-preview (13)**
- Preview-Kennzeichnung: `BuildConfig.PREVIEW = true`, Manifest-Metadatum `com.madebycli.aiusage.PREVIEW = true` und Versionssuffix.
- Eigener privater Preview-Schlüssel; weder der öffentliche Debug-Testschlüssel noch ein Ersatz für den offiziellen Release-Schlüssel.

Die Preview lässt sich parallel zur bisherigen App installieren. Accounts und Einstellungen werden aufgrund der eigenen Android-App-ID getrennt gespeichert. Für spätere Preview-Updates muss derselbe Preview-Schlüssel wiederverwendet werden.

## Lokal bauen

Vorhandenen privaten Keystore (Alias `aiusage-preview`) und die Datei mit dessen Passwort außerhalb des Repositorys bereitstellen:

```sh
export AIUSAGE_PREVIEW_KEYSTORE_PATH=/private/path/preview.p12
export AIUSAGE_PREVIEW_PASSWORD_FILE=/private/path/store.password
./gradlew :app:assemblePreview :app:lintPreview --max-workers=2
```

Fehlen die Signierparameter, bricht `verifyPreviewSigning` den Preview-Build ab. Es gibt keinen stillen Rückfall auf eine unsignierte APK oder den öffentlichen Testschlüssel. Schlüssel und Passwort werden nicht ins Repository oder in APK-Artefakte übernommen.

Der offizielle `release`-Build behält seine bisherige Signierung über `ANDROID_KEYSTORE_PATH`. Der Debug-Build und die untere Menüleiste bleiben unverändert. Es wird kein GitHub-Release erstellt.

## Fertiges Artefakt und Prüfung

[Signierte APK herunterladen](../../apk/AiUsage-0.9.2-preview-signed.apk) · [SHA-256 und Zertifikat](preview-apk.json)

- Release-Optimierung und Ressourcenverkleinerung erfolgreich (`assemblePreview`).
- `lintPreview`: 0 Fehler, 58 Warnungen.
- APK-Signatur v2, eigener Preview-Signer, Paket-ID, Launcher-Aktivität und Preview-Metadatum geprüft.
- Nicht debuggbar; ZIP-CRC und mitgelieferte Orion-Lizenz geprüft.
- Der Build ohne Signierparameter wurde zusätzlich geprüft und wie vorgesehen abgewiesen.
- Kein Test auf einem angeschlossenen Android-Gerät; keine Live-Anmeldung oder Geräteabnahme dieser minifizierten APK.
