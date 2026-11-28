# Widgets (3.0, The Widget Update)

Vier Widgets, jedes einzeln in der Widget-Liste des Launchers. Alle zeichnen mit denselben Daten wie die App, ohne
Netzwerkzugriff; neu gelesen wird im Hintergrund oder über den Aktualisieren-Knopf.

| Widget | Zeigt | Tippen öffnet |
| --- | --- | --- |
| Nutzung | 5-Stunden- und Wochenlimit je Account, Reset-Zeit | den Account (bei einem Account) oder die Startseite |
| Statistik | Verbrauch der letzten 7, 14 oder 28 Tage, Heute, Ø pro Tag, Serie, Anteil je Account | Tab Statistik |
| Planer | Account für jetzt mit freier Woche, danach die nächsten, auf großen Widgets die Woche mit Vorschlägen und Resets | Tab Planer |
| Resets | nächster Reset groß mit Restzeit, darunter die folgenden mit abgelaufenem Anteil des Fensters, gespeicherte Resets | Tab Resets |

## Größen

Jedes Layout passt sich an, von 2×1 bis 5×4. Regeln:

- Text wird nicht abgeschnitten, solange es eine kürzere Form gibt (z. B. „morgen 17:00“ → „Sa. 17:00“ → „17:00“ nur am selben Tag).
- Das Nutzungs-Widget gibt die Höhe zuerst den Formen; die Kopfzeile erscheint ab 150 dp Höhe. Accounts, die nicht
  passen, stehen als „+N“ in der Kopfzeile oder in der ersten Kachel.
- Ein Stil, der nicht einmal eine Kachel fasst, wird in dieser Größe als „Kompakt“ gezeichnet; die Einstellungen sagen das.

## Technik

- Gezeichnet wird mit `Canvas` in ein Bitmap (`WidgetRenderer`), damit Formen, Diagramme und Schrift genau wie in der
  App aussehen. Große Widgets werden mit geringerer Dichte gezeichnet, um das Speicherlimit für RemoteViews einzuhalten.
- Die Formen sind Material-3-Expressive-Formen (`MaterialShapes`), wie auf der verspielten Startseite.
- Einstellungen pro Widget in den Preferences `widgets` (`config.<id>`), JSON; Einstellungen aus 2.0 werden gelesen.
- Tests: `WidgetRendererTest` zeichnet jeden Typ und Stil in allen gängigen Größen nach `app/build/screenshots/widget`.
  Die Vorschaubilder für die Widget-Liste (`res/drawable-nodpi/widget_preview*.png`) erzeugt derselbe Test.
