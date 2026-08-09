// GENERATED FILE - do not edit.
// Source: code.template.js + repair-plan.json
// Rebuild: node tools/figma-export/repair/build-plugin.mjs

var REPAIR_PLAN = {
  "planVersion": "1.0.0",
  "collectionName": "Radio Myata / Semantic",
  "generatedFrom": {
    "dark": "CURRENT ANDROID UI — DARK",
    "light": "CURRENT ANDROID UI - LIGHT",
    "darkExportedAt": "2026-08-09T09:16:56.012Z",
    "lightExportedAt": "2026-08-09T09:15:47.627Z"
  },
  "pages": {
    "dark": "CURRENT ANDROID UI — DARK",
    "light": "CURRENT ANDROID UI - LIGHT"
  },
  "mutations": [
    {
      "group": "structural",
      "theme": "dark",
      "op": "setLayoutSizingHorizontal",
      "id": "2444:18249",
      "path": "PLAYER_dark > Main > Player Section > Track Info:margin > Track Info > TWO DOOR CINEMA CLUB",
      "expect": "FIXED",
      "value": "HUG",
      "reason": "Sibling track title is HUG/CENTER on both pages; the artist line is the outlier. Mini-player artist lines use FILL by design and are NOT touched."
    },
    {
      "group": "structural",
      "theme": "dark",
      "op": "setConstraints",
      "id": "2444:18269",
      "path": "PLAYER_dark > Controls > like > Container",
      "expect": "{\"horizontal\":\"MIN\",\"vertical\":\"MIN\"}",
      "value": "{\"horizontal\":\"CENTER\",\"vertical\":\"CENTER\"}",
      "reason": "Icon inside a 48dp touch target; CENTER/CENTER is the pattern on 5 of 6 control containers."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setConstraints",
      "id": "2399:31216",
      "path": "PLAYER > Controls > like > Container",
      "expect": "{\"horizontal\":\"MIN\",\"vertical\":\"MIN\"}",
      "value": "{\"horizontal\":\"CENTER\",\"vertical\":\"CENTER\"}",
      "reason": "Icon inside a 48dp touch target; CENTER/CENTER is the pattern on 5 of 6 control containers."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setConstraints",
      "id": "2399:31223",
      "path": "PLAYER > Controls > dislike > Container",
      "expect": "{\"horizontal\":\"MIN\",\"vertical\":\"MIN\"}",
      "value": "{\"horizontal\":\"CENTER\",\"vertical\":\"CENTER\"}",
      "reason": "Icon inside a 48dp touch target; CENTER/CENTER is the pattern on 5 of 6 control containers."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setFontStyle",
      "id": "2396:30788",
      "path": "PLAYER > Broadcast History Section > Heading 2 > История эфира",
      "expect": "Muller/Medium",
      "value": "Muller/Bold",
      "reason": "Every other true section heading (Наши потоки, Мятные плейлисты, О нас) is Muller Bold."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "deleteNode",
      "id": "2429:296",
      "path": "COLLECTION pusto > Track Item 1 > Container (hidden duplicate subtitle, y=259)",
      "expect": "hidden duplicate of 'Сохраняйте понравившиеся…'",
      "value": "deleted",
      "reason": "Light carries two copies of the same subtitle; the visible one at y=264 matches Dark and is kept."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setVisible",
      "id": "2411:31650",
      "path": "ABOUT US > One-time Donation Card > Background+Blur",
      "expect": "true",
      "value": "false",
      "reason": "About-screen decorative blurs are hidden in Dark and on the Boosty card in Light; canonical is hidden. The Player album-art blur is visible in BOTH themes and is deliberately NOT touched."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setVisible",
      "id": "2411:31664",
      "path": "ABOUT US > Section 2: About Us > Background+Blur",
      "expect": "true",
      "value": "false",
      "reason": "About-screen decorative blurs are hidden in Dark and on the Boosty card in Light; canonical is hidden. The Player album-art blur is visible in BOTH themes and is deliberately NOT touched."
    },
    {
      "group": "structural",
      "theme": "dark",
      "op": "setAutoLayoutHug",
      "id": "2444:18634",
      "path": "ABOUT US > Section 3: Social Media > TikTok > Container",
      "expect": "NONE / FIXED / FIXED",
      "value": "HORIZONTAL / HUG / HUG",
      "reason": "7 of 8 social buttons use HORIZONTAL + HUG/HUG."
    },
    {
      "group": "structural",
      "theme": "dark",
      "op": "setAutoLayoutHug",
      "id": "2444:18640",
      "path": "ABOUT US > Section 3: Social Media > Threads > Container",
      "expect": "NONE / FIXED / FIXED",
      "value": "HORIZONTAL / HUG / HUG",
      "reason": "7 of 8 social buttons use HORIZONTAL + HUG/HUG."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setAutoLayoutHug",
      "id": "2419:122",
      "path": "ABOUT US > Section 3: Social Media > Threads > Container",
      "expect": "NONE / FIXED / FIXED",
      "value": "HORIZONTAL / HUG / HUG",
      "reason": "7 of 8 social buttons use HORIZONTAL + HUG/HUG."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "renameNode",
      "id": "2396:30797",
      "path": "PLAYER > Broadcast History Section > List > History Item 1 > Text",
      "expect": "Text",
      "value": "10:45",
      "reason": "Layer was retyped and lost its content-derived name; Dark already names it 10:45. UI-KIT layers named 'History / time' are role names and are NOT renamed."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "setConstraints",
      "id": "2444:18814",
      "path": "ABOUT US > Section 3: Social Media > Container > TikTok > Container > Vector",
      "expect": "{\"horizontal\":\"MIN\",\"vertical\":\"MIN\"}",
      "value": "{\"horizontal\":\"CENTER\",\"vertical\":\"CENTER\"}",
      "reason": "The TikTok glyph itself, not its container. Dark is already CENTER/CENTER (2444:18812); light is the only side that differs."
    },
    {
      "group": "structural",
      "theme": "light",
      "op": "reorderChild",
      "id": "2444:10347",
      "parentId": "2429:196",
      "fromIndex": 3,
      "toIndex": 1,
      "path": "COLLECTION pusto > Track Item 1 > Container ('Сохраняйте понравившеся треки...')",
      "expect": "index 3",
      "value": "index 1",
      "reason": "Layer-order only, to match Dark. Deleting the hidden duplicate revealed that Light keeps the subtitle at index 3 while Dark has it at index 1. Same elements, same geometry, same visibility - the elements are absolutely positioned and do not overlap, so nothing moves visually. Purely so structural diffs stay meaningful."
    }
  ],
  "tokens": {
    "surface": {
      "dark": "#142d47",
      "light": "#ffffff",
      "bind": true
    },
    "surfaceContainer": {
      "dark": "#1c4771",
      "light": "#f8f9fa",
      "bind": true
    },
    "navigationContainer": {
      "dark": "#142d47",
      "light": "#edeeef",
      "bind": true
    },
    "menuSurface": {
      "dark": "#142d47",
      "light": "#f8f9fa",
      "bind": true
    },
    "textPrimary": {
      "dark": "#f5f7fa",
      "light": "#191c1d",
      "bind": true
    },
    "textHeading": {
      "dark": "#f5f7fa",
      "light": "#003056",
      "bind": true
    },
    "textSecondary": {
      "dark": "#b3c4d1",
      "light": "#42474e",
      "bind": true
    },
    "outline": {
      "dark": "#466d8f",
      "light": "#e1e3e4",
      "bind": true
    },
    "primary": {
      "dark": "#5fd9b4",
      "light": "#1c4771",
      "bind": true
    },
    "onPrimary": {
      "dark": "#0f253e",
      "light": "#ffffff",
      "bind": false
    },
    "background": {
      "dark": "#0b1d31",
      "light": "#f8f9fa",
      "bind": false
    },
    "surfaceElevated": {
      "dark": "#28557e",
      "light": "#ffffff",
      "bind": false
    },
    "divider": {
      "dark": "#466d8f",
      "light": "#e1e3e4",
      "bind": false
    },
    "textDisabled": {
      "dark": "#6f899f",
      "light": "#8e969e",
      "bind": false
    },
    "secondary": {
      "dark": "#00e5ff",
      "light": "#0090a3",
      "bind": false
    },
    "error": {
      "dark": "#ff8a80",
      "light": "#b3261e",
      "bind": false
    },
    "disabled": {
      "dark": "#1e3754",
      "light": "#e8eaed",
      "bind": false
    },
    "scrim": {
      "dark": "#0f253e",
      "light": "#003056",
      "bind": false
    }
  },
  "tokenBindings": [
    {
      "group": "tokenBinding",
      "token": "navigationContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:10351",
        "path": "home > BottomNavBar",
        "current": "#142d47"
      },
      "light": {
        "id": "2393:1629",
        "path": "home > BottomNavBar",
        "current": "#edeeef"
      },
      "nodeType": "FRAME",
      "nodeName": "BottomNavBar"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10359",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1637",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10361",
        "path": "home > BottomNavBar > Container > Margin > Плеер",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1639",
        "path": "home > BottomNavBar > Container > Margin > Плеер",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Плеер"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10364",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1642",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10366",
        "path": "home > BottomNavBar > Container > Margin > Коллекция",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1644",
        "path": "home > BottomNavBar > Container > Margin > Коллекция",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Коллекция"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10369",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1647",
        "path": "home > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:10371",
        "path": "home > BottomNavBar > Container > Margin > О нас",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2393:1649",
        "path": "home > BottomNavBar > Container > Margin > О нас",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "О нас"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:10388",
        "path": "home > Header - TopAppBar > Heading 1 > Привет, Денис!",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2393:1669",
        "path": "home > Header - TopAppBar > Heading 1 > Привет, Денис!",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Привет, Денис!"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:10398",
        "path": "home > Main > Live Streams Section > Heading 2 > Наши потоки",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2393:1676",
        "path": "home > Main > Live Streams Section > Heading 2 > Наши потоки",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Наши потоки"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18212",
        "path": "home > Main > Mint Playlists Section > Container > Heading 2 > Мятные плейлисты",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2393:1725",
        "path": "home > Main > Mint Playlists Section > Container > Heading 2 > Мятные плейлисты",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Мятные плейлисты"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18241;58548:7288",
        "path": "player > Main > Player Section > Mobile Header (Subtle):margin > swipe > Shape Set > shape",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "I2402:31398;58548:7288",
        "path": "player > Main > Player Section > Mobile Header (Subtle):margin > swipe > Shape Set > shape",
        "current": "#1c4771"
      },
      "nodeType": "VECTOR",
      "nodeName": "shape"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18247",
        "path": "player > Main > Player Section > Track Info:margin > Track Info > Heading 1 > WHAT YOU KNOW",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2396:30745",
        "path": "player > Main > Player Section > Track Info:margin > Track Info > Heading 1 > WHAT YOU KNOW",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "WHAT YOU KNOW"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "2444:18271",
        "path": "player > Main > Player Section > Controls:margin > Controls > play/pause",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "2399:31218",
        "path": "player > Main > Player Section > Controls:margin > Controls > play/pause",
        "current": "#1c4771"
      },
      "nodeType": "FRAME",
      "nodeName": "play/pause"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18288",
        "path": "player > Main > Broadcast History Section",
        "current": "#142d47"
      },
      "light": {
        "id": "2396:30784",
        "path": "player > Main > Broadcast History Section",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "Broadcast History Section"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18288",
        "path": "player > Main > Broadcast History Section",
        "current": "#466d8f"
      },
      "light": {
        "id": "2396:30784",
        "path": "player > Main > Broadcast History Section",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Broadcast History Section"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18292",
        "path": "player > Main > Broadcast History Section > Margin > Container > Heading 2 > История эфира",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2396:30788",
        "path": "player > Main > Broadcast History Section > Margin > Container > Heading 2 > История эфира",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "История эфира"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18301",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > 10:45",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30797",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > 10:45",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "10:45"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18306",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > Container > CRYOGEN",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2396:30802",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > Container > CRYOGEN",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "CRYOGEN"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18308",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > Container > MUSE",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30804",
        "path": "player > Main > Broadcast History Section > List > History Item 1 > Container > Container > Container > MUSE",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "MUSE"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18319",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > 10:41",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31075",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > 10:41",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "10:41"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18324",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > Container > MEET ME IN LOVE",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2399:31080",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > Container > MEET ME IN LOVE",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "MEET ME IN LOVE"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18326",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > Container > BLOSSOMS",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31082",
        "path": "player > Main > Broadcast History Section > History Item 1 > Container > Container > Container > BLOSSOMS",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "BLOSSOMS"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18337",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > 10:36",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31094",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > 10:36",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "10:36"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18342",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > Container > CITY WALLS",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2399:31099",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > Container > CITY WALLS",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "CITY WALLS"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18344",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > Container > TWENTY ONE PILOTS",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31101",
        "path": "player > Main > Broadcast History Section > History Item 2 > Container > Container > Container > TWENTY ONE PILOTS",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "TWENTY ONE PILOTS"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18353",
        "path": "player > Main > Broadcast History Section > Button:margin > Button",
        "current": "#466d8f"
      },
      "light": {
        "id": "2396:30867",
        "path": "player > Main > Broadcast History Section > Button:margin > Button",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "navigationContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18355",
        "path": "player > BottomNavBar",
        "current": "#142d47"
      },
      "light": {
        "id": "2396:30914",
        "path": "player > BottomNavBar",
        "current": "#edeeef"
      },
      "nodeType": "FRAME",
      "nodeName": "BottomNavBar"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18358",
        "path": "player > BottomNavBar > Background > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30917",
        "path": "player > BottomNavBar > Background > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18360",
        "path": "player > BottomNavBar > Background > Margin > Главная",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30919",
        "path": "player > BottomNavBar > Background > Margin > Главная",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Главная"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18368",
        "path": "player > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30927",
        "path": "player > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18370",
        "path": "player > BottomNavBar > Container > Margin > Коллекция",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30929",
        "path": "player > BottomNavBar > Container > Margin > Коллекция",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Коллекция"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18373",
        "path": "player > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30932",
        "path": "player > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18375",
        "path": "player > BottomNavBar > Container > Margin > О нас",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2396:30934",
        "path": "player > BottomNavBar > Container > Margin > О нас",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "О нас"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18376",
        "path": "collection",
        "current": "#142d47"
      },
      "light": {
        "id": "2399:31129",
        "path": "collection",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "COLLECTION_dark"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18381",
        "path": "collection > Main > Header Section > Container > Container > Здесь хранятся ваши сохранённые треки",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31136",
        "path": "collection > Main > Header Section > Container > Container > Здесь хранятся ваши сохранённые треки",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Здесь хранятся ваши сохранённые треки"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18387",
        "path": "collection > Main > Collection List > Track Item 1",
        "current": "#142d47"
      },
      "light": {
        "id": "2399:31142",
        "path": "collection > Main > Collection List > Track Item 1",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 1"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18387",
        "path": "collection > Main > Collection List > Track Item 1",
        "current": "#466d8f"
      },
      "light": {
        "id": "2399:31142",
        "path": "collection > Main > Collection List > Track Item 1",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 1"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18392",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Heading 3 > HOMEWRECKER",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2399:31147",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Heading 3 > HOMEWRECKER",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "HOMEWRECKER"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18394",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Container > SOMBR",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31149",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Container > SOMBR",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "SOMBR"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "strokes",
      "dark": {
        "id": "2444:18396",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Button",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "2409:31570",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Button",
        "current": "#1c4771"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18398;54616:25402",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Button > Container > arrow_forward > icon",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "I2409:31572;54616:25402",
        "path": "collection > Main > Collection List > Track Item 1 > Container > Button > Container > arrow_forward > icon",
        "current": "#1c4771"
      },
      "nodeType": "VECTOR",
      "nodeName": "icon"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18399",
        "path": "collection > Main > Collection List > Track Item 2",
        "current": "#142d47"
      },
      "light": {
        "id": "2399:31154",
        "path": "collection > Main > Collection List > Track Item 2",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 2"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18399",
        "path": "collection > Main > Collection List > Track Item 2",
        "current": "#466d8f"
      },
      "light": {
        "id": "2399:31154",
        "path": "collection > Main > Collection List > Track Item 2",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 2"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18404",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Heading 3 > LEILA",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2399:31159",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Heading 3 > LEILA",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "LEILA"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18406",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Container > MIAMI HORROR FT. POOLSIDE",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31161",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Container > MIAMI HORROR FT. POOLSIDE",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "MIAMI HORROR FT. POOLSIDE"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "strokes",
      "dark": {
        "id": "2444:18408",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Button",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "2409:31558",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Button",
        "current": "#1c4771"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18410;54616:25402",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Button > Container > arrow_forward > icon",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "I2409:31560;54616:25402",
        "path": "collection > Main > Collection List > Track Item 2 > Container > Button > Container > arrow_forward > icon",
        "current": "#1c4771"
      },
      "nodeType": "VECTOR",
      "nodeName": "icon"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18411",
        "path": "collection > Main > Collection List > Track Item 3",
        "current": "#142d47"
      },
      "light": {
        "id": "2399:31166",
        "path": "collection > Main > Collection List > Track Item 3",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 3"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18411",
        "path": "collection > Main > Collection List > Track Item 3",
        "current": "#466d8f"
      },
      "light": {
        "id": "2399:31166",
        "path": "collection > Main > Collection List > Track Item 3",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 3"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18416",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Heading 3 > FOREVER",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2399:31171",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Heading 3 > FOREVER",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "FOREVER"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18418",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Container > CHVRCHES",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2399:31173",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Container > CHVRCHES",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "CHVRCHES"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "strokes",
      "dark": {
        "id": "2444:18420",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Button",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "2399:31175",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Button",
        "current": "#1c4771"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18422;54616:25402",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Button > Container > arrow_forward > icon",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "I2409:31542;54616:25402",
        "path": "collection > Main > Collection List > Track Item 3 > Container > Button > Container > arrow_forward > icon",
        "current": "#1c4771"
      },
      "nodeType": "VECTOR",
      "nodeName": "icon"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18425",
        "path": "collection > Header - TopAppBar > Heading 1 > Моя коллекция",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2407:31460",
        "path": "collection > Header - TopAppBar > Heading 1 > Моя коллекция",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Моя коллекция"
    },
    {
      "group": "tokenBinding",
      "token": "navigationContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18430",
        "path": "collection > BottomNavBar",
        "current": "#142d47"
      },
      "light": {
        "id": "2407:31502",
        "path": "collection > BottomNavBar",
        "current": "#edeeef"
      },
      "nodeType": "FRAME",
      "nodeName": "BottomNavBar"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18433",
        "path": "collection > BottomNavBar > Background > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31505",
        "path": "collection > BottomNavBar > Background > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18435",
        "path": "collection > BottomNavBar > Background > Margin > Главная",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31507",
        "path": "collection > BottomNavBar > Background > Margin > Главная",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Главная"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18438",
        "path": "collection > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31510",
        "path": "collection > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18440",
        "path": "collection > BottomNavBar > Container > Margin > Плеер",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31512",
        "path": "collection > BottomNavBar > Container > Margin > Плеер",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Плеер"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18448",
        "path": "collection > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31520",
        "path": "collection > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18450",
        "path": "collection > BottomNavBar > Container > Margin > О нас",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2407:31522",
        "path": "collection > BottomNavBar > Container > Margin > О нас",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "О нас"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18484",
        "path": "collection pusto > Main > Header Section > Container > Container > Здесь хранятся ваши сохранённые треки",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:190",
        "path": "collection pusto > Main > Header Section > Container > Container > Здесь хранятся ваши сохранённые треки",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Здесь хранятся ваши сохранённые треки"
    },
    {
      "group": "tokenBinding",
      "token": "surface",
      "prop": "fills",
      "dark": {
        "id": "2444:18490",
        "path": "collection pusto > Main > Collection List > Track Item 1",
        "current": "#142d47"
      },
      "light": {
        "id": "2429:196",
        "path": "collection pusto > Main > Collection List > Track Item 1",
        "current": "#ffffff"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 1"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18490",
        "path": "collection pusto > Main > Collection List > Track Item 1",
        "current": "#466d8f"
      },
      "light": {
        "id": "2429:196",
        "path": "collection pusto > Main > Collection List > Track Item 1",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Track Item 1"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "2444:18493",
        "path": "collection pusto > Main > Collection List > Track Item 1 > Container > Heading 3 > Здесь пока пусто",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2429:201",
        "path": "collection pusto > Main > Collection List > Track Item 1 > Container > Heading 3 > Здесь пока пусто",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "Здесь пока пусто"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18510",
        "path": "collection pusto > Header - TopAppBar > Heading 1 > Моя коллекция",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2429:234",
        "path": "collection pusto > Header - TopAppBar > Heading 1 > Моя коллекция",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Моя коллекция"
    },
    {
      "group": "tokenBinding",
      "token": "navigationContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18518",
        "path": "collection pusto > BottomNavBar",
        "current": "#142d47"
      },
      "light": {
        "id": "2429:239",
        "path": "collection pusto > BottomNavBar",
        "current": "#edeeef"
      },
      "nodeType": "FRAME",
      "nodeName": "BottomNavBar"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18521",
        "path": "collection pusto > BottomNavBar > Background > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:242",
        "path": "collection pusto > BottomNavBar > Background > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18523",
        "path": "collection pusto > BottomNavBar > Background > Margin > Главная",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:244",
        "path": "collection pusto > BottomNavBar > Background > Margin > Главная",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Главная"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18526",
        "path": "collection pusto > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:247",
        "path": "collection pusto > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18528",
        "path": "collection pusto > BottomNavBar > Container > Margin > Плеер",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:249",
        "path": "collection pusto > BottomNavBar > Container > Margin > Плеер",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Плеер"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18536",
        "path": "collection pusto > BottomNavBar > Container > Container > Icon",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:257",
        "path": "collection pusto > BottomNavBar > Container > Container > Icon",
        "current": "#42474e"
      },
      "nodeType": "VECTOR",
      "nodeName": "Icon"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18538",
        "path": "collection pusto > BottomNavBar > Container > Margin > О нас",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2429:259",
        "path": "collection pusto > BottomNavBar > Container > Margin > О нас",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "О нас"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18570",
        "path": "about us > Header - TopAppBar > Heading 1 > Поддержать радио",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2413:57",
        "path": "about us > Header - TopAppBar > Heading 1 > Поддержать радио",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Поддержать радио"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18585",
        "path": "about us > Main > Section 1: Support the Project > Container > Boosty Subscription Card > Container > Background+Border",
        "current": "#1c4771"
      },
      "light": {
        "id": "2411:31638",
        "path": "about us > Main > Section 1: Support the Project > Container > Boosty Subscription Card > Container > Background+Border",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Background+Border"
    },
    {
      "group": "tokenBinding",
      "token": "primary",
      "prop": "fills",
      "dark": {
        "id": "2444:18598",
        "path": "about us > Main > Section 1: Support the Project > Container > One-time Donation Card > Container > Background+Border > Разово",
        "current": "#5fd9b4"
      },
      "light": {
        "id": "2411:31653",
        "path": "about us > Main > Section 1: Support the Project > Container > One-time Donation Card > Container > Background+Border > Разово",
        "current": "#1c4771"
      },
      "nodeType": "TEXT",
      "nodeName": "Разово"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18606",
        "path": "about us > Main > Section 2: About Us",
        "current": "#466d8f"
      },
      "light": {
        "id": "2411:31663",
        "path": "about us > Main > Section 2: About Us",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Section 2: About Us"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18609",
        "path": "about us > Main > Section 2: About Us > Heading 2 > О нас",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2411:31666",
        "path": "about us > Main > Section 2: About Us > Heading 2 > О нас",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "О нас"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18612",
        "path": "about us > Main > Section 2: About Us > Container > Container > Радио Мята — интернет-радиостанция, ориентированная на инди-музыку и альтернативный рок.",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2411:31669",
        "path": "about us > Main > Section 2: About Us > Container > Container > Радио Мята — интернет-радиостанция, ориентированная на инди-музыку и альтернативный рок.",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Радио Мята — интернет-радиостанция, ориентированная на инди-музыку и альтернативный рок."
    },
    {
      "group": "tokenBinding",
      "token": "menuSurface",
      "prop": "fills",
      "dark": {
        "id": "2444:18618",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button",
        "current": "#142d47"
      },
      "light": {
        "id": "2424:758",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "outline",
      "prop": "strokes",
      "dark": {
        "id": "2444:18618",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button",
        "current": "#466d8f"
      },
      "light": {
        "id": "2424:758",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button",
        "current": "#e1e3e4"
      },
      "nodeType": "FRAME",
      "nodeName": "Button"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18619",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button > Читать подробнее",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2424:759",
        "path": "about us > Main > Section 2: About Us > Button:margin > Button > Читать подробнее",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Читать подробнее"
    },
    {
      "group": "tokenBinding",
      "token": "textHeading",
      "prop": "fills",
      "dark": {
        "id": "2444:18622",
        "path": "about us > Main > Section 3: Social Media > Heading 2 > Ещё Радио Мята",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "2411:31676",
        "path": "about us > Main > Section 3: Social Media > Heading 2 > Ещё Радио Мята",
        "current": "#003056"
      },
      "nodeType": "TEXT",
      "nodeName": "Ещё Радио Мята"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18624",
        "path": "about us > Main > Section 3: Social Media > Container > Telegram",
        "current": "#1c4771"
      },
      "light": {
        "id": "2411:31678",
        "path": "about us > Main > Section 3: Social Media > Container > Telegram",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Telegram"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18627",
        "path": "about us > Main > Section 3: Social Media > Container > Spotify",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:99",
        "path": "about us > Main > Section 3: Social Media > Container > Spotify",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Spotify"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18630",
        "path": "about us > Main > Section 3: Social Media > Container > Instagram",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:105",
        "path": "about us > Main > Section 3: Social Media > Container > Instagram",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Instagram"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18633",
        "path": "about us > Main > Section 3: Social Media > Container > TikTok",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:109",
        "path": "about us > Main > Section 3: Social Media > Container > TikTok",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "TikTok"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18636",
        "path": "about us > Main > Section 3: Social Media > Container > YouTube",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:113",
        "path": "about us > Main > Section 3: Social Media > Container > YouTube",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "YouTube"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18639",
        "path": "about us > Main > Section 3: Social Media > Container > Threads",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:121",
        "path": "about us > Main > Section 3: Social Media > Container > Threads",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Threads"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18642",
        "path": "about us > Main > Section 3: Social Media > Container > Boosty",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:125",
        "path": "about us > Main > Section 3: Social Media > Container > Boosty",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Boosty"
    },
    {
      "group": "tokenBinding",
      "token": "surfaceContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18645",
        "path": "about us > Main > Section 3: Social Media > Container > ЯМузыка",
        "current": "#1c4771"
      },
      "light": {
        "id": "2419:129",
        "path": "about us > Main > Section 3: Social Media > Container > ЯМузыка",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "ЯМузыка"
    },
    {
      "group": "tokenBinding",
      "token": "navigationContainer",
      "prop": "fills",
      "dark": {
        "id": "2444:18648",
        "path": "about us > BottomNavBar",
        "current": "#142d47"
      },
      "light": {
        "id": "2417:77",
        "path": "about us > BottomNavBar",
        "current": "#edeeef"
      },
      "nodeType": "FRAME",
      "nodeName": "BottomNavBar"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18653",
        "path": "about us > BottomNavBar > Background > Margin > Главная",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2417:82",
        "path": "about us > BottomNavBar > Background > Margin > Главная",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Главная"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18658",
        "path": "about us > BottomNavBar > Container > Margin > Плеер",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2417:87",
        "path": "about us > BottomNavBar > Container > Margin > Плеер",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Плеер"
    },
    {
      "group": "tokenBinding",
      "token": "textSecondary",
      "prop": "fills",
      "dark": {
        "id": "2444:18663",
        "path": "about us > BottomNavBar > Container > Margin > Коллекция",
        "current": "#b3c4d1"
      },
      "light": {
        "id": "2417:92",
        "path": "about us > BottomNavBar > Container > Margin > Коллекция",
        "current": "#42474e"
      },
      "nodeType": "TEXT",
      "nodeName": "Коллекция"
    },
    {
      "group": "tokenBinding",
      "token": "menuSurface",
      "prop": "fills",
      "dark": {
        "id": "2444:18707",
        "path": "menu / плеер",
        "current": "#142d47"
      },
      "light": {
        "id": "2444:18763",
        "path": "menu / плеер",
        "current": "#f8f9fa"
      },
      "nodeType": "FRAME",
      "nodeName": "Menu / Плеер"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18708;2436:642",
        "path": "menu / плеер > Menu row / Найти трек > Menu / label",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "I2444:18764;2436:642",
        "path": "menu / плеер > Menu row / Найти трек > Menu / label",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "Menu / label"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18709;2436:642",
        "path": "menu / плеер > Menu row / Таймер сна > Menu / label",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "I2444:18765;2436:642",
        "path": "menu / плеер > Menu row / Таймер сна > Menu / label",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "Menu / label"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18710;2436:642",
        "path": "menu / плеер > Menu row / Сообщить о проблеме > Menu / label",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "I2444:18766;2436:642",
        "path": "menu / плеер > Menu row / Сообщить о проблеме > Menu / label",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "Menu / label"
    },
    {
      "group": "tokenBinding",
      "token": "textPrimary",
      "prop": "fills",
      "dark": {
        "id": "I2444:18711;2436:642",
        "path": "menu / плеер > Menu row / История эфира > Menu / label",
        "current": "#f5f7fa"
      },
      "light": {
        "id": "I2444:18767;2436:642",
        "path": "menu / плеер > Menu row / История эфира > Menu / label",
        "current": "#191c1d"
      },
      "nodeType": "TEXT",
      "nodeName": "Menu / label"
    }
  ],
  "textStyles": [
    {
      "name": "Radio Myata/screenTitle",
      "family": "Muller",
      "style": "Medium",
      "size": 24,
      "lineHeight": 32,
      "sample": "Привет, Денис! · Моя коллекция"
    },
    {
      "name": "Radio Myata/sectionTitle",
      "family": "Muller",
      "style": "Bold",
      "size": 28,
      "lineHeight": 36,
      "sample": "Наши потоки · Мятные плейлисты · О нас"
    },
    {
      "name": "Radio Myata/sectionTitleDense",
      "family": "Muller",
      "style": "Bold",
      "size": 24,
      "lineHeight": 32,
      "sample": "История эфира"
    },
    {
      "name": "Radio Myata/trackTitle",
      "family": "Muller",
      "style": "Black",
      "size": 24,
      "lineHeight": 24,
      "sample": "WHAT YOU KNOW (player)"
    },
    {
      "name": "Radio Myata/miniPlayerTitle",
      "family": "Muller",
      "style": "Medium",
      "size": 15,
      "lineHeight": 27.5,
      "sample": "WHAT YOU KNOW (mini-player)"
    },
    {
      "name": "Radio Myata/miniPlayerSubtitle",
      "family": "Muller",
      "style": "Regular",
      "size": 14,
      "lineHeight": 20,
      "sample": "TWO DOOR CINEMA CLUB"
    },
    {
      "name": "Radio Myata/listTitle",
      "family": "Muller",
      "style": "Regular",
      "size": 17,
      "lineHeight": 28,
      "sample": "CRYOGEN · MEET ME IN LOVE"
    },
    {
      "name": "Radio Myata/listTitleCompact",
      "family": "Muller",
      "style": "Regular",
      "size": 16,
      "lineHeight": 28,
      "sample": "HOMEWRECKER · LEILA"
    },
    {
      "name": "Radio Myata/rowLabel",
      "family": "Muller",
      "style": "Medium",
      "size": 16,
      "lineHeight": 22,
      "sample": "Spotify · Apple Music"
    },
    {
      "name": "Radio Myata/body",
      "family": "Muller",
      "style": "Regular",
      "size": 15,
      "lineHeight": 24,
      "sample": "Радио Мята — интернет-радиостанция…"
    },
    {
      "name": "Radio Myata/bodyStrong",
      "family": "Muller",
      "style": "Medium",
      "size": 15,
      "lineHeight": 20,
      "sample": "Действие"
    },
    {
      "name": "Radio Myata/button",
      "family": "Muller",
      "style": "Regular",
      "size": 22,
      "lineHeight": 28,
      "sample": "Показать ещё · Экспортировать список"
    },
    {
      "name": "Radio Myata/navLabel",
      "family": "Muller",
      "style": "Medium",
      "size": 12,
      "lineHeight": 16,
      "sample": "Главная · Плеер · Коллекция"
    },
    {
      "name": "Radio Myata/caption",
      "family": "Muller",
      "style": "Regular",
      "size": 12,
      "lineHeight": 16,
      "sample": "Сейчас играет · Ежемесячно"
    },
    {
      "name": "Radio Myata/timestamp",
      "family": "Muller",
      "style": "Regular",
      "size": 14,
      "lineHeight": 20,
      "sample": "10:45 (after Hanken removal)"
    }
  ]
};

