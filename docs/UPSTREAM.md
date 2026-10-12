# Herkunft und Änderungen

AI Usage ist eine auf den Verbrauchsmonitor reduzierte Ableitung von
[AniHyou-android](https://github.com/axiel7/AniHyou-android), Autor axiel7.

Geklont am 12.10.2026 von `master`, Commit
`905178700cb43e3196621b22c33161bfbab3beee`.

Übernommene Grundlage:

- `core/ui/.../composables/DefaultScaffold.kt`: große, mittlere und kleine
  Top-App-Bar-Scaffolds sowie Statusleistenschutz. Die große Variante erhält
  einen Bottom-Bar-Slot; Material-Opt-in ergänzt.
- `core/ui/.../composables/common/CommonProgressIndicator.kt`: die animierte
  Material-3-Expressive-Ladeanzeige unverändert, Opt-in ergänzt.
- `core/ui/.../theme/Theme.kt`: Expressive-Theme, Systemfarben und Dunkelmodus
  übernommen und reduziert. AniList-Profilfarben und MaterialKolor entfernt;
  eigene ruhige Salbei-Palette als Fallback, optional schwarzer Hintergrund.
- Gradle-Startskripte und Wrapper-JAR übernommen; Version auf Gradle 8.13
  angepasst. Android/Kotlin-Abhängigkeiten auf die hier gebaute Kombination
  AGP 8.13.2, Kotlin 2.2.21, SDK 36 und Material 3 1.4.0 reduziert.
- Bottom-Navigation aus AniHyous `MainBottomNavBar.kt` für drei AI-Usage-Seiten
  angepasst; Anime-Routen, Ressourcen und Animationsvektoren entfernt.

Nicht enthalten: AniList, GraphQL/Apollo, Anime-/Manga-Datenmodelle, Medienbilder,
Social-Funktionen, Tracking, Wear OS, Widgets, Koin und die fachlichen
Feature-Module. App-ID und Launcher sind eigenständig. Das existierende
GPL-3.0-Lizenzdokument bleibt erhalten. Es wurden keine AniHyou-Markenassets
übernommen.

Protokollreferenz für Authentifizierung und Verbrauch:
[openai/codex](https://github.com/openai/codex), inspizierter Commit
`6e5f665feb9b71e1500a5721036fbdb8e871f4b0`, insbesondere
`codex-rs/login/src/device_code_auth.rs`, `codex-rs/login/src/oauth/client.rs`,
`codex-rs/backend-client/src/client/rate_limit_resets.rs` und
`codex-rs/backend-client/src/types.rs`. Die Implementierung von AI Usage ist
eigenständig; der OAuth-Client ist der öffentliche Codex-Client.

Die Noctalia-Referenz verwendet selbst den CLI-Report von
[ai-usagebar](https://github.com/akitaonrails/ai-usagebar). Android ruft die
Account-Daten direkt ab, ohne Rust-CLI oder Desktop-Credential-Dateien.
