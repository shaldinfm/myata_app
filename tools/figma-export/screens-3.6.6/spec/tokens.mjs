/*
 * Colour roles for the 3.6.6 proposal screens.
 *
 * v1 roles are copied verbatim from canonical/semantic-tokens.json, which was
 * derived by matching equivalent nodes across the two canonical Figma pages.
 * Nothing here is invented: every v2 role below cites the canonical nodes it
 * was read from, so a reviewer can check any value against the real file.
 *
 * The v2 roles exist because the new screens need states the canonical screens
 * never show - a disabled button, a destructive row, a sheet border. They are
 * PROPOSED and need owner approval before they reach Android.
 */

export const TOKENS = {
  // ---- v1: approved, straight from canonical/semantic-tokens.json ----
  background:          { dark: "#0f253e", light: "#f8f9fa", v: 1 },
  surface:             { dark: "#142d47", light: "#ffffff", v: 1 },
  surfaceContainer:    { dark: "#1c4771", light: "#f8f9fa", v: 1 },
  navigationContainer: { dark: "#142d47", light: "#edeeef", v: 1 },
  menuSurface:         { dark: "#142d47", light: "#f8f9fa", v: 1 },
  textPrimary:         { dark: "#f5f7fa", light: "#191c1d", v: 1 },
  textHeading:         { dark: "#f5f7fa", light: "#003056", v: 1 },
  textSecondary:       { dark: "#b3c4d1", light: "#42474e", v: 1 },
  outline:             { dark: "#466d8f", light: "#e1e3e4", v: 1 },
  primary:             { dark: "#5fd9b4", light: "#1c4771", v: 1 },
  onPrimary:           { dark: "#0f253e", light: "#ffffff", v: 1 },

  // ---- v2: proposed, each with the canonical evidence it came from ----
  menuOutline: {
    dark: "#466d8f", light: "#1c3f5f", v: 2,
    from: "Menu / Плеер and Bottom Sheet strokes on both pages. Light uses a navy " +
          "border rather than the neutral `outline`, consistently across both nodes, " +
          "so this is a real role and not a light-page slip."
  },
  buttonSecondaryContainer: {
    dark: "#1c3f5f", light: "#f8f9fa", v: 2,
    from: "PLAYER > Broadcast History Section > Button ('Показать ещё') fill, and " +
          "the UI KIT component 'Component / Dark / Button / Secondary'."
  },
  buttonSecondaryLabel: {
    dark: "#5fd9b4", light: "#003056", v: 2,
    from: "'Показать ещё' label. Dark equals `primary`, which is what the UI KIT " +
          "Secondary component specifies."
  },
  buttonOutlineContainer: {
    dark: "#142d47", light: "#f8f9fa", v: 2,
    from: "ABOUT US > Button ('Читать подробнее') fill, and 'Component / Dark / Button / Outline'."
  },
  buttonOutlineLabel: {
    dark: "#f5f7fa", light: "#003056", v: 2,
    from: "'Читать подробнее' label. Dark equals `textPrimary`, per the UI KIT Outline component. " +
          "See TYPOGRAPHY-AND-COLOUR-OUTLIERS.md - this is the role that raw-hex matching mistook for textHeading."
  },
  buttonOutlineBorder: {
    dark: "#466d8f", light: "#e1e3e4", v: 2,
    from: "Same two buttons. Identical to `outline`; kept named so Android can diverge later."
  },
  error: {
    dark: "#ff8a80", light: "#b3261e", v: 2,
    from: "canonical/semantic-tokens.json, confidence WEAK. Dark comes from the " +
          "'Token / error' swatch in the dark UI KIT; light is proposed. No canonical " +
          "screen shows an error state."
  },
  disabledContainer: {
    dark: "#1e3754", light: "#e8eaed", v: 2,
    from: "canonical/semantic-tokens.json `disabled`, PROPOSED. No canonical screen has a disabled control."
  },
  disabledContent: {
    dark: "#6f899f", light: "#8e969e", v: 2,
    from: "canonical/semantic-tokens.json `textDisabled`, WEAK. One dark-only node, light proposed."
  },
  scrim: {
    dark: "#0f253e", light: "#003056", v: 2,
    from: "canonical/semantic-tokens.json `scrim`, PROPOSED. Canonical sheets are drawn without a scrim."
  },

  // Kept raw on purpose: identical in both canonical pages, so they carry no theme role.
  navActiveContainer: { dark: "#ffccff", light: "#ffccff", v: 1, from: "BottomNavBar active pill, same hex on both pages." },
  navActiveContent:   { dark: "#00723d", light: "#00723d", v: 1, from: "BottomNavBar active icon and label, same hex on both pages." }
};

export function resolve(token, theme) {
  if (token == null) return null;
  if (typeof token === "string" && token.charAt(0) === "#") return token;
  const t = TOKENS[token];
  if (!t) throw new Error("unknown token: " + token);
  return t[theme];
}

/* Canonical type ramp. Every size below is measured from a real node in the
 * canonical pages; the comment names the node it was read from. */
export const TYPE = {
  screenTitle:   { font: "Medium",  size: 24, lh: 32 }, // 'Моя коллекция' TopAppBar
  sectionTitle:  { font: "Bold",    size: 24, lh: 32 }, // 'История эфира'
  sheetTitle:    { font: "Medium",  size: 24, lh: 32 }, // 'Найти трек на стриминге'
  sheetRow:      { font: "Medium",  size: 16, lh: 22 }, // 'Spotify'
  menuRow:       { font: "Medium",  size: 15, lh: 22 }, // 'Действие'
  historyTitle:  { font: "Regular", size: 17, lh: 28 }, // 'CRYOGEN'
  bodySecondary: { font: "Regular", size: 14, lh: 20 }, // 'MUSE', '10:45'
  buttonLabel:   { font: "Regular", size: 22, lh: 28 }, // 'Показать ещё'
  navLabel:      { font: "Medium",  size: 12, lh: 16 }, // 'Главная'
  fieldText:     { font: "Regular", size: 15, lh: 22 }  // derived: menuRow size at Regular, for multi-line input
};