/*
 * Radio Myata - controlled canonical repair.
 *
 *   DRY RUN  reads the document and reports what it would do, plus the current
 *            state of the semantic collection, its modes, variables and text
 *            styles. It performs NO writes.
 *   APPLY    executes only the entries the last Dry Run marked READY, re-verifying
 *            each node immediately before writing.
 *
 * Both modes are idempotent and recoverable. A run that failed halfway can be
 * re-run: anything already done reports as ALREADY_APPLIED and is not repeated.
 *
 * The plan is data (repair-plan.json, embedded above as REPAIR_PLAN). The plugin
 * cannot invent a mutation that is not in the plan.
 */

figma.showUI(__html__, { width: 560, height: 680 });

var verifiedPlan = null;

// ---------------- helpers ----------------

function hexToRgb(hex) {
  var h = String(hex).replace("#", "");
  return { r: parseInt(h.slice(0, 2), 16) / 255, g: parseInt(h.slice(2, 4), 16) / 255, b: parseInt(h.slice(4, 6), 16) / 255 };
}
function rgbToHex(c) {
  var to = function (v) { return Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, "0"); };
  return "#" + to(c.r) + to(c.g) + to(c.b);
}
function solidOf(node, prop) {
  var arr = node[prop];
  if (!Array.isArray(arr)) return null;
  for (var i = 0; i < arr.length; i++) if (arr[i].type === "SOLID" && arr[i].visible !== false) return arr[i];
  return null;
}
function errText(e) {
  if (!e) return "unknown error";
  return (e.message || String(e)) + (e.stack ? "\n" + String(e.stack).split("\n").slice(0, 3).join("\n") : "");
}
async function loadAllPages() {
  try { if (figma.loadAllPagesAsync) await figma.loadAllPagesAsync(); } catch (e) { /* legacy access */ }
}
async function getNode(id) {
  try { return await figma.getNodeByIdAsync(id); } catch (e) { return null; }
}

