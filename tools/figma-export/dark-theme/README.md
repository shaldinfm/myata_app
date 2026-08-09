# Radio Myata · Dark Screens — локальный Figma development-плагин

Плагин создаёт редактируемые dark-версии утверждённых экранов Radio Myata на отдельной странице `CURRENT ANDROID UI — DARK`.

Он не использует Figma MCP, сеть, Gradle и не изменяет Android-код, XML, ресурсы или архитектуру. Существующие Figma-страницы и компоненты не трогаются: при повторном запуске пересоздаются только слои с маркером этого плагина на его собственной странице.

## Запуск в Figma Desktop

1. Открой Figma Desktop и нужный файл Radio Myata.
2. Выбери `Plugins` → `Development` → `Import plugin from manifest…`.
3. Укажи файл `tools/figma-export/dark-theme/manifest.json`.
4. Запусти `Plugins` → `Development` → `Radio Myata · Dark Screens`.
5. Нажми `Создать dark-фреймы`.
6. В диалоге плагина появятся node id и ссылки на созданные dark-фреймы; они ведут на текущий открытый Figma-файл.

Опционально можно выбрать локальные PNG/JPG/WebP обложки перед запуском. По умолчанию плагин использует подготовленные отдельные image fills из `../assets/real-artwork/` для текущего трека, плейлистов и сохранённых треков; это кропы из приложенных эталонных PNG, а не целые экраны. Social logos берутся из Android `*_info.png`, а `*_banner_new.xml` конвертируются в редактируемые векторные баннеры.

## Проверка

Сборка не требуется: `code.js` — dependency-free JavaScript для Figma Plugin API. Для синтаксической проверки в локальном Node.js:

```powershell
node --check tools/figma-export/dark-theme/code.js
Get-Content tools/figma-export/dark-theme/manifest.json | ConvertFrom-Json
```

## Локальные visual previews

Скрипты ниже не меняют Android-проект. Они создают PNG-превью экспортируемых dark-экранов в `previews/` в масштабе 100%:

```powershell
# NODE_PATH нужен только если puppeteer установлен не локально в проекте:
# $env:NODE_PATH='<путь к вашей глобальной папке node_modules>'
node tools/figma-export/dark-theme/build-dark-assets.mjs
node tools/figma-export/dark-theme/render-previews.mjs
```

`build-dark-assets.mjs` вырезает обложки из reference-скриншотов в
`../assets/real-artwork/`. Эти файлы **не хранятся в репозитории** (чужие
обложки треков) — см. `assets/real-artwork/SOURCES.md`. Без них плагин
использует цветовые fallback-слои, а готовые превью уже лежат в `previews/`.

`render-previews.mjs` использует установленный локально Chrome только для рендера статического `preview.html`; в Figma и Android он ничего не записывает.

Структура экранов, токены, размеры и approved-состояния зафиксированы в `dark-screens.json`. Все основные элементы создаются как редактируемые FrameNode/TextNode/RectangleNode/EllipseNode/VectorNode/ComponentNode; повторяющиеся элементы используют локальные компоненты и instances. Минимальная touch target интерактивных элементов — 48×48.

## Ограничения

Фактическая загрузка Muller зависит от шрифтов, установленных и доступных в конкретном Figma-файле. Плагин сначала проверяет `listAvailableFontsAsync`; если Muller недоступен, текстовые слои получают суффикс `[Inter fallback: Muller unavailable]`, а в отчёте плагина это отмечается. Плагин также создаёт локальную Variable Collection `Radio Myata / Semantic` с modes `Light` и `Dark`, создаёт paint styles и применяет Dark mode к собственной странице.

Конкретные Figma-ссылки нельзя достоверно сформировать до ручного запуска плагина в Figma Desktop: только Figma Plugin API знает текущий file key и созданные node id.
