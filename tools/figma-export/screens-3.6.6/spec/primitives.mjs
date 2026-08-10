/*
 * Canonical layout primitives.
 *
 * Every number here was measured from the two canonical Figma pages, not chosen.
 * Where a new screen needs something the canonical screens never show (a back
 * arrow, a text field), the primitive says so in a `derived` note and explains
 * what it was derived from.
 */
import { TYPE } from "./tokens.mjs";

export const SCREEN_W = 390;
export const MARGIN = 16;
export const CONTENT_W = 358;   // 390 - 2*16, the width of every canonical card
export const APPBAR_H = 64;     // 'Header - TopAppBar' on all canonical screens
export const NAV_H = 76;        // 'BottomNavBar'
export const CONTENT_TOP = 80;  // 'Main' starts at y=80 on every canonical screen

/* ---------- node constructors ---------- */

/* `al` turns a frame into a Figma auto-layout frame, which is what lets a row
 * grow with its content instead of clipping it. Children of an al frame are laid
 * out in flow; their x/y are ignored. `hug: "HEIGHT"` means the frame's height is
 * whatever its content needs - the h in the spec is then only a nominal value for
 * the preview and the validator.
 *
 *   al: { mode:"HORIZONTAL"|"VERTICAL", gap, pad:[t,r,b,l], align:"MIN"|"CENTER", hug:"HEIGHT" }
 */
export const F = (n, o = {}, ch = []) => ({
  n, t: "FRAME", x: o.x || 0, y: o.y || 0, w: o.w, h: o.h,
  fill: o.fill ?? null, stroke: o.stroke ?? null, sw: o.sw ?? (o.stroke ? 1 : 0),
  r: o.r ?? 0, opacity: o.opacity,
  al: o.al ?? null, grow: o.grow ?? 0, ch
});

/* `wrap: true` lets the text run onto as many lines as it needs: the width stays
 * fixed and the height follows. Nothing in these screens truncates with an
 * ellipsis - a track called "Краснознамённая дивизия имени моей бабушки" has to
 * be readable in full. */
export const T = (n, s, o = {}) => ({
  n, t: "TEXT", x: o.x || 0, y: o.y || 0, w: o.w, h: o.h ?? (o.ty?.lh ?? 20),
  fill: o.fill ?? "textPrimary", grow: o.grow ?? 0,
  text: { s, font: (o.ty || TYPE.bodySecondary).font, size: (o.ty || TYPE.bodySecondary).size,
          lh: (o.ty || TYPE.bodySecondary).lh, align: o.align || "LEFT",
          valign: o.valign || "TOP", wrap: !!o.wrap }
});

export const V = (n, o = {}) => ({
  n, t: "VECTOR", x: o.x || 0, y: o.y || 0, w: o.w ?? 24, h: o.h ?? 24,
  path: o.path, fill: o.fill ?? null, stroke: o.stroke ?? null, sw: o.sw ?? 2,
  cap: o.cap || "ROUND"
});

export const E = (n, o = {}) => ({
  n, t: "ELLIPSE", x: o.x || 0, y: o.y || 0, w: o.w, h: o.h,
  fill: o.fill ?? null, stroke: o.stroke ?? null, sw: o.sw ?? (o.stroke ? 1 : 0)
});

/* A reference to a real node elsewhere in the Figma file - a brand mark or a
 * canonical control. The plugin clones that node and retints it; nothing about
 * the artwork is authored here. See spec/assets.json. */
export const A = (key, o = {}) => ({
  n: o.n || ("Asset / " + key), t: "ASSET", key,
  x: o.x || 0, y: o.y || 0, w: o.w ?? 24, h: o.h ?? 24,
  tint: o.tint ?? "textSecondary",
  // Shape of the placeholder slot in the HTML preview only. The plugin clones
  // the real node and ignores this. Set it where the container geometry is a
  // known canonical fact and only the glyph inside is unknown.
  slotRadius: o.slotRadius ?? 4
});

/* ---------- icons ----------
 * Authored here as plain 24x24 geometry. The canonical icon set is artwork and
 * is deliberately not copied; these read correctly at size and are meant to be
 * swapped for the real icons when the screens are implemented. */