// ---------------- classification ----------------

function currentValue(node, op) {
  switch (op) {
    case "setLayoutSizingHorizontal": return String(node.layoutSizingHorizontal);
    case "setConstraints": return JSON.stringify({ horizontal: node.constraints.horizontal, vertical: node.constraints.vertical });
    case "setFontStyle": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style : "MIXED";
    case "setFontFamily": return node.fontName && node.fontName.family ? node.fontName.family + "/" + node.fontName.style : "MIXED";
    case "setVisible": return String(node.visible !== false);
    case "renameNode": return node.name;
    case "deleteNode": return "present";
    case "setAutoLayoutHug": return (node.layoutMode || "NONE") + " / " + node.layoutSizingHorizontal + " / " + node.layoutSizingVertical;
    case "reorderChild": return node.parent ? "index " + node.parent.children.indexOf(node) : "(no parent)";
    default: return "?";
  }
}

// Has this mutation already been done?
function isAlreadyApplied(op, current, m) {
  switch (op) {
    case "setLayoutSizingHorizontal": return current === m.value;
    case "setConstraints": {
      try {
        var a = JSON.parse(current), b = JSON.parse(m.value);
        return a.horizontal === b.horizontal && a.vertical === b.vertical;
      } catch (e) { return false; }
    }
    case "setFontStyle": return current === m.value;
    case "setFontFamily": return current.indexOf("Muller/") === 0;
    case "setVisible": return current === m.value;
    case "renameNode": return current === m.value;
    case "setAutoLayoutHug": return current === "HORIZONTAL / HUG / HUG";
    case "reorderChild": return current === m.value;
    default: return false;
  }
}

