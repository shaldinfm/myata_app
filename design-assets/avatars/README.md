# Avatar artwork

**Status (G6a, 2026-09-14): 24 owner-created avatars, approved and bundled.**

- **Shipped:** `app/src/main/res/drawable-nodpi/avatar_myata_01.webp` … `avatar_myata_24.webp`,
  384px WebP.
- **Masters:** the owner's 1254px candidate PNGs. They are **not** committed, here or anywhere
  in the repo; they stay in the owner's candidate folder.
- **Pipeline:** `tools/avatars/export_avatars.py --source <that folder>` prepares and exports
  them. `tools/avatars/selection.json` is the approved mapping. `tools/avatars/manifest.json`
  records each avatar's source file, SHA-256, version, crop and output hash.

| Android resource | stored `user_metadata.avatar_id` |
|---|---|
| `avatar_myata_01` … `avatar_myata_24` | `myata-01` … `myata-24` |

The earlier 16 temporary images (Material 3 Design Kit renders taken from the Figma frame) are
gone from the APK. Their history, and the documented similarity consideration for the shipped
set, are in
[docs/PROFILE-AVATAR-3.6.6.md](../../docs/PROFILE-AVATAR-3.6.6.md#artwork-provenance-and-release-considerations).