export const ICON = {
  back:      "M15 4 L7 12 L15 20",
  clock:     "M12 3 A9 9 0 1 0 12.01 3 M12 7 L12 12 L16 14",
  check:     "M5 12.5 L10 17.5 L19 6.5",
  dotsV:     "M12 5.5 L12 5.6 M12 12 L12 12.1 M12 18.4 L12 18.5",
  trash:     "M4 7 H20 M9 7 V4 H15 V7 M6 7 L7 20 H17 L18 7 M10 11 V16 M14 11 V16",
  download:  "M12 3 V15 M7 10.5 L12 15.5 L17 10.5 M4 20 H20",
  playNote:  "M9 18 A2.5 2.5 0 1 0 9 13 A2.5 2.5 0 1 0 9 18 M11.5 15.5 V4 L20 6 V17 M17.5 19.5 A2.5 2.5 0 1 0 17.5 14.5 A2.5 2.5 0 1 0 17.5 19.5",
  pause:     "M9 5 V19 M15 5 V19",
  headphone: "M4 15 V12 A8 8 0 0 1 20 12 V15 M4 14 H7 V20 H4 Z M20 14 H17 V20 H20 Z",
  layout:    "M4 5 H20 V19 H4 Z M4 10 H20 M10 10 V19",
  question:  "M12 3 A9 9 0 1 0 12.01 3 M9.2 9.2 A3 3 0 1 1 12 13 V15 M12 18 V18.1",
  alert:     "M12 3 A9 9 0 1 0 12.01 3 M12 7 V13 M12 16.5 V16.6",
  inbox:     "M3 13 H8 L10 16 H14 L16 13 H21 M3 13 L5.5 5 H18.5 L21 13 V19 H3 Z",
  disc:      "M12 4 A8 8 0 1 0 12.01 4 M12 10.5 A1.5 1.5 0 1 0 12.01 10.5",
  doc:       "M6 3 H14 L18 7 V21 H6 Z M14 3 V7 H18 M9 12 H15 M9 16 H15",
  timerOff:  "M12 3 A9 9 0 1 0 12.01 3 M6 6 L18 18",
  // semantic replacements for the generic circle, per the visual review
  person:    "M12 4 A4 4 0 1 0 12.01 4 M4.5 20.5 A7.5 7.5 0 0 1 19.5 20.5",
  theme:     "M12 3 A9 9 0 1 0 12.01 3 M12 3 V21 M12 6 A6 6 0 0 1 12 18",
  equalizer: "M5 20 V12 M10 20 V4 M15 20 V15 M20 20 V8",
  message:   "M4 5 H20 V16 H12 L7 20.5 V16 H4 Z M8 10.5 H16",
  info:      "M12 3 A9 9 0 1 0 12.01 3 M12 11 V17 M12 7.4 V7.5",
  sync:      "M4.5 12 A7.5 7.5 0 0 1 17.6 7.1 M18 3.5 V7.5 H14 M19.5 12 A7.5 7.5 0 0 1 6.4 16.9 M6 20.5 V16.5 H10",
  logout:    "M14 4 H19 V20 H14 M10 8 L6 12 L10 16 M6 12 H16"
};

export const icon = (name, o = {}) =>
  F("Icon / " + name, { x: o.x, y: o.y, w: 24, h: 24 }, [
    V(name, { w: 24, h: 24, path: ICON[name], stroke: o.color ?? "textSecondary", sw: o.sw ?? 1.8 })
  ]);

/* ---------- canonical components ---------- */

/* 'Header - TopAppBar', 390x64, fill = background.
 * Title x=16 y=16, Medium/24/32, textHeading. Trailing 'Button:margin' x=342 y=13.
 * `back` is derived: no canonical screen is a detail screen, so none has one.
 * It follows the trailing button's 24px optical size and pushes the title to x=56. */