// Is the node still in the state the plan expected before the change?
function isReady(op, current, m) {
  switch (op) {
    case "setFontFamily": return current.indexOf("Hanken") === 0;
    case "setConstraints": {
      try {
        var a = JSON.parse(current), b = JSON.parse(m.expect);
        return a.horizontal === b.horizontal && a.vertical === b.vertical;
      } catch (e) { return false; }
    }
    case "setAutoLayoutHug": return current !== "HORIZONTAL / HUG / HUG";
    case "reorderChild": return current === m.expect;
    case "deleteNode": return true; // node exists => still to delete
    default: return current === m.expect;
  }
}

function classify(node, m) {
  if (!node) return m.op === "deleteNode"
    ? { status: "ALREADY_APPLIED", current: "(node gone - deleted)" }
    : { status: "SKIP_MISSING", current: "(node not found)" };
  var cur = currentValue(node, m.op);
  if (m.op !== "deleteNode" && isAlreadyApplied(m.op, cur, m)) return { status: "ALREADY_APPLIED", current: cur };
  if (isReady(m.op, cur, m)) return { status: "READY", current: cur };
  return { status: "SKIP_CHANGED", current: cur };
}

// ---------------- semantic collection state (read-only) ----------------

async function readCollectionState() {
  var state = { exists: false, id: null, modes: [], modeLight: null, modeDark: null, variables: {}, missingVariables: [], canBind: false, note: "" };
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) { state.note = "Collection does not exist yet; it will be created."; return state; }

  state.exists = true;
  state.id = col.id;
  for (var m = 0; m < col.modes.length; m++) {
    state.modes.push(col.modes[m].name);
    if (col.modes[m].name === "Light") state.modeLight = col.modes[m].modeId;
    if (col.modes[m].name === "Dark") state.modeDark = col.modes[m].modeId;
  }

  var vars = await figma.variables.getLocalVariablesAsync("COLOR");
  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var found = null;
    for (var v = 0; v < vars.length; v++) if (vars[v].name === names[t] && vars[v].variableCollectionId === col.id) found = vars[v];
    if (found) state.variables[names[t]] = { id: found.id, modes: Object.keys(found.valuesByMode).length };
    else state.missingVariables.push(names[t]);
  }

  state.canBind = !!(state.modeLight && state.modeDark);
  if (!state.modeDark) {
    state.note = "Single mode only. A Light/Dark variable pair needs a Figma plan that allows multiple modes, so the semantic phase is skipped by design " +
      "and the mapping lives in tools/figma-export/canonical/semantic-tokens.json instead. This is NOT an error.";
    state.emptyAndUnused = Object.keys(state.variables).length === 0;
  }
  return state;
}

