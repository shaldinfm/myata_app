# Current Android UI — локальный Figma development-плагин

Плагин переносит только текущий Home Android-приложения и отдельный UI KIT. Он не использует Figma MCP, сеть или Gradle-проект и не изменяет Android production-код.

## Запуск в Figma Desktop

1. Откройте Figma Desktop и существующий файл, в котором нужно создать страницу.
2. Выберите `Plugins → Development → Import plugin from manifest…`.
3. Укажите файл:

   `myata_app_clean/tools/figma-export/manifest.json`

4. Запустите `Plugins → Development → Current Android UI Export`.
5. В панели плагина нажмите `Создать Home`. Плагин создаст/обновит только свою страницу `CURRENT ANDROID UI`.

В панели можно выбрать до трёх локальных обложек. Если их не выбрать, первые две обложки Home берутся из crops прикреплённого reference-скриншота, а третья — из Android fallback-ресурса. Кнопка `Продолжить без растров` оставляет редактируемые цветовые fallback-слои, если среда Figma не разрешила загрузку изображений.

## Что создаётся

- `Home / Main (375×667)` — редактируемый FrameNode размером ровно 375×667 dp.
- `UI KIT / Current Android UI` — компоненты, paint styles/variables, типографика, токены и превью Android raster assets.
- `Reference / Home screenshot 576×1280 (locked)` — отдельный заблокированный RectangleNode с image fill, вынесенный за пределы Home. Это визуальный эталон, а не плоская подложка продукта.

Home использует exact Android XML-векторы `myata_banner_new.xml`, `gold_banner_new.xml` и `xtra_banner_new.xml`, разобранные в UI-панели в SVG перед передачей в Plugin API. Повторяющиеся элементы представлены ComponentNode/InstanceNode: нижняя навигация, карточки потоков, три варианта playlist card, play button и player controls. Горизонтальные ряды — Auto Layout с clipping.

## Визуальная геометрия Home v2

- фон `#1C3F5F`;
- `Наши потоки`: x22, y60, 25sp;
- потоковые карточки: 316×198, radius20, первая x21, gap7, следующая начинается x344 и обрезается viewport 375;
- `Мятные плейлисты`: x22, y350, 25sp;
- обложки: 140×140, radius20, первая x9, gap14, третья частично видна;
- нижняя навигация: x25, y603, 325×54, radius27, stroke1.5, нижний отступ10.

Скриншот имеет исходный размер 576×1280 и отображается как reference 375×833.333. Рабочий Home намеренно остаётся 375×667 по требованию и Android-эталонному viewport; системные status/navigation bars в рабочий фрейм не добавляются.

## Шрифт

Плагин проверяет `listAvailableFontsAsync()` и использует Muller, если семейство доступно в текущем Figma Desktop. В Android-ресурсах присутствуют regular/black/heavy/light/bold/thin варианты Muller. В предыдущем локальном аудите Figma Muller не был доступен, поэтому ожидаемый fallback — Inter; такие TextNode получают суффикс `[Inter fallback: Muller unavailable]`, а UI KIT содержит явную запись аудита.

## Использованные ресурсы

В `assets/` лежат копии:

- `myata_banner_new.xml`, `gold_banner_new.xml`, `xtra_banner_new.xml` — точные Android vector drawable;
- `zaglushka_1_img.png`, `zaglushka_3_img.png`, `zaglushka_4_img.png` — Android raster image fills/fallback и превью UI KIT;
- `reference-home.png` — приложенный скриншот для отдельного locked Reference;
- crops `reference-cover-01/02.png` создаются временно UI-панелью из reference для удалённых playlist covers, если пользователь не выбрал собственные файлы.

Скриншот не вставляется целиком внутрь Home и не векторизуется. Фотографии/сложные паттерны остаются растровыми image fills.

## Проверка без сборки

Сборка не требуется. Из `myata_app_clean`:

```powershell
node --check tools/figma-export/code.js
Get-Content tools/figma-export/manifest.json -Raw | ConvertFrom-Json | Out-Null
```

Также можно проверить UI script через Node, извлекая содержимое `<script>` из `ui.html`; фактический импорт нужно проверить вручную в Figma Desktop по шагам выше.

Повторный запуск не создаёт новые страницы/Home: обновляется только Home и plugin-owned UI KIT v2. Чужая страница с таким же именем и чужие компоненты не выбираются по имени и не перезаписываются.

## Что не хранится в репозитории

`assets/real-artwork/*.png` и `assets/reference-home.png` намеренно исключены
(`.gitignore`): это чужие обложки треков и скриншот, содержащий их же. Плагин без
них работает — обложки заменяются цветовыми fallback-слоями, а утверждённые
визуальные эталоны уже сохранены как PNG в `dark-theme/previews/`.

## Ограничения

- Плейлистовые обложки в Android загружаются удалённым API `radiomyata.ru`; без запуска приложения/API нельзя получить весь фактический набор. В проект включены локальные fallback и два crops из приложенного эталона.
- Точное Muller-написание зависит от установленного в Figma шрифта; .ttf/.otf не загружаются автоматически из-за лицензирования.
- Системные бары, живые состояния, remote data и фактический runtime Layout Inspector не воспроизводятся в первом этапе.
- Остальные экраны и состояния намеренно не создаются этой версией плагина.
