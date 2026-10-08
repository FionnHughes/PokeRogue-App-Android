# PokéRogue desktop (Linux and Windows)

The online game in its own window, with:

- **Side panel** (Tab, changeable): quick toggles, the same tools as the phone app
  (PokéRogue Wiki, Pokedex, Type Calculator, Team Builder, Smogon, Type Chart),
  run history sharing, reload and updates.
- **A key per tool**: the pencil beside a tool, then the key. Keys the game uses
  are refused unless held with Ctrl or Alt. Keys do nothing while a text field
  has focus.
- **Run history sharing** with the phone app through the store at
  `fionnhughes.dev/pr/h/` (same code as the phone).
- **Updates** from `fionnhughes.dev/pr/d/latest.json`. The AppImage and the
  Windows installer update themselves; the tar.gz copy points to the download.

Other keys: F11 full screen, F5 or Ctrl+R reload, F12 developer tools, Esc closes
the panel or the tool sheet.

Built by `.github/workflows/desktop.yml`. Grew out of Admiral-Billy's
[Pokerogue-App](https://github.com/Admiral-Billy/Pokerogue-App) (MIT,
`LICENSE-Admiral-Billy`): the tool list, icons and type charts come from there.

Development: `npm ci`, `npm test`, `npm start`.