// ---------------- dry run ----------------

async function dryRun() {
  await loadAllPages();
  var report = { mutations: [], bindings: [], variables: [], textStyles: [], collection: null, counts: {}, warnings: [] };

  report.collection = await readCollectionState();
  report.starterMode = !report.collection.canBind;
  if (report.collection.note) report.warnings.push(report.collection.note);
  if (report.collection.emptyAndUnused)
    report.warnings.push("The '" + REPAIR_PLAN.collectionName + "' collection is empty (0 variables) and nothing binds to it. " +
      "It is a leftover from the failed run and carries no value. Deleting it is offered as a separate, explicit action - it is never part of Apply.");

  for (var i = 0; i < REPAIR_PLAN.mutations.length; i++) {
    var m = REPAIR_PLAN.mutations[i];
    var node = await getNode(m.id);
    var c = classify(node, m);
    report.mutations.push({ group: m.group, theme: m.theme, op: m.op, id: m.id, path: m.path,
      expect: m.expect, value: m.value, reason: m.reason, current: c.current, status: c.status });
  }

  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var name = names[t], tok = REPAIR_PLAN.tokens[name];
    var existing = report.collection.variables[name];
    report.variables.push({ name: name, dark: tok.dark, light: tok.light, bound: tok.bind,
      status: existing ? "ALREADY_APPLIED" : (report.starterMode ? "SKIPPED_PLAN_LIMIT" : "WILL_CREATE") });
  }

  // bindings: READY only if the collection can actually carry two modes
  for (var b = 0; b < REPAIR_PLAN.tokenBindings.length; b++) {
    var bind = REPAIR_PLAN.tokenBindings[b];
    for (var side = 0; side < 2; side++) {
      var s = side === 0 ? bind.dark : bind.light;
      var theme = side === 0 ? "dark" : "light";
      var n = await getNode(s.id);
      var e = { token: bind.token, prop: bind.prop, theme: theme, id: s.id, path: s.path, nodeName: bind.nodeName, expect: s.current };
      if (!n) { e.status = "SKIP_MISSING"; e.current = "(node not found)"; }
      else {
        var paint = solidOf(n, bind.prop);
        e.current = paint ? rgbToHex(paint.color) : "(no solid paint)";
        var boundTo = paint && paint.boundVariables && paint.boundVariables.color ? paint.boundVariables.color.id : null;
        var want = report.collection.variables[bind.token];
        if (boundTo && want && boundTo === want.id) e.status = "ALREADY_APPLIED";
        else if (report.starterMode) e.status = "SKIPPED_PLAN_LIMIT";
        else e.status = paint && e.current === s.current ? "READY" : "SKIP_CHANGED";
      }
      report.bindings.push(e);
    }
  }

  var localStyles = await figma.getLocalTextStylesAsync();
  for (var ts = 0; ts < REPAIR_PLAN.textStyles.length; ts++) {
    var st = REPAIR_PLAN.textStyles[ts];
    var found = false;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) found = true;
    report.textStyles.push({ name: st.name, spec: st.family + " " + st.style + " " + st.size + "/" + st.lineHeight,
      sample: st.sample, status: found ? "ALREADY_APPLIED" : "WILL_CREATE" });
  }

  var count = function (arr, s) { var n = 0; for (var i = 0; i < arr.length; i++) if (arr[i].status === s) n++; return n; };
  report.counts = {
    mutationsReady: count(report.mutations, "READY"),
    mutationsApplied: count(report.mutations, "ALREADY_APPLIED"),
    mutationsChanged: count(report.mutations, "SKIP_CHANGED"),
    mutationsMissing: count(report.mutations, "SKIP_MISSING"),
    bindingsReady: count(report.bindings, "READY"),
    bindingsApplied: count(report.bindings, "ALREADY_APPLIED"),
    bindingsBlocked: count(report.bindings, "SKIPPED_PLAN_LIMIT"),
    bindingsOther: report.bindings.length - count(report.bindings, "READY") - count(report.bindings, "ALREADY_APPLIED") - count(report.bindings, "SKIPPED_PLAN_LIMIT"),
    variablesToCreate: count(report.variables, "WILL_CREATE"),
    variablesExisting: count(report.variables, "ALREADY_APPLIED"),
    stylesToCreate: count(report.textStyles, "WILL_CREATE"),
    stylesExisting: count(report.textStyles, "ALREADY_APPLIED")
  };

  verifiedPlan = report;
  return report;
}

