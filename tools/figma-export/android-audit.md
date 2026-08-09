# Android resource audit used by the local plugin

Дата snapshot: 2026-08-04. Источник: `myata_app_clean/app/src/main/res` and the active Kotlin fragments/view model; визуальная сверка — `tools/figma-export/assets/reference-home.png`.

## Stack and navigation

- Kotlin + XML Views; one mobile `MainActivity` with fragments.
- `app/src/main/res/navigation/navgraph.xml`: `splashFragment → mainFragment`, plus `playerFragment`, `infoFragment`, `donateFragment` and `favoritesFragment`.
- `fragment_player.xml` hosts a `ViewPager2` with three stream pages: MYATA, GOLD and XTRA.
- `fragment_history_bottom_sheet.xml` is a modal history sheet. Donate has a WebView payment state. `MainActivity` also contains the shared bottom navigation.
- The repository also contains TV layouts; they are intentionally outside the first 375×667 mobile stage.

## Home layout mapping

| Android source | Figma reconstruction |
| --- | --- |
| `fragment_main.xml` root `FrameLayout` | `Home / Main (375×667)` FrameNode |
| background `#1C3F5F` | editable rectangle `Background / #1C3F5F` |
| `Наши потоки`, 25sp, start 22dp, top 60dp | TextNode at x22/y60 |
| stream `HorizontalScrollView`, top margin 20dp | clipped viewport at y105; horizontal Auto Layout track |
| three `CardView` banners, radius 20dp, 316×198 vector assets | three local station ComponentNodes and instances |
| `Мятные плейлисты`, 25sp, start 22dp | TextNode at x22/y350 |
| horizontal `RecyclerView`, top margin 20dp, bottom margin 55dp | clipped viewport at y390; horizontal Auto Layout track |
| `rw_playlist_item.xml`, radius 20dp, image corner radius 15dp | three reusable 140×140 Playlist Card components with image fills |
| `activity_main.xml` bottom nav, 54dp, margins 25/10dp, radius 27dp, stroke 1.5dp | Bottom Navigation component instance at x25/y603 |

## Home visual correction from reference

Reference screenshot dimensions are 576×1280 px. It is kept in Figma as a separate locked 375×833.333 reference layer; it is not placed inside the product Home frame. The working Home remains the required 375×667 dp frame because the supplied screenshot includes a different vertical viewport plus system bars.

The updated plugin matches the measured horizontal composition: stream cards remain the Android 316×198 dp assets, first card starts at x21, item gap is 7 dp and the second card starts at x344 so only its leading portion is clipped by the 375 dp viewport. Playlist cards are 140×140 dp at x9, x163 and x317 with 14 dp gaps, which preserves the visible partial third card. The bottom navigation is 325×54 dp at x25/y603 with 27 dp radius and 1.5 dp black stroke.

## Tokens and resources

- Colors are taken from the active layouts/drawables: `#1C3F5F`, `#1C4771`, `#F5F7FA`, `#00E5FF`, `#FFFF00`, `#FFCCFF`, `#FF3F7B`, `#132740`, `#0F253E`, `#0A1D33`, `#204971`, `#5FD9B4`, `#1E3754`, `#88FFFFFF` and `#67686D`.
- There is no active `values/dimens.xml`; most measurements are hardcoded in the XML layouts. The plugin records the extracted 375×667 dp reference and the radius/spacing families in `screens.json`.
- Fonts are present under `res/font`: Muller regular/black/heavy/light/bold/thin variants. The plugin checks Figma availability at runtime and falls back to Inter when necessary.
- XML vectors used as source references include `home_new.xml`, `player_new.xml`, `donate_new.xml`, `info_new.xml`, `ic_favorites_nav.xml`, `myata_banner_new.xml`, `gold_banner_new.xml`, `xtra_banner_new.xml`, `btn_play.xml`, `ic_heart_outline.xml` and `ic_history.xml`. The three Home banners are copied into the local plugin bundle and parsed into editable SVG layers by `ui.html`.
- Bundled Android raster resources used as image fills are `zaglushka_1_img.png`, `zaglushka_3_img.png` and `zaglushka_4_img.png`. The attached `reference-home.png` is used only for the locked Reference layer; two temporary square crops are made for remote playlist covers when no user-selected covers are supplied. The remaining `screen0..9`, stream background bitmaps and remote playlist images are not embedded.
- Muller files are present in `res/font`, but the earlier Figma availability audit returned no Muller family. The plugin therefore records Inter fallback layers unless Muller is installed in the target Figma Desktop.
