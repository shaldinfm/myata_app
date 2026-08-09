# Artwork image-fill sources

The dark Figma exporter uses these as separate image fills only. They are cropped from the supplied reference screenshots because the corresponding remote artwork is not bundled in Android resources.

- `player-what-you-know.png` — `PLAYER.png`, current-track cover.
- `playlist-seasonal-topping.png` and `playlist-xtra.png` — `HOME.png`, playlist covers.
- `track-homewrecker.png`, `track-leila.png`, `track-forever.png` — `COLLECTION.png`, saved-track covers.

Stream banners are not crops: the exporter converts `*_banner_new.xml` Android vector drawables to editable Figma vectors. Social logos are copied from Android `*_info.png` drawables.