// ---------------- apply ----------------

/*
 * Starter-compatible: this NEVER calls addMode(). A Light/Dark variable pair needs
 * a plan that allows multiple modes; without it the semantic phase is skipped
 * entirely and the mapping lives in canonical/semantic-tokens.json instead.
 *
 * Nothing is created here either - creating an unbound single-mode collection is
 * what produced the leftover we now have to clean up.
 */
async function inspectCollection(done) {
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) return { collection: null, modeLight: null, modeDark: null };

  var modeLight = null, modeDark = null;
  for (var m = 0; m < col.modes.length; m++) {
    if (col.modes[m].name === "Light") modeLight = col.modes[m].modeId;
    if (col.modes[m].name === "Dark") modeDark = col.modes[m].modeId;
  }
  return { collection: col, modeLight: modeLight, modeDark: modeDark };
}

// Explicit, separate action. Never part of Apply.
async function deleteEmptyCollection() {
  var collections = await figma.variables.getLocalVariableCollectionsAsync();
  var col = null;
  for (var i = 0; i < collections.length; i++) if (collections[i].name === REPAIR_PLAN.collectionName) col = collections[i];
  if (!col) return { ok: false, reason: "No collection named " + REPAIR_PLAN.collectionName + " exists." };

  var vars = await figma.variables.getLocalVariablesAsync("COLOR");
  var mine = 0;
  for (var v = 0; v < vars.length; v++) if (vars[v].variableCollectionId === col.id) mine++;
  if (mine > 0) return { ok: false, reason: "Refusing to delete: the collection holds " + mine + " variable(s). Only an empty collection is removed." };

  col.remove();
  return { ok: true, reason: "Deleted the empty collection " + REPAIR_PLAN.collectionName + "." };
}