export function topAppBar(title, o = {}) {
  const ch = [];
  if (o.back) ch.push(F("Button:margin", { x: 12, y: 20, w: 24, h: 24 }, [icon("back", { color: "textPrimary" })]));
  ch.push(F("Heading 1", { x: o.back ? 56 : 16, y: 16, w: 300, h: 32 }, [
    T(title, title, { w: 300, h: 32, ty: TYPE.screenTitle, fill: "textHeading" })
  ]));
  if (o.trailing)
    ch.push(F("Button:margin", { x: 342, y: 20, w: 24, h: 24 }, [icon(o.trailing, { color: "textPrimary" })]));
  return F("Header - TopAppBar", { w: SCREEN_W, h: APPBAR_H, fill: "background" }, ch);
}

/* 'BottomNavBar', 390x76 at the bottom, fill = navigationContainer.
 * Item metrics read off HOME/COLLECTION: active pill 94x48 r9999. */
export function bottomNav(active, screenH) {
  const items = [
    { label: "Главная", ic: "layout", w: 79, x: 23 },
    { label: "Плеер", ic: "playNote", w: 68, x: 117 },
    { label: "Коллекция", ic: "disc", w: 94, x: 200 },
    { label: "О нас", ic: "question", w: 64, x: 308 }
  ];
  return F("BottomNavBar", { y: screenH - NAV_H, w: SCREEN_W, h: NAV_H, fill: "navigationContainer" },
    items.map((it) => {
      const on = it.label === active;
      return F(on ? "Background" : "Container",
        { x: it.x, y: 12, w: it.w, h: 48, fill: on ? "navActiveContainer" : null, r: on ? 9999 : 0 }, [
        icon(it.ic, { x: (it.w - 24) / 2, y: 2, color: on ? "navActiveContent" : "textSecondary" }),
        T(it.label, it.label, { x: 0, y: 28, w: it.w, h: 16, ty: TYPE.navLabel, align: "CENTER",
                                fill: on ? "navActiveContent" : "textSecondary" })
      ]);
    })
  );
}

/* 'Bottom Sheet / …', 358 wide, r28, fill menuSurface, stroke menuOutline.
 * Handle 40x4 at (159,16) r2. Title at (24,40) Medium/24/32.
 * Canonical height is a fixed 600 with 278px of empty space below the last row;
 * new sheets size to content on the same rhythm instead of copying that number. */
export const SHEET = { w: CONTENT_W, titleY: 40, rowTop: 92, rowH: 56, pitch: 58, padBottom: 24 };

export function sheetShell(name, title, h, ch, o = {}) {
  const head = [
    F("Bottom Sheet / drag handle", { x: 159, y: 16, w: 40, h: 4, fill: "outline", r: 2 }),
    T("Bottom Sheet / title", title, { x: 24, y: SHEET.titleY, w: 300, h: 32, ty: TYPE.sheetTitle, fill: "textHeading" })
  ];
  if (o.subtitle)
    head.push(T("Bottom Sheet / subtitle", o.subtitle, { x: 24, y: 78, w: 310, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" }));
  return F(name, { w: SHEET.w, h, fill: "menuSurface", stroke: "menuOutline", r: 28 }, head.concat(ch));
}

/* 'Sheet row / …', 358x56. Icon 48x48 at (16,+4) r24, label at x=76 Medium/16/22. */
export function sheetRow(label, o = {}) {
  const leading = o.asset
    ? A(o.asset, { x: 12, y: 12, w: 24, h: 24, tint: o.iconColor || "textPrimary" })
    : icon(o.ic || "disc", { x: 12, y: 12, color: o.iconColor || "primary" });
  const ch = [
    F("Sheet / leading icon", { x: 16, y: 4, w: 48, h: 48, r: 24 }, [leading]),
    T("Sheet / label", label, { x: 76, y: 16, w: o.trailing ? 104 : (o.chevron ? 240 : 266), h: 24,
                                ty: TYPE.sheetRow, fill: o.labelColor || "textPrimary" })
  ];
  if (o.trailing)
    ch.push(T("Sheet / trailing", o.trailing, { x: 180, y: 18, w: 162, h: 20, ty: TYPE.bodySecondary, align: "RIGHT", fill: "textSecondary" }));
  if (o.chevron)
    ch.push(F("Sheet / chevron", { x: 318, y: 16, w: 24, h: 24 }, [
      V("chevron", { w: 24, h: 24, path: "M9 5 L16 12 L9 19", stroke: "textSecondary", sw: 1.8 })
    ]));
  return F("Sheet row / " + label, { y: o.y, w: SHEET.w, h: SHEET.rowH }, ch);
}

/* 'Menu / Плеер', r20, fill menuSurface, stroke menuOutline.
 * Canonical is 206 wide with rows 184x48 at x=11, pitch 52, label x=70 Medium/15.
 * Width is a parameter because 206 cannot hold a label plus a trailing value. */
export function menu(name, rows, o = {}) {
  const w = o.w || 206;
  // canonical 'Menu / Плеер' is 264 tall for 4 rows: 10 top, 52 pitch, 50 bottom
  const h = 10 + rows.length * 52 + 46;
  return F(name, { w, h, fill: "menuSurface", stroke: "menuOutline", r: 20 },
    rows.map((row, i) => {
      const ch = [
        F("Menu / action icon", { x: 12, y: 0, w: 48, h: 48, r: 24 }, [icon(row.ic || "disc", { x: 12, y: 12, color: "textSecondary" })]),
        T("Menu / label", row.label, { x: 70, y: 13, w: row.trailing ? w - 22 - 70 - 70 : w - 22 - 70 - 8, h: 22,
                                       ty: TYPE.menuRow, fill: row.danger ? "error" : "textPrimary" })
      ];
      if (row.trailing)
        ch.push(T("Menu / trailing", row.trailing, { x: w - 22 - 70 - 8, y: 14, w: 70, h: 20,
                                                     ty: TYPE.bodySecondary, align: "RIGHT", fill: "primary" }));
      return F("Menu row / " + row.label, { x: 11, y: 10 + i * 52, w: w - 22, h: 48 }, ch);
    })
  );
}

/* Buttons. Filled = 'Component / Dark / Button / Primary'; the outline and
 * secondary variants are the same components with their canonical fills. */
export function button(label, o = {}) {
  const w = o.w ?? CONTENT_W, h = o.h ?? 52;
  const kind = o.kind || "primary";
  const spec = {
    primary:   { fill: "primary", stroke: null, label: "onPrimary" },
    secondary: { fill: "buttonSecondaryContainer", stroke: "buttonOutlineBorder", label: "buttonSecondaryLabel" },
    outline:   { fill: "buttonOutlineContainer", stroke: "buttonOutlineBorder", label: "buttonOutlineLabel" },
    disabled:  { fill: "disabledContainer", stroke: null, label: "disabledContent" }
  }[kind];
  const ch = [T(label, label, { x: 0, y: (h - TYPE.buttonLabel.lh) / 2, w, h: TYPE.buttonLabel.lh,
                                ty: TYPE.buttonLabel, align: "CENTER", fill: spec.label })];
  if (o.leading) ch.unshift(icon(o.leading, { x: 24, y: (h - 24) / 2, color: spec.label }));
  return F("Button" + (o.name ? " / " + o.name : ""), { x: o.x ?? 0, y: o.y, w, h, r: 12, fill: spec.fill, stroke: spec.stroke }, ch);
}

/* Card: r24, fill surface, stroke outline 1 - 'Track Item', 'Broadcast History Section'. */
export const card = (n, o, ch) =>
  F(n, { x: o.x ?? MARGIN, y: o.y, w: o.w ?? CONTENT_W, h: o.h, r: o.r ?? 24, fill: "surface", stroke: "outline" }, ch);

/* 'History Item', 324x74 r8 inside the PLAYER card. Promoted to full content
 * width here because the standalone screen has no outer card to sit in.
 *
 * The canonical row leaves a 64px gap between the time column (ends at +39) and
 * the text column (starts at +103) with no child in it - that gap is the album
 * art slot, and a 48px thumbnail centred in it lands the text back on the
 * canonical x. The 48x48 r8 thumbnail geometry is the mini player's.
 *
 * One trailing action only: the canonical 'find track' control cloned from the
 * Collection track item, so History and Collection share a single pattern. */
export const HISTORY_ART = { w: 48, h: 48, r: 8 };

/* The row is a horizontal auto-layout so it grows with its text. A uniform 8px
 * gap and 13px padding reproduce every canonical anchor exactly:
 *
 *   13 time 39 | 8 | 60 art 48 | 8 | 116 text 181 | 8 | 305 action 40 | 13
 *
 * A single-line row therefore comes out at 13 + 48 + 13 = 74, which is the
 * canonical height; a two-line title simply makes it taller. Cross-axis
 * alignment is MIN so the time stays at the top rather than drifting to the
 * middle of a tall title. */
export const HISTORY_ROW = { pad: 13, gap: 8, timeW: 39, textW: 181, actionW: 40 };

const historyRowFrame = (name, ch) =>
  F(name, {
    w: CONTENT_W, h: 74, r: 8, stroke: "outline",
    al: { mode: "HORIZONTAL", gap: HISTORY_ROW.gap, pad: [13, 13, 13, 13], align: "MIN", hug: "HEIGHT" }
  }, ch);

export function historyItem(o) {
  return historyRowFrame("History Item / " + o.title, [
    // 28 tall with centred text so it sits on the title's first line, not above it
    T("time", o.time, { w: HISTORY_ROW.timeW, h: 28, ty: TYPE.bodySecondary, fill: "textSecondary", valign: "CENTER" }),
    F("Album art", { ...HISTORY_ART, fill: "surfaceContainer" }),
    F("Text", { w: HISTORY_ROW.textW, h: 48, grow: 1,
                al: { mode: "VERTICAL", gap: 0, pad: [0, 0, 0, 0], align: "MIN", hug: "HEIGHT" } }, [
      T("title", o.title, { w: HISTORY_ROW.textW, h: 28, ty: TYPE.historyTitle, fill: "textPrimary", wrap: true }),
      T("artist", o.artist, { w: HISTORY_ROW.textW, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary", wrap: true })
    ]),
    A("control/find-track", { n: "Button / find track", w: 40, h: 40, tint: "primary", slotRadius: 20 })
  ]);
}

/* Same frame, same anchors, placeholders instead of content - so nothing moves
 * horizontally when the data arrives. */
export function historySkeleton(n) {
  return historyRowFrame("Skeleton " + n, [
    F("s-time", { w: HISTORY_ROW.timeW, h: 28 }, [
      F("bar", { x: 0, y: 8, w: 34, h: 12, r: 6, fill: "outline", opacity: 0.45 })
    ]),
    F("s-art", { ...HISTORY_ART, fill: "outline", opacity: 0.5 }),
    F("s-text", { w: HISTORY_ROW.textW, h: 48, grow: 1,
                  al: { mode: "VERTICAL", gap: 0, pad: [0, 0, 0, 0], align: "MIN", hug: "HEIGHT" } }, [
      F("s-title", { w: HISTORY_ROW.textW, h: 28 }, [F("bar", { x: 0, y: 6, w: 150, h: 16, r: 8, fill: "outline", opacity: 0.5 })]),
      F("s-artist", { w: HISTORY_ROW.textW, h: 20, }, [F("bar", { x: 0, y: 4, w: 96, h: 12, r: 6, fill: "outline", opacity: 0.35 })])
    ]),
    F("s-action", { w: 40, h: 40, r: 20, stroke: "outline", opacity: 0.5 })
  ]);
}

/* Snackbar. No canonical equivalent - derived from the card metrics
 * (content width, r12 like the buttons, surfaceContainer like the mini player). */
export function snackbar(name, message, o = {}) {
  const h = 56;
  const ch = [
    icon(o.ic || "check", { x: 16, y: 16, color: o.iconColor || "primary" }),
    T("message", message, { x: 52, y: 18, w: o.action ? 200 : 290, h: 20, ty: TYPE.bodySecondary, fill: "textPrimary" })
  ];
  if (o.action)
    ch.push(T("action", o.action, { x: 252, y: 18, w: 90, h: 20, ty: TYPE.bodySecondary, align: "RIGHT", fill: "primary" }));
  return F(name, { w: CONTENT_W, h, r: 12, fill: "surfaceContainer", stroke: "menuOutline" }, ch);
}