async function ensureVariables(col, modeLight, modeDark, done) {
  var existing = await figma.variables.getLocalVariablesAsync("COLOR");
  var map = {};
  var names = Object.keys(REPAIR_PLAN.tokens);
  for (var t = 0; t < names.length; t++) {
    var name = names[t], tok = REPAIR_PLAN.tokens[name], variable = null;
    for (var e = 0; e < existing.length; e++) if (existing[e].name === name && existing[e].variableCollectionId === col.id) variable = existing[e];
    if (!variable) {
      try { variable = figma.variables.createVariable(name, col, "COLOR"); done.variables++; }
      catch (err) { done.skipped.push("createVariable('" + name + "'): " + errText(err)); continue; }
    } else {
      done.variablesReused++;
    }
    try {
      if (modeLight) variable.setValueForMode(modeLight, hexToRgb(tok.light));
      if (modeDark) variable.setValueForMode(modeDark, hexToRgb(tok.dark));
    } catch (err) { done.skipped.push("setValueForMode('" + name + "'): " + errText(err)); }
    map[name] = variable;
  }
  return map;
}

async function apply() {
  if (!verifiedPlan) throw new Error("Run Dry Run first. Apply only executes what a Dry Run in this session marked READY.");
  await loadAllPages();
  var done = { mutations: 0, mutationsSkipped: 0, variables: 0, variablesReused: 0, bindings: 0, textStyles: 0, skipped: [], notes: [], semanticSkipped: null, phase: "start" };

  // ---- phase 1: structural + typography ----
  done.phase = "structural";
  for (var i = 0; i < verifiedPlan.mutations.length; i++) {
    var m = verifiedPlan.mutations[i];
    if (m.status !== "READY") { done.mutationsSkipped++; continue; }
    try {
      var node = await getNode(m.id);
      var c = classify(node, m);
      if (c.status === "ALREADY_APPLIED") { done.mutationsSkipped++; continue; }
      if (c.status !== "READY") { done.skipped.push(m.op + " " + m.id + ": " + c.status + " (now " + c.current + ")"); continue; }

      if (m.op === "setLayoutSizingHorizontal") node.layoutSizingHorizontal = m.value;
      else if (m.op === "setConstraints") node.constraints = JSON.parse(m.value);
      else if (m.op === "setVisible") node.visible = (m.value === "true");
      else if (m.op === "renameNode") node.name = m.value;
      else if (m.op === "deleteNode") node.remove();
      else if (m.op === "setAutoLayoutHug") { node.layoutMode = "HORIZONTAL"; node.layoutSizingHorizontal = "HUG"; node.layoutSizingVertical = "HUG"; }
      else if (m.op === "reorderChild") {
        // Layer order only. insertChild moves an existing child; geometry,
        // visibility, fills and text are untouched.
        if (!node.parent) { done.skipped.push(m.id + ": no parent"); continue; }
        node.parent.insertChild(m.toIndex, node);
      }
      else if (m.op === "setFontStyle" || m.op === "setFontFamily") {
        var target = { family: "Muller", style: m.op === "setFontStyle" ? m.value.split("/")[1] : "Regular" };
        try { await figma.loadFontAsync(target); }
        catch (e) { done.skipped.push(m.id + ": font unavailable " + target.family + " " + target.style + " - " + errText(e)); continue; }
        node.fontName = target;
      }
      done.mutations++;
    } catch (e) {
      done.skipped.push(m.op + " " + m.id + " threw: " + errText(e));
    }
  }

  // ---- phase 2: semantic variables (skipped unless two modes already exist) ----
  done.phase = "semantic";
  var col = null, modeLight = null, modeDark = null;
  try {
    var res = await inspectCollection(done);
    col = res.collection; modeLight = res.modeLight; modeDark = res.modeDark;
  } catch (e) { done.skipped.push("collection inspect: " + errText(e)); }

  var vars = null;
  if (!col || !modeLight || !modeDark) {
    done.semanticSkipped = "SKIPPED_PLAN_LIMIT - a Light/Dark variable pair needs a Figma plan that allows multiple modes. " +
      "The approved mapping lives in tools/figma-export/canonical/semantic-tokens.json and is the source of truth for Android. " +
      "Nothing was created, and no node was bound: binding to a single-mode variable would make both pages resolve to the same colour.";
    done.notes.push("Semantic phase skipped (Starter-compatible path). Text styles still run.");
  } else {
    done.phase = "variables";
    try { vars = await ensureVariables(col, modeLight, modeDark, done); }
    catch (e) { done.skipped.push("variables: " + errText(e)); vars = null; }

    if (vars) {
      done.phase = "bindings";
      for (var b = 0; b < verifiedPlan.bindings.length; b++) {
        var e2 = verifiedPlan.bindings[b];
        if (e2.status !== "READY") continue;
        try {
          var n = await getNode(e2.id);
          if (!n) { done.skipped.push(e2.id + " vanished"); continue; }
          var paint = solidOf(n, e2.prop);
          if (!paint) { done.skipped.push(e2.id + " has no solid paint"); continue; }
          if (paint.boundVariables && paint.boundVariables.color) continue;
          if (rgbToHex(paint.color) !== e2.expect) { done.skipped.push(e2.id + " colour changed since dry run"); continue; }
          var variable = vars[e2.token];
          if (!variable) { done.skipped.push(e2.id + ": no variable " + e2.token); continue; }
          var bound = figma.variables.setBoundVariableForPaint(paint, "color", variable);
          var arr = n[e2.prop].slice();
          for (var k = 0; k < arr.length; k++) if (arr[k] === paint) { arr[k] = bound; break; }
          n[e2.prop] = arr;
          done.bindings++;
        } catch (err) { done.skipped.push("binding " + e2.id + " threw: " + errText(err)); }
      }
    }
  }

  // ---- phase 3: text styles (independent of everything above) ----
  done.phase = "textStyles";
  var localStyles = await figma.getLocalTextStylesAsync();
  for (var t = 0; t < REPAIR_PLAN.textStyles.length; t++) {
    var st = REPAIR_PLAN.textStyles[t];
    var exists = false;
    for (var q = 0; q < localStyles.length; q++) if (localStyles[q].name === st.name) exists = true;
    if (exists) continue;
    try {
      await figma.loadFontAsync({ family: st.family, style: st.style });
      var style = figma.createTextStyle();
      style.name = st.name;
      style.fontName = { family: st.family, style: st.style };
      style.fontSize = st.size;
      style.lineHeight = { unit: "PIXELS", value: st.lineHeight };
      done.textStyles++;
    } catch (err) { done.skipped.push("text style " + st.name + ": " + errText(err)); }
  }

  done.phase = "done";
  return done;
}

// ---------------- message pump ----------------

figma.ui.onmessage = async function (msg) {
  if (!msg) return;
  if (msg.type === "dryrun") {
    try {
      figma.ui.postMessage({ type: "status", text: "Reading document…" });
      figma.ui.postMessage({ type: "dryrun-result", report: await dryRun() });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Dry Run failed: " + errText(e) });
    }
  } else if (msg.type === "delete-empty-collection") {
    try {
      figma.ui.postMessage({ type: "delete-result", result: await deleteEmptyCollection() });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Delete failed: " + errText(e) });
    }
  } else if (msg.type === "apply") {
    var phase = "unknown";
    try {
      figma.ui.postMessage({ type: "status", text: "Applying…" });
      var done = await apply();
      phase = done.phase;
      figma.ui.postMessage({ type: "apply-result", done: done });
    } catch (e) {
      figma.ui.postMessage({ type: "error", text: "Apply failed during phase '" + phase + "': " + errText(e) });
    }
  }
};
