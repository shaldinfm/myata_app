"use strict";

// Radio Myata dark-theme exporter.
// Local-only Figma Plugin API code. It never calls Figma MCP or the Android app.

const PAGE_NAME = "CURRENT ANDROID UI — DARK";
const PAGE_MARKER = "radio-myata-dark-design-export-v1";
const SCREEN_WIDTH = 390;
const MIN_TOUCH = 48;
const KIT_X = 0;
const KIT_Y = 1600;

const TOKENS = Object.freeze({
  background: { light: "#F8F9FA", dark: "#0B1D31" },
  surface: { light: "#FFFFFF", dark: "#132E4A" },
  surfaceContainer: { light: "#F3F4F5", dark: "#1B4163" },
  surfaceElevated: { light: "#FFFFFF", dark: "#28557E" },
  primary: { light: "#1C4771", dark: "#69E5BE" },
  onPrimary: { light: "#FFFFFF", dark: "#0F253E" },
  secondary: { light: "#5FD9B4", dark: "#00E5FF" },
  onSecondary: { light: "#003056", dark: "#0F253E" },
  outline: { light: "#E1E3E4", dark: "#5A82A3" },
  textPrimary: { light: "#003056", dark: "#F5F7FA" },
  textSecondary: { light: "#42474E", dark: "#C3D3DF" },
  activeIndicator: { light: "#FFCCFF", dark: "#FFCCFF" },
  miniPlayer: { light: "#1C4771", dark: "#24587F" },
  error: { light: "#E74C3C", dark: "#FF8A80" },
  supportMonthlyBackground: { light: "#5FD9B4", dark: "#5FD9B4" },
  supportMonthlyContent: { light: "#0F253E", dark: "#0F253E" },
  supportOneTimeBackground: { light: "#204971", dark: "#204971" },
  supportOneTimeContent: { light: "#F5F7FA", dark: "#F5F7FA" },
  navigationContainer: { light: "#F3F4F5", dark: "#102A44" },
  disabledContent: { light: "#9BA6AF", dark: "#6F899F" },
  disabledContainer: { light: "#ECEFF1", dark: "#1E3754" },
  divider: { light: "#E1E3E4", dark: "#426B8D" },
  focusRing: { light: "#00AFC7", dark: "#00E5FF" },
  scrim: { light: "#003056", dark: "#0F253E" },
  myataPink: { light: "#FF3F7B", dark: "#FF3F7B" },
  myataCyan: { light: "#00E5FF", dark: "#00E5FF" },
  goldYellow: { light: "#FFFF00", dark: "#FFFF00" },
  goldGreen: { light: "#2FB56A", dark: "#2FB56A" },
  xtraPink: { light: "#FFCCFF", dark: "#FFCCFF" },
  brandNavy: { light: "#1C3F5F", dark: "#1C3F5F" }
});

const SCREEN_DATA = Object.freeze([
  ["DARK / Home", 0, 757],
  ["DARK / Player", 450, 1022],
  ["DARK / Collection", 900, 740],
  ["DARK / Collection Empty", 1350, 740],
  ["DARK / About & Support", 1800, 1418],
  ["DARK / Player Menu", 2250, 1022],
  ["DARK / Collection Menu", 2700, 740],
  ["DARK / Music Service Bottom Sheet", 3150, 1022],
  ["DARK / Sleep Timer Bottom Sheet", 3600, 1022],
  ["DARK / Report Problem Bottom Sheet", 4050, 1022],
  ["DARK / Full Broadcast History Bottom Sheet", 4500, 1022]
]);

const PATHS = Object.freeze({
  home: "M15.84 71.0628H29.1V46.3086H55.62V71.0628H68.88V33.9316L42.36 15.366L15.84 33.9316V71.0628ZM7 79.3141V29.8059L42.36 5.05176L77.72 29.8059V79.3141H46.78V54.56H37.94V79.3141H7Z",
  player: "M14.1445 79.795C12.1997 79.795 10.5347 79.1025 9.14975 77.7175C7.76476 76.3325 7.07227 74.6676 7.07227 72.7227V30.2891C7.07227 28.8157 7.47008 27.4896 8.26571 26.3109C9.06134 25.1322 10.1369 24.2777 11.4924 23.7472L56.2246 5.53613L58.5231 11.3708L29.3499 23.2168H70.7227C72.6676 23.2168 74.3325 23.9093 75.7175 25.2943C77.1025 26.6793 77.795 28.3441 77.795 30.2891V72.7227C77.795 74.6676 77.1025 76.3325 75.7175 77.7175C74.3325 79.1025 72.6676 79.795 70.7227 79.795H14.1445ZM14.1445 72.7227H70.7227V47.9698H14.1445V72.7227ZM28.2891 69.1866C30.7644 69.1866 32.8566 68.332 34.5657 66.6229C36.2749 64.9138 37.1294 62.8215 37.1294 60.3463C37.1294 57.871 36.2749 55.7787 34.5657 54.0696C32.8566 52.3605 30.7644 51.5059 28.2891 51.5059C25.8138 51.5059 23.7216 52.3605 22.0124 54.0696C20.3033 55.7787 19.4487 57.871 19.4487 60.3463C19.4487 62.8215 20.3033 64.9138 22.0124 66.6229C23.7216 68.332 25.8138 69.1866 28.2891 69.1866ZM14.1445 40.8975H56.5782V33.8252H63.6505V40.8975H70.7227V30.2891H14.1445V40.8975Z",
  collection: "M42.687,55.7753C45.2242,55.7753 47.3901,54.8959 49.1847,53.1372C50.9793,51.3785 51.8766,49.2255 51.8766,46.6784V23.2865H62.6443V17.717H48.1636V39.7166C47.4829,39.1596 46.6685,38.7264 45.7205,38.417C44.7724,38.1076 43.7612,37.9529 42.687,37.9529C40.2698,37.9529 38.2267,38.7997 36.5577,40.4935C34.8887,42.1866 34.0542,44.2597 34.0542,46.7128C34.0542,49.1652 34.8887,51.2887 36.5577,53.0834C38.2267,54.878 40.2698,55.7753 42.687,55.7753ZM21.7085,68.121C20.2233,68.121 18.9238,67.5641 17.8099,66.4502C16.696,65.3363 16.139,64.0367 16.139,62.5515V10.5695C16.139,9.0843 16.696,7.78475 17.8099,6.67085C18.9238,5.55695 20.2233,5 21.7085,5H73.6905C75.1757,5 76.4753,5.55695 77.5892,6.67085C79.26,8.0843 79.26,9.0843 79.26,10.5695V62.5515C79.26,64.0367 78.7031,65.3367 77.5892,66.4502C76.4753,67.5641 75.1757,68.121 73.6905,68.121H21.7085ZM21.7085,62.5515H73.6905V10.5695H21.7085V62.5515ZM10.5695,79.26C9.0843,79.26 7.78475,78.7031 6.67085,77.5892C5.55695,76.4753 5,75.1757 5,73.6905V16.139H10.5695V73.6905H68.121V79.26H10.5695Z",
  info: "M38.8975 60.1143H45.9698V38.8975H38.8975V60.1143ZM42.4336 31.8252C43.4355 31.8252 44.2754 31.4863 44.9531 30.8086C45.6309 30.1308 45.9698 29.291 45.9698 28.2891C45.9698 27.2872 45.6309 26.4473 44.9531 25.7696C44.2754 25.0918 43.4355 24.753 42.4336 24.753C41.4317 24.753 40.5919 25.0918 39.9141 25.7696C39.2364 26.4473 38.8975 27.2872 38.8975 28.2891C38.8975 29.291 39.2364 30.1308 39.9141 30.8086C40.5919 31.4863 41.4317 31.8252 42.4336 31.8252ZM42.4336 77.795C37.542 77.795 32.945 76.8668 28.6427 75.0103C24.3404 73.1538 20.598 70.6343 17.4155 67.4518C14.2329 64.2693 11.7134 60.5269 9.85697 56.2246C8.0005 51.9223 7.07227 47.3253 7.07227 42.4336C7.07227 37.542 8.0005 32.945 9.85697 28.6427C11.7134 24.3404 14.2329 20.598 17.4155 17.4155C20.598 14.2329 24.3404 11.7134 28.6427 9.85697C32.945 8.0005 37.542 7.07227 42.4336 7.07227C47.3253 7.07227 51.9223 8.0005 56.2246 9.85697C60.5269 11.7134 64.2693 14.2329 67.4518 17.4155C70.6343 20.598 73.1538 24.3404 75.0103 28.6427C76.8668 32.945 77.795 37.542 77.795 42.4336C77.795 47.325 76.8668 51.922 75.0103 56.2246C73.1538 60.5269 70.6343 64.2693 67.4518 67.4518C64.2693 70.6343 60.5269 73.1538 56.2246 75.0103C51.9223 76.8668 47.3253 77.795 42.4336 77.795ZM42.4336 70.7227C50.331 70.7227 57.0202 67.9822 62.5012 62.5012C67.9822 57.0202 70.7227 50.331 70.7227 42.4336C70.7227 34.5363 67.9822 27.8471 62.5012 22.3661C57.0202 16.885 50.331 14.1445 42.4336 14.1445C34.5363 14.1445 27.8471 16.885 22.3661 22.3661C16.885 27.8471 14.1445 34.536 14.1445 42.4336C14.1445 50.331 16.885 57.0202 22.3661 62.5012C27.8471 67.9822 34.5363 70.7227 42.4336 70.7227Z"
});

const ICONS = Object.freeze({
  home: `<path d="${PATHS.home}" fill="#COLOR"/>`,
  player: `<path d="${PATHS.player}" fill="#COLOR"/>`,
  collection: `<path d="${PATHS.collection}" fill="#COLOR"/>`,
  info: `<path d="${PATHS.info}" fill="#COLOR"/>`,
  play: `<path d="M8 5v14l11-7L8 5Z" fill="#COLOR"/>`,
  pause: `<path d="M6 5h4v14H6zM14 5h4v14h-4z" fill="#COLOR"/>`,
  dots: `<circle cx="12" cy="5" r="1.7" fill="#COLOR"/><circle cx="12" cy="12" r="1.7" fill="#COLOR"/><circle cx="12" cy="19" r="1.7" fill="#COLOR"/>`,
  account: `<circle cx="12" cy="8" r="3" fill="none" stroke="#COLOR" stroke-width="1.8"/><path d="M5.5 20c.6-3.1 2.8-4.7 6.5-4.7s5.9 1.6 6.5 4.7" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round"/>`,
  arrow: `<path d="M6 18 18 6M8 6h10v10" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>`,
  up: `<path d="M7 10v9H4v-9h3Zm2 9V9.4l3.2-4.2c.5-.7 1.5-.9 2.2-.4.6.4.9 1.2.7 1.9l-.8 3.1H19c1.1 0 2 .9 2 2 0 .2 0 .4-.1.6l-1.5 5.2c-.3 1-1.2 1.7-2.3 1.7H9Z" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linejoin="round"/>`,
  down: `<path d="M17 14V5h3v9h-3Zm-2-9v9.6l-3.2 4.2c-.5.7-1.5.9-2.2.4-.6-.4-.9-1.2-.7-1.9l.8-3.1H5c-1.1 0-2-.9-2-2 0-.2 0-.4.1-.6l1.5-5.2C4.9 5.4 5.8 5 6.9 5H15Z" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linejoin="round"/>`,
  heart: `<path d="M12 20.4 10.5 19C5.4 14.4 2 11.4 2 7.8 2 5.1 4.1 3 6.8 3c1.5 0 3 .7 4 1.9C11.9 3.7 13.4 3 14.9 3 17.6 3 19.7 5.1 19.7 7.8c0 3.6-3.4 6.6-8.5 11.2L12 20.4Z" fill="#COLOR"/>`,
  search: `<circle cx="10.5" cy="10.5" r="5.5" fill="none" stroke="#COLOR" stroke-width="1.8"/><path d="m15 15 5 5" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round"/>`,
  close: `<path d="m6 6 12 12M18 6 6 18" fill="none" stroke="#COLOR" stroke-width="2" stroke-linecap="round"/>`,
  timer: `<circle cx="12" cy="13" r="7" fill="none" stroke="#COLOR" stroke-width="1.8"/><path d="M9 3h6M12 6v7l3 2" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round"/>`,
  warning: `<path d="m12 3 9 17H3L12 3Z" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linejoin="round"/><path d="M12 9v4m0 3h.01" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round"/>`,
  browser: `<rect x="4" y="4" width="16" height="16" rx="2" fill="none" stroke="#COLOR" stroke-width="1.8"/><path d="M4 8h16" fill="none" stroke="#COLOR" stroke-width="1.8"/><circle cx="7" cy="6" r=".8" fill="#COLOR"/>`,
  disc: `<circle cx="12" cy="12" r="8" fill="none" stroke="#COLOR" stroke-width="1.8"/><circle cx="12" cy="12" r="2" fill="#COLOR"/>`,
  menu: `<path d="M5 7h14M5 12h14M5 17h14" fill="none" stroke="#COLOR" stroke-width="1.8" stroke-linecap="round"/>`
});

let tokenVariables = Object.create(null);
let paintStyles = Object.create(null);
let fonts = null;
let assets = [];
let components = Object.create(null);
let variableCollectionId = "";
let darkModeId = "";
let bindVariables = false;

function hexColor(value) {
  const raw = String(value).replace("#", "");
  return {
    r: parseInt(raw.slice(0, 2), 16) / 255,
    g: parseInt(raw.slice(2, 4), 16) / 255,
    b: parseInt(raw.slice(4, 6), 16) / 255
  };
}

function solid(hex, opacity) {
  const result = { type: "SOLID", color: hexColor(hex) };
  if (opacity !== undefined) result.opacity = opacity;
  return result;
}

function paintFor(token, opacity) {
  const hex = TOKENS[token] ? TOKENS[token].dark : (token || "#FFFFFF");
  const paint = solid(hex, opacity === undefined ? 1 : opacity);
  if (bindVariables && tokenVariables[token] && figma.variables && typeof figma.variables.setBoundVariableForPaint === "function") {
    try { return figma.variables.setBoundVariableForPaint(paint, "color", tokenVariables[token]); } catch (error) { /* literal fallback stays correct */ }
  }
  return paint;
}

function mark(node, source) {
  try { node.setPluginData("plugin", PAGE_MARKER); } catch (error) { /* ignored */ }
  try { node.setPluginData("source", source || "Radio Myata dark design system"); } catch (error) { /* ignored */ }
  return node;
}

function owned(node) {
  try { return node.getPluginData("plugin") === PAGE_MARKER; } catch (error) { return false; }
}

function setFill(node, token, opacity) {
  if (!node || !("fills" in node)) return;
  node.fills = token ? [paintFor(token, opacity)] : [];
}

function setStroke(node, token, weight) {
  if (!node || !("strokes" in node)) return;
  node.strokes = token ? [paintFor(token)] : [];
  node.strokeWeight = weight || 1;
}

function appendFrame(parent, name, width, height, x, y, fillToken, radius, source) {
  const frame = figma.createFrame();
  frame.name = name;
  frame.resize(width, height);
  frame.x = x || 0;
  frame.y = y || 0;
  frame.layoutMode = "NONE";
  setFill(frame, fillToken);
  if (radius !== undefined) frame.cornerRadius = radius;
  parent.appendChild(frame);
  applyDarkMode(frame);
  return mark(frame, source);
}

function appendRectangle(parent, name, width, height, x, y, fillToken, radius, source) {
  const node = figma.createRectangle();
  node.name = name;
  node.resize(width, height);
  node.x = x || 0;
  node.y = y || 0;
  setFill(node, fillToken);
  if (radius !== undefined) node.cornerRadius = radius;
  parent.appendChild(node);
  applyDarkMode(node);
  return mark(node, source);
}

function appendEllipse(parent, name, width, height, x, y, fillToken, source) {
  const node = figma.createEllipse();
  node.name = name;
  node.resize(width, height);
  node.x = x || 0;
  node.y = y || 0;
  setFill(node, fillToken);
  parent.appendChild(node);
  applyDarkMode(node);
  return mark(node, source);
}

function appendText(parent, name, value, options) {
  const text = figma.createText();
  text.name = name + (fonts && fonts.fallback ? " [Inter fallback: Muller unavailable]" : "");
  text.fontName = (fonts && fonts[options.weight || "regular"]) || { family: "Inter", style: "Regular" };
  text.fontSize = options.size || 16;
  text.characters = value;
  text.textAutoResize = "NONE";
  text.resize(options.width || 200, options.height || Math.ceil((options.lineHeight || options.size || 16) * 1.25));
  text.textAlignHorizontal = options.align || "LEFT";
  text.textAlignVertical = options.vertical || "TOP";
  text.lineHeight = { value: options.lineHeight || Math.ceil((options.size || 16) * 1.25), unit: "PIXELS" };
  if (options.letterSpacing !== undefined) text.letterSpacing = { value: options.letterSpacing, unit: "PIXELS" };
  text.fills = [paintFor(options.token || "textPrimary")];
  parent.appendChild(text);
  applyDarkMode(text);
  mark(text, options.source || "Android XML typography");
  try { text.setPluginData("semanticToken", options.token || "textPrimary"); } catch (error) { /* ignored */ }
  return text;
}

function iconSvg(icon, hex) {
  const body = ICONS[icon] || ICONS.info;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">${body.replace(/#COLOR/g, TOKENS[hex] ? TOKENS[hex].dark : hex)}</svg>`;
}

function appendIcon(parent, name, icon, token, width, height, x, y, source) {
  const vector = figma.createNodeFromSvg(iconSvg(icon, token || "textPrimary"));
  vector.name = name;
  vector.resize(width || 24, height || 24);
  vector.x = x || 0;
  vector.y = y || 0;
  parent.appendChild(vector);
  return mark(vector, source || "Android vector icon");
}

function appendIconButton(parent, name, icon, token, x, y, backgroundToken, size) {
  const outer = appendFrame(parent, name, size || MIN_TOUCH, size || MIN_TOUCH, x, y, backgroundToken, (size || MIN_TOUCH) / 2, "48dp interactive target");
  if (!backgroundToken) outer.fills = [];
  appendIcon(outer, "Vector / " + icon, icon, token || "textPrimary", 24, 24, ((size || MIN_TOUCH) - 24) / 2, ((size || MIN_TOUCH) - 24) / 2);
  return outer;
}

function addAutoLayout(node, direction, spacing, padding) {
  node.layoutMode = direction;
  node.itemSpacing = spacing || 0;
  node.primaryAxisSizingMode = "FIXED";
  node.counterAxisSizingMode = "FIXED";
  node.paddingLeft = padding || 0;
  node.paddingRight = padding || 0;
  node.paddingTop = padding || 0;
  node.paddingBottom = padding || 0;
  node.counterAxisAlignItems = "CENTER";
  return node;
}

function applyImageFill(node, asset) {
  if (!asset || !Array.isArray(asset.bytes) || typeof figma.createImage !== "function") return false;
  try {
    const image = figma.createImage(new Uint8Array(asset.bytes));
    node.fills = [{ type: "IMAGE", imageHash: image.hash, scaleMode: "FILL" }];
    node.setPluginData("imageAsset", asset.name || "unnamed image");
    node.setPluginData("assetSource", asset.source || "local Android resource");
    return true;
  } catch (error) {
    try { node.setPluginData("imageAssetError", error.message || String(error)); } catch (ignored) { /* ignored */ }
    return false;
  }
}

function rasterAsset(index) {
  const custom = assets.filter((asset) => asset && asset.source === "user-selected artwork");
  const local = assets.filter((asset) => asset && Array.isArray(asset.bytes));
  return custom[index] || local[index % Math.max(1, local.length)] || null;
}

function assetByName(name) {
  return assets.find((asset) => asset && asset.name === name) || null;
}

function vectorAsset(name) {
  return assets.find((asset) => asset && asset.kind === "svg" && asset.name === name) || null;
}

function appendImage(parent, name, width, height, x, y, asset, fallbackToken, radius, source) {
  const node = appendRectangle(parent, name, width, height, x, y, fallbackToken || "surfaceContainer", radius, source || "Android raster resource");
  applyImageFill(node, asset);
  if (!asset) node.setPluginData("imageAsset", "fallback color; artwork not supplied");
  return node;
}

function appendVectorAsset(parent, name, asset, width, height, x, y, fallbackToken, radius) {
  if (!asset || !asset.svg) return appendRectangle(parent, name, width, height, x, y, fallbackToken || "brandNavy", radius, "Android XML vector fallback");
  const vector = figma.createNodeFromSvg(asset.svg);
  vector.name = name;
  vector.resize(width, height);
  vector.x = x || 0;
  vector.y = y || 0;
  parent.appendChild(vector);
  mark(vector, asset.source || "Android XML vector drawable");
  try { vector.setPluginData("assetName", asset.name); } catch (error) { /* ignored */ }
  return vector;
}

function makeComponent(parent, name, width, height, fillToken, radius, source) {
  const component = figma.createComponent();
  component.name = name;
  component.resize(width, height);
  component.layoutMode = "NONE";
  component.clipsContent = true;
  if (fillToken) setFill(component, fillToken);
  if (radius !== undefined) component.cornerRadius = radius;
  parent.appendChild(component);
  applyDarkMode(component);
  mark(component, source || "Radio Myata component");
  return component;
}

function combineVariants(parent, name, variants, source) {
  try {
    const set = figma.combineAsVariants(variants, parent);
    set.name = name;
    mark(set, source || "Radio Myata component variants");
    applyDarkMode(set);
    return set;
  } catch (error) {
    return null;
  }
}

function instance(parent, component, name, x, y) {
  const node = component.createInstance();
  node.name = name;
  node.x = x || 0;
  node.y = y || 0;
  parent.appendChild(node);
  return mark(node, "Instance of " + component.name);
}

function addComponentLabel(parent, value, x, y, width) {
  return appendText(parent, "UI Kit label", value, { size: 12, lineHeight: 16, width: width || 260, height: 18, token: "textSecondary", x, y });
}

function setPosition(node, x, y) {
  node.x = x;
  node.y = y;
  return node;
}

async function setupFonts() {
  let available = [];
  try { available = await figma.listAvailableFontsAsync(); } catch (error) { available = []; }
  const names = available.map((item) => item.fontName);
  function choose(styles, fallbackStyle) {
    const muller = names.find((font) => /muller/i.test(font.family) && styles.some((style) => font.style.toLowerCase() === style.toLowerCase()));
    if (muller) return muller;
    const inter = names.find((font) => /^inter$/i.test(font.family) && font.style.toLowerCase() === fallbackStyle.toLowerCase());
    return inter || { family: "Inter", style: fallbackStyle };
  }
  const result = {
    regular: choose(["Regular", "Book", "Medium"], "Regular"),
    light: choose(["Light", "ExtraLight", "Regular"], "Regular"),
    bold: choose(["Bold", "SemiBold", "Medium"], "Bold"),
    black: choose(["Black", "ExtraBold", "Bold"], "Bold")
  };
  result.fallback = !/muller/i.test(result.regular.family);
  const unique = [];
  Object.keys(result).forEach((key) => {
    if (key !== "fallback" && !unique.some((font) => font.family === result[key].family && font.style === result[key].style)) unique.push(result[key]);
  });
  for (const font of unique) {
    try { await figma.loadFontAsync(font); } catch (error) {
      if (!/Inter/i.test(font.family)) {
        result.fallback = true;
        result.regular = { family: "Inter", style: "Regular" };
        result.light = result.regular;
        result.bold = { family: "Inter", style: "Bold" };
        result.black = result.bold;
        try {
          await figma.loadFontAsync(result.regular);
          await figma.loadFontAsync(result.bold);
        } catch (ignored) { /* Figma will report a missing font in the text layer */ }
      }
    }
  }
  return result;
}

async function setupTokens() {
  tokenVariables = Object.create(null);
  paintStyles = Object.create(null);
  variableCollectionId = "";
  darkModeId = "";
  bindVariables = false;
  if (figma.variables && typeof figma.variables.getLocalVariableCollections === "function") {
    try {
      let collection = figma.variables.getLocalVariableCollections().find((item) => item.name === "Radio Myata / Semantic");
      if (!collection) collection = figma.variables.createVariableCollection("Radio Myata / Semantic");
      const lightId = collection.defaultModeId;
      let darkMode = collection.modes.find((mode) => mode.name === "Dark");
      if (!darkMode) darkMode = { modeId: collection.addMode("Dark") };
      const darkId = darkMode.modeId || darkMode;
      try { collection.renameMode(lightId, "Light"); } catch (error) { /* already named */ }
      try { collection.renameMode(darkId, "Dark"); } catch (error) { /* already named */ }
      const all = typeof figma.variables.getLocalVariables === "function" ? figma.variables.getLocalVariables("COLOR") : [];
      for (const token of Object.keys(TOKENS)) {
        const variableName = "Radio Myata / " + token;
        let variable = all.find((item) => item.name === variableName && item.variableCollectionId === collection.id);
        if (!variable) variable = figma.variables.createVariable(variableName, collection, "COLOR");
        variable.setValueForMode(lightId, hexColor(TOKENS[token].light));
        variable.setValueForMode(darkId, hexColor(TOKENS[token].dark));
        tokenVariables[token] = variable;
      }
      variableCollectionId = collection.id;
      darkModeId = darkId;
      bindVariables = typeof figma.variables.setExplicitVariableModeForCollection === "function";
    } catch (error) {
      tokenVariables = Object.create(null);
    }
  }
  try {
    const existing = figma.getLocalPaintStyles();
    for (const token of Object.keys(TOKENS)) {
      const styleName = "Radio Myata / Dark / " + token;
      let style = existing.find((item) => item.name === styleName);
      if (!style) style = figma.createPaintStyle();
      style.name = styleName;
      style.paints = [solid(TOKENS[token].dark)];
      paintStyles[token] = style;
    }
  } catch (error) {
    paintStyles = Object.create(null);
  }
}

function applyDarkMode(node) {
  if (!bindVariables || !variableCollectionId || !darkModeId || !figma.variables) return;
  try { figma.variables.setExplicitVariableModeForCollection(node, variableCollectionId, darkModeId); } catch (error) { /* bound paints fall back to the local literal if this API is unavailable */ }
}

function clearOwned(page) {
  for (const child of [...page.children]) {
    if (owned(child)) child.remove();
  }
}

function createScreen(page, name, x, height) {
  const screen = appendFrame(page, name, SCREEN_WIDTH, height, x, 0, "background", 0, "390px approved visual frame");
  screen.clipsContent = true;
  screen.setPluginData("screenWidth", String(SCREEN_WIDTH));
  screen.setPluginData("screenHeight", String(height));
  return screen;
}

function addHeader(screen, title, trailing, trailingIcon) {
  const titleNode = appendText(screen, "Header / title", title, { size: 24, lineHeight: 32, width: 300, height: 36, weight: "black", token: "textPrimary" });
  titleNode.x = 16;
  titleNode.y = 16;
  const action = appendFrame(screen, "Header / " + trailing + " · 48dp target", 48, 48, 326, 8, null, 24, "48dp interactive target");
  if ((trailingIcon || "account") === "account") {
    appendFrame(action, "Header / visible account circle", 40, 40, 4, 4, "surfaceContainer", 20, "Android account control visual");
    appendIcon(action, "Header / account icon", "account", "textPrimary", 24, 24, 12, 12, "Android account vector");
  } else {
    appendIcon(action, "Header / " + (trailingIcon || "menu"), trailingIcon || "menu", "textPrimary", 24, 24, 12, 12, "Android menu vector");
  }
  return action;
}

function addCenteredPlayerHeader(screen) {
  const nowPlaying = appendText(screen, "Player / now playing", "СЕЙЧАС ИГРАЕТ", { size: 12, lineHeight: 16, width: 240, height: 18, align: "CENTER", token: "textSecondary" });
  nowPlaying.x = 75;
  nowPlaying.y = 34;
  appendEllipse(screen, "Player / pagination dot 1", 8, 8, 182, 64, "primary");
  appendEllipse(screen, "Player / pagination dot 2", 8, 8, 192, 64, "disabledContent");
  appendEllipse(screen, "Player / pagination dot 3", 8, 8, 202, 64, "disabledContent");
}

function addMini(screen, y, state) {
  const component = components.mini[state || "play"];
  return instance(screen, component, "Mini-player / " + (state || "play"), 16, y);
}

function addNavigation(screen, y, active) {
  return instance(screen, components.nav[active || "home"], "Bottom navigation / " + (active || "home"), 0, y);
}

function addSectionTitle(screen, value, x, y, width) {
  const text = appendText(screen, "Section title / " + value, value, { size: 25, lineHeight: 32, width: width || 358, height: 36, weight: "black", token: "textPrimary" });
  text.x = x || 16;
  text.y = y || 0;
  return text;
}

function addStreamViewport(screen) {
  const viewport = appendFrame(screen, "Streams / horizontal clipped viewport", 358, 198, 16, 132, null, 24, "Android ViewPager2 horizontal clipping");
  viewport.clipsContent = true;
  instance(viewport, components.stream.myata, "Stream card / MYATA", 0, 0);
  instance(viewport, components.stream.gold, "Stream card / GOLD · clipped next card", 331, 0);
  return viewport;
}

function addPlaylistViewport(screen) {
  const viewport = appendFrame(screen, "Playlists / horizontal clipped viewport", 358, 160, 16, 416, null, 24, "Android horizontal RecyclerView clipping");
  viewport.clipsContent = true;
  instance(viewport, components.playlist.one, "Playlist card / 01", 0, 0);
  instance(viewport, components.playlist.two, "Playlist card / 02", 176, 0);
  instance(viewport, components.playlist.three, "Playlist card / 03 · clipped next card", 352, 0);
  return viewport;
}

function addAlbum(screen, asset, x, y, size, name) {
  const image = appendImage(screen, name, size, size, x, y, asset, "surfaceContainer", 24, "Android artwork image fill");
  setStroke(image, "outline", 2);
  return image;
}

function addHistoryCard(screen, x, y, width, height, title, rowCount) {
  const card = appendFrame(screen, "Broadcast history / card", width, height, x, y, "surfaceElevated", 20, "fragment_player.xml · history card");
  setStroke(card, "outline", 1);
  appendText(card, "Broadcast history / title", title, { size: 24, lineHeight: 30, width: width - 32, height: 32, weight: "black", token: "textPrimary" }).x = 16;
  card.children[card.children.length - 1].y = 18;
  const rows = [
    ["10:45", "CRYOGEN", "MUSE"],
    ["10:41", "MEET ME IN LOVE", "BLOSSOMS"],
    ["10:36", "CITY WALLS", "TWENTY ONE PILOTS"],
    ["10:32", "THE LESS I KNOW", "TAME IMPALA"],
    ["10:28", "DREAMS", "THE CRANBERRIES"]
  ];
  for (let i = 0; i < (rowCount || 3); i++) {
    const row = instance(card, components.history[i % components.history.length], "History row / " + rows[i][1], 16, 74 + i * 74);
    row.setPluginData("time", rows[i][0]);
    row.setPluginData("track", rows[i][1]);
    row.setPluginData("artist", rows[i][2]);
  }
  const button = instance(card, components.button.secondary, "History / Показать ещё", 16, height - 70);
  button.resize(width - 32, 54);
  return card;
}

function addSheetBackdrop(screen) {
  const scrim = appendRectangle(screen, "Overlay / scrim", SCREEN_WIDTH, screen.height, 0, 0, "scrim", 0, "modal scrim");
  scrim.opacity = 0.72;
  return scrim;
}

function addSheet(screen, title, top, height) {
  const sheet = appendFrame(screen, "Bottom Sheet / " + title, 358, height, 16, top, "surface", 28, "Material bottom sheet");
  setStroke(sheet, "outline", 1);
  appendFrame(sheet, "Bottom Sheet / drag handle", 40, 4, 159, 16, "outline", 2, "Bottom sheet handle");
  appendText(sheet, "Bottom Sheet / title", title, { size: 22, lineHeight: 28, width: 300, height: 32, weight: "black", token: "textPrimary" }).x = 24;
  sheet.children[sheet.children.length - 1].y = 40;
  return sheet;
}

function addSheetRow(sheet, label, index, icon, selected) {
  const row = instance(sheet, components.sheetRows[label], "Sheet row / " + label, 0, 84 + index * 58);
  row.setPluginData("label", label);
  row.setPluginData("touchTarget", String(MIN_TOUCH));
  if (selected) appendRectangle(row, "Selected indicator", 4, 40, 0, 8, "primary", 2, "selected state");
  if (icon) row.setPluginData("leadingIcon", icon);
  return row;
}

function addPopup(screen, title, top, rows) {
  const popup = appendFrame(screen, "Menu / " + title, 206, 56 + rows.length * 52, 168, top, "surfaceElevated", 20, "Android popup menu");
  setStroke(popup, "outline", 1);
  for (let i = 0; i < rows.length; i++) {
    const row = instance(popup, components.menu[rows[i][0]], "Menu row / " + rows[i][0], 11, 10 + i * 52);
    row.setPluginData("approvedAction", rows[i][0]);
    row.setPluginData("isDestructive", rows[i][1] ? "true" : "false");
  }
  return popup;
}

function buildHome(page) {
  const screen = createScreen(page, "DARK / Home", 0, 757);
  addHeader(screen, "Привет, Денис!", "account", "account");
  addSectionTitle(screen, "Наши потоки", 16, 80);
  addStreamViewport(screen);
  addSectionTitle(screen, "Мятные плейлисты", 16, 369);
  addPlaylistViewport(screen);
  addMini(screen, 602, "play");
  addNavigation(screen, 681, "home");
  return screen;
}

function buildPlayer(page) {
  const screen = createScreen(page, "DARK / Player", 450, 1022);
  addCenteredPlayerHeader(screen);
  addAlbum(screen, assetByName("player-what-you-know.png") || rasterAsset(0), 74, 94, 242, "Player / current artwork");
  appendText(screen, "Player / track title", "WHAT YOU KNOW", { size: 24, lineHeight: 30, width: 358, height: 32, weight: "black", align: "CENTER", token: "textPrimary" }).x = 16;
  screen.children[screen.children.length - 1].y = 373;
  appendText(screen, "Player / artist", "TWO DOOR CINEMA CLUB", { size: 18, lineHeight: 24, width: 358, height: 26, align: "CENTER", token: "textSecondary" }).x = 16;
  screen.children[screen.children.length - 1].y = 404;
  instance(screen, components.playerControls.default, "Player controls / default", 16, 442);
  addHistoryCard(screen, 16, 553, 358, 372, "История эфира", 3);
  addNavigation(screen, 946, "player");
  return screen;
}

function buildCollection(page) {
  const screen = createScreen(page, "DARK / Collection", 900, 740);
  addHeader(screen, "Моя коллекция", "menu", "dots");
  appendText(screen, "Collection / description", "Здесь хранятся ваши сохранённые треки", { size: 14, lineHeight: 20, width: 358, height: 22, token: "textSecondary" }).x = 16;
  screen.children[screen.children.length - 1].y = 84;
  const rows = [
    ["HOMEWRECKER", "SOMBЯ", 0],
    ["LEILA", "MIAMI HORROR FT. POOLSIDE", 1],
    ["FOREVER", "CHVRCHES", 2]
  ];
  rows.forEach((data, i) => {
    const row = instance(screen, components.track[i], "Track card / " + data[0], 16, 133 + i * 114);
    row.setPluginData("title", data[0]);
    row.setPluginData("artist", data[1]);
  });
  addMini(screen, 587, "pause");
  addNavigation(screen, 664, "collection");
  return screen;
}

function buildEmptyCollection(page) {
  const screen = createScreen(page, "DARK / Collection Empty", 1350, 740);
  addHeader(screen, "Моя коллекция", "account", "account");
  appendText(screen, "Collection empty / description", "Здесь хранятся ваши сохранённые треки", { size: 14, lineHeight: 20, width: 358, height: 22, token: "textSecondary" }).x = 16;
  screen.children[screen.children.length - 1].y = 84;
  const empty = appendFrame(screen, "Collection empty / card", 358, 358, 16, 133, "surface", 24, "fragment_favorites.xml · empty state");
  setStroke(empty, "outline", 1);
  appendEllipse(empty, "Empty collection / outer disc", 140, 140, 109, 68, "activeIndicator");
  appendEllipse(empty, "Empty collection / inner disc", 110, 110, 124, 83, "xtraPink");
  appendEllipse(empty, "Empty collection / center", 54, 54, 152, 111, "primary");
  appendEllipse(empty, "Empty collection / center hole", 14, 14, 172, 131, "surface");
  appendIcon(empty, "Empty collection / saved heart", "heart", "myataPink", 40, 40, 232, 155);
  appendText(empty, "Empty collection / title", "Здесь пока пусто", { size: 16, lineHeight: 22, width: 310, height: 24, weight: "bold", align: "CENTER", token: "textPrimary" }).x = 24;
  empty.children[empty.children.length - 1].y = 238;
  appendText(empty, "Empty collection / body", "Сохраняйте понравившиеся треки\nв плеере, и они появятся здесь.", { size: 13, lineHeight: 20, width: 310, height: 46, align: "CENTER", token: "textSecondary" }).x = 24;
  empty.children[empty.children.length - 1].y = 270;
  addMini(screen, 587, "play");
  addNavigation(screen, 664, "collection");
  return screen;
}

function buildAbout(page) {
  const screen = createScreen(page, "DARK / About & Support", 1800, 1418);
  addHeader(screen, "Поддержать радио", "account", "account");
  instance(screen, components.support.monthly, "Support card / monthly", 16, 80);
  instance(screen, components.support.oneTime, "Support card / one-time", 16, 382);
  const about = appendFrame(screen, "About / card", 358, 276, 16, 695, "surface", 24, "fragment_info.xml · about card");
  setStroke(about, "outline", 1);
  appendText(about, "About / title", "О нас", { size: 26, lineHeight: 32, width: 300, height: 36, weight: "black", token: "textPrimary" }).x = 32;
  about.children[about.children.length - 1].y = 36;
  appendText(about, "About / body", "Радио Мята — интернет-радиостанция,\nориентированная на инди-музыку и\nальтернативный рок.", { size: 16, lineHeight: 24, width: 294, height: 78, token: "textSecondary" }).x = 32;
  about.children[about.children.length - 1].y = 88;
  instance(about, components.button.outline, "About / Читать подробнее", 32, 189);
  const labels = ["Телеграм", "Spotify", "Instagram", "TikTok", "YouTube", "Threads", "VK", "Яндекс Музыка"];
  appendText(screen, "About / social heading", "Ещё Радио Мята", { size: 24, lineHeight: 30, width: 358, height: 34, weight: "black", token: "textPrimary" }).x = 16;
  screen.children[screen.children.length - 1].y = 1025;
  labels.forEach((label, index) => {
    const x = 16 + (index % 4) * 90;
    const y = 1067 + Math.floor(index / 4) * 92;
    instance(screen, components.social[index % components.social.length], "Social button / " + label, x, y);
  });
  addMini(screen, 1262, "play");
  addNavigation(screen, 1342, "about");
  return screen;
}

function buildPlayerMenu(page) {
  const screen = createScreen(page, "DARK / Player Menu", 2250, 1022);
  buildPlayerContent(screen);
  addSheetBackdrop(screen);
  addPopup(screen, "Плеер", 72, [["Найти трек", false], ["Таймер сна", false], ["Сообщить о проблеме", true], ["История эфира", false]]);
  return screen;
}

function buildPlayerContent(screen) {
  addCenteredPlayerHeader(screen);
  addAlbum(screen, assetByName("player-what-you-know.png") || rasterAsset(0), 74, 94, 242, "Player / current artwork");
  appendText(screen, "Player / track title", "WHAT YOU KNOW", { size: 24, lineHeight: 30, width: 358, height: 32, weight: "black", align: "CENTER", token: "textPrimary" }).x = 16;
  screen.children[screen.children.length - 1].y = 373;
  appendText(screen, "Player / artist", "TWO DOOR CINEMA CLUB", { size: 18, lineHeight: 24, width: 358, height: 26, align: "CENTER", token: "textSecondary" }).x = 16;
  screen.children[screen.children.length - 1].y = 404;
  instance(screen, components.playerControls.default, "Player controls / default", 16, 442);
  addHistoryCard(screen, 16, 553, 358, 372, "История эфира", 3);
  addNavigation(screen, 946, "player");
}

function buildCollectionMenu(page) {
  const screen = createScreen(page, "DARK / Collection Menu", 2700, 740);
  addHeader(screen, "Моя коллекция", "menu", "dots");
  appendText(screen, "Collection / description", "Здесь хранятся ваши сохранённые треки", { size: 14, lineHeight: 20, width: 358, height: 22, token: "textSecondary" }).x = 16;
  screen.children[screen.children.length - 1].y = 84;
  ["HOMEWRECKER", "LEILA", "FOREVER"].forEach((title, i) => instance(screen, components.track[i], "Track card / " + title, 16, 133 + i * 114));
  addMini(screen, 587, "pause");
  addNavigation(screen, 664, "collection");
  addSheetBackdrop(screen);
  addPopup(screen, "Коллекция", 72, [["Экспортировать TXT", false], ["Экспортировать CSV", false], ["Очистить коллекцию", true]]);
  return screen;
}

function buildServiceSheet(page) {
  const screen = createScreen(page, "DARK / Music Service Bottom Sheet", 3150, 1022);
  buildPlayerContent(screen);
  addSheetBackdrop(screen);
  const sheet = addSheet(screen, "Найти трек в музыкальном сервисе", 390, 600);
  [["Spotify", "disc"], ["Apple Music", "disc"], ["YouTube Music", "play"], ["Яндекс Музыка", "disc"], ["Найти в браузере", "browser"]].forEach((item, index) => addSheetRow(sheet, item[0], index, item[1], false));
  return screen;
}

function buildTimerSheet(page) {
  const screen = createScreen(page, "DARK / Sleep Timer Bottom Sheet", 3600, 1022);
  buildPlayerContent(screen);
  addSheetBackdrop(screen);
  const sheet = addSheet(screen, "Таймер сна", 476, 514);
  [["Выкл.", "close"], ["15 минут", "timer"], ["30 минут", "timer"], ["60 минут", "timer"], ["До конца текущего трека", "timer"]].forEach((item, index) => addSheetRow(sheet, item[0], index, item[1], index === 0));
  return screen;
}

function buildProblemSheet(page) {
  const screen = createScreen(page, "DARK / Report Problem Bottom Sheet", 4050, 1022);
  buildPlayerContent(screen);
  addSheetBackdrop(screen);
  const sheet = addSheet(screen, "Сообщить о проблеме", 470, 520);
  const field = appendFrame(sheet, "Report problem / message field", 310, 126, 24, 94, "surfaceContainer", 16, "Report problem input visual");
  setStroke(field, "outline", 1);
  appendText(field, "Report problem / placeholder", "Опишите проблему", { size: 16, lineHeight: 22, width: 270, height: 24, token: "textSecondary" }).x = 16;
  field.children[field.children.length - 1].y = 16;
  instance(sheet, components.button.primary, "Report problem / Отправить", 24, 240);
  appendText(sheet, "Report problem / error state", "Error reserved for failed submission or destructive action", { size: 11, lineHeight: 15, width: 310, height: 32, token: "error" }).x = 24;
  sheet.children[sheet.children.length - 1].y = 316;
  return screen;
}

function buildFullHistorySheet(page) {
  const screen = createScreen(page, "DARK / Full Broadcast History Bottom Sheet", 4500, 1022);
  buildPlayerContent(screen);
  addSheetBackdrop(screen);
  const sheet = appendFrame(screen, "Bottom Sheet / full broadcast history", 310, 842, 40, 90, "surfaceElevated", 40, "fragment_history_bottom_sheet.xml");
  setStroke(sheet, "outline", 2);
  appendText(sheet, "Full history / title", "ИСТОРИЯ ТРЕКОВ", { size: 20, lineHeight: 26, width: 270, height: 28, weight: "bold", align: "CENTER", token: "textPrimary", letterSpacing: 1 }).x = 20;
  sheet.children[sheet.children.length - 1].y = 24;
  appendRectangle(sheet, "Full history / divider", 278, 1, 16, 68, "divider", 0, "history divider");
  for (let i = 0; i < 7; i++) {
    const row = instance(sheet, components.history[i % components.history.length], "Full history / row " + (i + 1), 16, 86 + i * 82);
    row.resize(278, 58);
    row.setPluginData("state", "loaded");
  }
  appendIconButton(screen, "Full history / close", "close", "textPrimary", 318, 34, "surfaceElevated", 48);
  return screen;
}

function buildBottomNavigation(uiKit) {
  components.nav = Object.create(null);
  const items = [
    ["home", "Главная", "home"],
    ["player", "Плеер", "player"],
    ["collection", "Коллекция", "collection"],
    ["about", "О нас", "info"]
  ];
  for (const active of items.map((item) => item[0])) {
    const component = makeComponent(uiKit, "Component / Dark / Bottom Navigation / " + active, SCREEN_WIDTH, 76, "navigationContainer", 18, "activity_main.xml · bottom navigation");
    for (let index = 0; index < items.length; index++) {
      const item = items[index];
      const x = index * (SCREEN_WIDTH / 4);
      const cell = appendFrame(component, "Navigation item / " + item[1], SCREEN_WIDTH / 4, 76, x, 0, null, 0, "48dp touch target");
      if (active === item[0]) appendFrame(cell, "Active indicator", 64, 48, 16.75, 8, "activeIndicator", 24, "approved pink active indicator");
      appendIcon(cell, "Navigation icon / " + item[2], item[2], active === item[0] ? "onPrimary" : "textSecondary", 24, 24, (SCREEN_WIDTH / 4 - 24) / 2, 14, "Android navigation vector drawable");
      appendText(cell, "Navigation label / " + item[1], item[1], { size: 12, lineHeight: 16, width: SCREEN_WIDTH / 4, height: 16, weight: "bold", align: "CENTER", token: active === item[0] ? "primary" : "textSecondary" }).y = 48;
    }
    components.nav[active] = component;
  }
  const activeItem = makeComponent(uiKit, "State=Active", 96, 76, "navigationContainer", 0, "Navigation item state variant");
  appendFrame(activeItem, "Active indicator", 64, 48, 16, 8, "activeIndicator", 24, "approved pink active indicator");
  appendIcon(activeItem, "Navigation icon", "home", "onPrimary", 24, 24, 36, 14);
  const activeLabel = appendText(activeItem, "Navigation label", "Главная", { size: 12, lineHeight: 16, width: 96, height: 16, weight: "bold", align: "CENTER", token: "primary" });
  activeLabel.y = 48;
  const inactiveItem = makeComponent(uiKit, "State=Inactive", 96, 76, "navigationContainer", 0, "Navigation item state variant");
  appendIcon(inactiveItem, "Navigation icon", "home", "textSecondary", 24, 24, 36, 14);
  const inactiveLabel = appendText(inactiveItem, "Navigation label", "Главная", { size: 12, lineHeight: 16, width: 96, height: 16, weight: "bold", align: "CENTER", token: "textSecondary" });
  inactiveLabel.y = 48;
  components.navItemVariants = combineVariants(uiKit, "Component / Dark / Navigation Item", [activeItem, inactiveItem], "Navigation active/inactive variants");
}

function buildMiniPlayers(uiKit) {
  components.mini = Object.create(null);
  const states = [
    ["play", "play", "play"],
    ["pause", "pause", "pause"],
    ["buffering", "disc", "buffering"]
  ];
  states.forEach((state) => {
    const component = makeComponent(uiKit, "Component / Dark / Mini-player / " + state[0], 358, 74, "miniPlayer", 15, "activity_main.xml · mini-player");
    appendImage(component, "Mini-player / artwork", 48, 48, 12, 13, assetByName("player-what-you-know.png") || rasterAsset(0), "surfaceContainer", 8, "Actual current-track artwork image fill");
    appendText(component, "Mini-player / title", "WHAT YOU KNOW", { size: 16, lineHeight: 20, width: 214, height: 22, token: "textPrimary" }).x = 72;
    component.children[component.children.length - 1].y = 13;
    appendText(component, "Mini-player / artist", "TWO DOOR CINEMA CLUB", { size: 14, lineHeight: 18, width: 214, height: 20, token: "textSecondary" }).x = 72;
    component.children[component.children.length - 1].y = 36;
    const action = appendIconButton(component, "Mini-player / " + state[2], state[1], state[0] === "buffering" ? "primary" : "textPrimary", 298, 13, null, 48);
    action.setPluginData("state", state[0]);
    if (state[0] === "buffering") {
      appendEllipse(action, "Buffering dot 1", 5, 5, 13, 21, "primary");
      appendEllipse(action, "Buffering dot 2", 5, 5, 21, 21, "primary");
      appendEllipse(action, "Buffering dot 3", 5, 5, 29, 21, "primary");
    }
    components.mini[state[0]] = component;
  });
  components.mini.play.name = "State=Play";
  components.mini.pause.name = "State=Pause";
  components.mini.buffering.name = "State=Buffering";
  components.miniVariants = combineVariants(uiKit, "Component / Dark / Mini-player", [components.mini.play, components.mini.pause, components.mini.buffering], "Mini-player play/pause/buffering variants");
}

function buildStreams(uiKit) {
  components.stream = Object.create(null);
  [["myata", "MYATA", "myata_banner_new.xml"], ["gold", "GOLD", "gold_banner_new.xml"], ["xtra", "XTRA", "xtra_banner_new.xml"]].forEach((entry) => {
    const component = makeComponent(uiKit, "Component / Dark / Stream Card / " + entry[1], 316, 198, "brandNavy", 24, "rw_stream_item.xml · Android XML vector banner");
    component.clipsContent = true;
    appendVectorAsset(component, "Vector / " + entry[1] + " banner", vectorAsset(entry[2]), 316, 198, 0, 0, entry[0] === "myata" ? "myataPink" : entry[0] === "gold" ? "goldGreen" : "xtraPink", 24);
    component.setPluginData("streamName", entry[1]);
    components.stream[entry[0]] = component;
  });
}

function buildPlaylists(uiKit) {
  components.playlist = Object.create(null);
  [["one", "Playlist card / 01 · local Android artwork", 0], ["two", "Playlist card / 02 · local Android artwork", 1], ["three", "Playlist card / 03 · optional artwork", 2]].forEach((entry) => {
    const component = makeComponent(uiKit, "Component / Dark / Playlist Card / " + entry[0], 160, 160, "surfaceContainer", 24, "rw_playlist_item.xml · image fill");
    component.clipsContent = true;
    const artwork = ["playlist-seasonal-topping.png", "playlist-xtra.png", "player-what-you-know.png"][entry[2]];
    const image = appendImage(component, entry[1], 160, 160, 0, 0, assetByName(artwork) || rasterAsset(entry[2]), "surfaceContainer", 24, "Actual playlist artwork image fill");
    image.setPluginData("role", "playlist cover");
    components.playlist[entry[0]] = component;
  });
}

function buildHistoryRows(uiKit) {
  components.history = [];
  const rows = [
    ["10:45", "CRYOGEN", "MUSE", 0],
    ["10:41", "MEET ME IN LOVE", "BLOSSOMS", 1],
    ["10:36", "CITY WALLS", "TWENTY ONE PILOTS", 2]
  ];
  rows.forEach((row, index) => {
    const component = makeComponent(uiKit, "Component / Dark / History Row / " + (index + 1), 326, 58, null, 0, "item_history_track.xml · broadcast history");
    addAutoLayout(component, "HORIZONTAL", 8, 0);
    const time = appendText(component, "History / time", row[0], { size: 13, lineHeight: 18, width: 44, height: 20, token: "textSecondary" });
    time.layoutAlign = "INHERIT";
    appendImage(component, "History / artwork", 40, 40, 0, 0, assetByName(["track-homewrecker.png", "track-leila.png", "track-forever.png"][row[3]]) || rasterAsset(row[3]), "surfaceContainer", 8, "Actual history artwork image fill");
    const content = appendFrame(component, "History / text column", 218, 46, 0, 0, null, 0, "History row Auto Layout");
    addAutoLayout(content, "VERTICAL", 1, 0);
    appendText(content, "History / track", row[1], { size: 16, lineHeight: 20, width: 218, height: 21, token: "textPrimary" });
    appendText(content, "History / artist", row[2], { size: 13, lineHeight: 18, width: 218, height: 19, token: "textSecondary" });
    components.history.push(component);
  });
}

function buildTrackCards(uiKit) {
  components.track = [];
  const rows = [
    ["HOMEWRECKER", "SOMBЯ", 0],
    ["LEILA", "MIAMI HORROR FT. POOLSIDE", 1],
    ["FOREVER", "CHVRCHES", 2]
  ];
  rows.forEach((row, index) => {
    const component = makeComponent(uiKit, "Component / Dark / Track Card / " + row[0], 358, 97, "surface", 20, "item_favorite_track.xml · collection track card");
    setStroke(component, "outline", 1);
    addAutoLayout(component, "HORIZONTAL", 12, 16);
    appendImage(component, "Track / artwork", 64, 64, 0, 0, assetByName(["track-homewrecker.png", "track-leila.png", "track-forever.png"][row[2]]) || rasterAsset(row[2]), "surfaceContainer", 16, "Actual saved-track artwork image fill");
    const textColumn = appendFrame(component, "Track / text column", 178, 64, 0, 0, null, 0, "Track card Auto Layout");
    addAutoLayout(textColumn, "VERTICAL", 2, 0);
    appendText(textColumn, "Track / title", row[0], { size: 16, lineHeight: 20, width: 178, height: 22, token: "textPrimary" });
    appendText(textColumn, "Track / artist", row[1], { size: 13, lineHeight: 18, width: 178, height: 20, token: "textSecondary" });
    const action = appendIconButton(component, "Track / open action", "arrow", "primary", 0, 0, null, MIN_TOUCH);
    action.setPluginData("touchTarget", String(MIN_TOUCH));
    components.track.push(component);
  });
}

function buildPlayerControls(uiKit) {
  components.playerControls = Object.create(null);
  ["default", "saved"].forEach((variant) => {
    const component = makeComponent(uiKit, "Component / Dark / Player Controls / " + variant, 358, 80, null, 0, "fragment_myata_stream.xml · thumbs actions");
    const up = appendIconButton(component, "Thumbs up / " + (variant === "saved" ? "saved" : "save to Collection"), "up", variant === "saved" ? "myataPink" : "textPrimary", 24, 16, null, MIN_TOUCH);
    up.setPluginData("action", "Сохранить в Коллекцию");
    const play = appendIconButton(component, "Player / pause", "pause", "onPrimary", 139, 0, "primary", 80);
    play.cornerRadius = 24;
    play.setPluginData("action", "play/pause");
    const down = appendIconButton(component, "Thumbs down / negative reaction", "down", "textSecondary", 286, 16, null, MIN_TOUCH);
    down.setPluginData("action", "Отрицательная реакция");
    down.setPluginData("errorTokenUsed", "false");
    components.playerControls[variant] = component;
  });
}

function buildButtons(uiKit) {
  components.button = Object.create(null);
  const primary = makeComponent(uiKit, "Component / Dark / Button / Primary", 184, 52, "primary", 12, "Material button · primary");
  addAutoLayout(primary, "HORIZONTAL", 0, 16);
  const primaryText = appendText(primary, "Button / label", "Подписаться", { size: 20, lineHeight: 24, width: 152, height: 26, align: "CENTER", token: "onPrimary" });
  primaryText.layoutGrow = 1;
  components.button.primary = primary;
  const secondary = makeComponent(uiKit, "Component / Dark / Button / Secondary", 184, 54, "surfaceContainer", 12, "Material outlined button");
  setStroke(secondary, "outline", 1);
  addAutoLayout(secondary, "HORIZONTAL", 0, 16);
  const secondaryText = appendText(secondary, "Button / label", "Показать ещё", { size: 20, lineHeight: 24, width: 152, height: 26, align: "CENTER", token: "primary" });
  secondaryText.layoutGrow = 1;
  components.button.secondary = secondary;
  const outline = makeComponent(uiKit, "Component / Dark / Button / Outline", 294, 54, "surface", 12, "Material outlined button");
  setStroke(outline, "outline", 1);
  addAutoLayout(outline, "HORIZONTAL", 0, 16);
  const outlineText = appendText(outline, "Button / label", "Читать подробнее", { size: 20, lineHeight: 24, width: 262, height: 26, align: "CENTER", token: "textPrimary" });
  outlineText.layoutGrow = 1;
  components.button.outline = outline;
  const stateSpecs = [
    ["Default", "primary", "onPrimary", "Продолжить"],
    ["Pressed", "secondary", "onSecondary", "Продолжить"],
    ["Disabled", "disabledContainer", "disabledContent", "Продолжить"],
    ["Loading", "primary", "onPrimary", "•••"],
    ["Error", "error", "onPrimary", "Удалить"]
  ];
  const stateVariants = stateSpecs.map((spec) => {
    const component = makeComponent(uiKit, "State=" + spec[0], 184, 52, spec[1], 12, "Button state variant");
    addAutoLayout(component, "HORIZONTAL", 0, 16);
    const text = appendText(component, "Button / " + spec[0], spec[3], { size: 16, lineHeight: 20, width: 152, height: 24, weight: "bold", align: "CENTER", token: spec[2] });
    text.layoutGrow = 1;
    component.setPluginData("state", spec[0].toLowerCase());
    return component;
  });
  components.buttonStateVariants = combineVariants(uiKit, "Component / Dark / Button", stateVariants, "Button default/pressed/disabled/loading/error variants");
}

function buildChips(uiKit) {
  components.chip = Object.create(null);
  [["monthly", "Ежемесячно"], ["oneTime", "Разово"]].forEach((entry) => {
    const component = makeComponent(uiKit, "Component / Dark / Chip / " + entry[0], 97, 28, "surface", 14, "Support card chip");
    addAutoLayout(component, "HORIZONTAL", 0, 10);
    const text = appendText(component, "Chip / label", entry[1], { size: 11, lineHeight: 14, width: 77, height: 16, align: "CENTER", token: "primary" });
    text.layoutGrow = 1;
    components.chip[entry[0]] = component;
  });
}

function buildSupportCards(uiKit) {
  components.support = Object.create(null);
  const monthlyButton = makeComponent(uiKit, "Component / Dark / Button / Support Monthly", 184, 52, "supportMonthlyContent", 12, "Monthly support button · mint card contrast");
  addAutoLayout(monthlyButton, "HORIZONTAL", 0, 16);
  const monthlyButtonText = appendText(monthlyButton, "Button / label", "Подписаться", { size: 20, lineHeight: 24, width: 152, height: 26, align: "CENTER", token: "supportMonthlyBackground" });
  monthlyButtonText.layoutGrow = 1;
  components.button.supportMonthly = monthlyButton;
  const oneTimeButton = makeComponent(uiKit, "Component / Dark / Button / Support One-time", 239, 52, "brandNavy", 12, "One-time support button");
  addAutoLayout(oneTimeButton, "HORIZONTAL", 0, 16);
  const oneTimeButtonText = appendText(oneTimeButton, "Button / label", "Поддержать эфир", { size: 20, lineHeight: 24, width: 207, height: 26, align: "CENTER", token: "supportOneTimeContent" });
  oneTimeButtonText.layoutGrow = 1;
  components.button.supportOneTime = oneTimeButton;
  const monthly = makeComponent(uiKit, "Component / Dark / Support Card / Monthly", 358, 286, "supportMonthlyBackground", 24, "fragment_info.xml · monthly support card");
  instance(monthly, components.chip.monthly, "Chip / Ежемесячно", 24, 24);
  appendText(monthly, "Monthly support / title", "Подпишитесь на Boosty", { size: 24, lineHeight: 30, width: 310, height: 34, token: "supportMonthlyContent" }).x = 24;
  monthly.children[monthly.children.length - 1].y = 68;
  appendText(monthly, "Monthly support / body", "Полный архив эфиров и подкастов,\nэксклюзивные плейлисты и ранний доступ\nк новому контенту. Плюс — ваше имя появится\nв титрах наших видеороликов на YouTube.", { size: 14, lineHeight: 20, width: 310, height: 82, token: "supportMonthlyContent" }).x = 24;
  monthly.children[monthly.children.length - 1].y = 108;
  instance(monthly, components.button.supportMonthly, "Monthly support / Подписаться", 24, 210);
  components.support.monthly = monthly;
  const oneTime = makeComponent(uiKit, "Component / Dark / Support Card / One-time", 358, 266, "supportOneTimeBackground", 24, "fragment_info.xml · one-time support card");
  instance(oneTime, components.chip.oneTime, "Chip / Разово", 24, 24);
  appendText(oneTime, "One-time support / title", "Помочь эфиру звучать", { size: 24, lineHeight: 30, width: 310, height: 34, token: "supportOneTimeContent" }).x = 24;
  oneTime.children[oneTime.children.length - 1].y = 68;
  appendText(oneTime, "One-time support / body", "Разовый вклад помогает оплачивать серверы,\nприложение и развитие Радио Мята.\nЛюбая сумма поддерживает станцию на плаву.", { size: 14, lineHeight: 20, width: 310, height: 64, token: "supportOneTimeContent" }).x = 24;
  oneTime.children[oneTime.children.length - 1].y = 108;
  instance(oneTime, components.button.supportOneTime, "One-time support / Поддержать эфир", 24, 186);
  components.support.oneTime = oneTime;
}

function buildSocialButtons(uiKit) {
  components.social = [];
  const entries = [
    ["Telegram", "telegram_info.png", "myataCyan"],
    ["Spotify", "spotify_info.png", "primary"],
    ["Instagram", "insta_info.png", "textPrimary"],
    ["TikTok", "tiktok_info.png", "myataCyan"],
    ["YouTube", "youtube_info.png", "myataPink"],
    ["Threads", "twitter_info.png", "textPrimary"],
    ["VK", "vk_info.png", "myataCyan"],
    ["Яндекс Музыка", "yandex_info.png", "goldYellow"]
  ];
  entries.forEach((entry) => {
    const component = makeComponent(uiKit, "Component / Dark / Social Button / " + entry[0], 76, 76, "surface", 12, "fragment_info.xml · social link");
    setStroke(component, "outline", 1);
    appendImage(component, "Social / original Android logo", 52, 52, 12, 12, assetByName(entry[1]), entry[2], 8, "Android drawable logo image fill");
    components.social.push(component);
  });
}

function buildMenuRows(menuParent, sheetParent) {
  components.menu = Object.create(null);
  components.sheetRows = Object.create(null);
  const menuRows = [
    ["Найти трек", "search", false],
    ["Таймер сна", "timer", false],
    ["Сообщить о проблеме", "warning", true],
    ["История эфира", "menu", false],
    ["Экспортировать TXT", "browser", false],
    ["Экспортировать CSV", "browser", false],
    ["Очистить коллекцию", "close", true]
  ];
  menuRows.forEach((entry) => {
    const row = makeComponent(menuParent, "Component / Dark / Menu Row / " + entry[0], 184, 48, null, 0, "Popup menu row · 48dp touch target");
    addAutoLayout(row, "HORIZONTAL", 10, 12);
    const icon = appendIconButton(row, "Menu / " + entry[1], entry[1], entry[2] ? "error" : "textSecondary", 0, 0, null, MIN_TOUCH);
    icon.layoutAlign = "INHERIT";
    const text = appendText(row, "Menu / label", entry[0], { size: 14, lineHeight: 20, width: 102, height: 22, token: entry[2] ? "error" : "textPrimary" });
    text.layoutGrow = 1;
    components.menu[entry[0]] = row;
  });
  const sheetRows = [
    ["Spotify", "disc", false], ["Apple Music", "disc", false], ["YouTube Music", "play", false], ["Яндекс Музыка", "disc", false], ["Найти в браузере", "browser", false],
    ["Выкл.", "close", false], ["15 минут", "timer", false], ["30 минут", "timer", false], ["60 минут", "timer", false], ["До конца текущего трека", "timer", false]
  ];
  sheetRows.forEach((entry) => {
    const row = makeComponent(sheetParent, "Component / Dark / Bottom Sheet Row / " + entry[0], 358, 56, null, 0, "Bottom sheet row · 48dp minimum touch target");
    addAutoLayout(row, "HORIZONTAL", 12, 16);
    const icon = appendIconButton(row, "Sheet / " + entry[1], entry[1], "primary", 0, 0, null, MIN_TOUCH);
    icon.layoutAlign = "INHERIT";
    const text = appendText(row, "Sheet / label", entry[0], { size: 16, lineHeight: 22, width: 262, height: 24, token: "textPrimary" });
    text.layoutGrow = 1;
    components.sheetRows[entry[0]] = row;
  });
}

function buildUiKit(page) {
  const root = appendFrame(page, "UI KIT / Radio Myata Dark", 1800, 3020, KIT_X, KIT_Y, "background", 24, "Dark design system UI KIT");
  setStroke(root, "outline", 1);
  const title = appendText(root, "UI Kit / title", "Radio Myata · Dark UI KIT", { size: 28, lineHeight: 36, width: 700, height: 40, weight: "black", token: "textPrimary" });
  title.x = 32;
  title.y = 24;
  const subtitle = appendText(root, "UI Kit / subtitle", "390px visual reference · semantic Variables Light/Dark · 48×48dp minimum touch target", { size: 14, lineHeight: 20, width: 900, height: 22, token: "textSecondary" });
  subtitle.x = 32;
  subtitle.y = 68;
  function section(name, x, y, width, height) {
    const frame = appendFrame(root, "UI KIT / " + name, width, height, x, y, "surface", 20, "UI Kit section");
    setStroke(frame, "outline", 1);
    const label = appendText(frame, "Section title", name, { size: 18, lineHeight: 24, width: width - 32, height: 26, weight: "black", token: "textPrimary" });
    label.x = 16;
    label.y = 14;
    return frame;
  }
  const sections = {
    colors: section("Colors", 32, 112, 840, 246),
    typography: section("Typography", 904, 112, 864, 246),
    spacing: section("Spacing", 32, 374, 840, 210),
    navigation: section("Navigation", 904, 374, 864, 406),
    playback: section("Playback", 32, 600, 840, 460),
    cards: section("Cards", 904, 796, 864, 1450),
    buttons: section("Buttons", 32, 1076, 840, 420),
    menus: section("Menus", 32, 1512, 840, 500),
    sheets: section("Bottom Sheets", 32, 2028, 840, 718),
    states: section("States", 904, 2262, 864, 684)
  };
  const swatches = ["background", "surface", "surfaceContainer", "surfaceElevated", "primary", "secondary", "activeIndicator", "miniPlayer", "textPrimary", "textSecondary", "supportMonthlyBackground", "supportOneTimeBackground", "error"];
  swatches.forEach((token, index) => {
    const x = 16 + (index % 4) * 205;
    const y = 56 + Math.floor(index / 4) * 48;
    appendRectangle(sections.colors, "Token / " + token, 28, 28, x, y, token, 8, "Semantic color variable");
    const label = appendText(sections.colors, "Token label / " + token, token, { size: 11, lineHeight: 14, width: 155, height: 16, token: "textSecondary" });
    label.x = x + 36;
    label.y = y + 6;
  });
  const typeSamples = [
    ["Screen title · Muller Black 24/32", 24, "black"], ["Section title · Muller Black 25/32", 20, "black"], ["Body · Muller Regular 16/24", 16, "regular"], ["Secondary · Muller Regular 14/20", 14, "regular"]
  ];
  typeSamples.forEach((sample, index) => {
    const node = appendText(sections.typography, "Type sample / " + index, sample[0], { size: sample[1], lineHeight: sample[1] + 6, width: 780, height: sample[1] + 8, weight: sample[2], token: index < 2 ? "textPrimary" : "textSecondary" });
    node.x = 16;
    node.y = 52 + index * 44;
  });
  const spacingValues = [4, 8, 12, 16, 20, 24, 32, 40, 48];
  spacingValues.forEach((value, index) => {
    const x = 16 + index * 88;
    appendRectangle(sections.spacing, "Spacing / " + value, value, 12, x, 66, "primary", 6, "Spacing token");
    const label = appendText(sections.spacing, "Spacing label / " + value, String(value), { size: 12, lineHeight: 16, width: 44, height: 18, align: "CENTER", token: "textSecondary" });
    label.x = x;
    label.y = 88;
  });
  [15, 20, 24, 28, 40].forEach((radius, index) => {
    appendRectangle(sections.spacing, "Radius / " + radius, 72, 40, 16 + index * 96, 132, "surfaceElevated", radius, "Radius token");
    const label = appendText(sections.spacing, "Radius label / " + radius, radius + "dp", { size: 11, lineHeight: 14, width: 72, height: 16, align: "CENTER", token: "textSecondary" });
    label.x = 16 + index * 96;
    label.y = 178;
  });
  return { root, sections };
}

function placeComponents(sections) {
  Object.values(components.nav || {}).forEach((node, index) => setPosition(node, 16 + (index % 2) * 420, 54 + Math.floor(index / 2) * 86));
  if (components.navItemVariants) setPosition(components.navItemVariants, 16, 228);
  if (components.miniVariants) setPosition(components.miniVariants, 16, 54);
  Object.values(components.playerControls || {}).forEach((node, index) => setPosition(node, 416, 54 + index * 94));
  Object.values(components.stream || {}).forEach((node, index) => setPosition(node, 16 + (index % 2) * 332, 54 + Math.floor(index / 2) * 214));
  Object.values(components.playlist || {}).forEach((node, index) => setPosition(node, 16 + index * 174, 492));
  Object.values(components.track || {}).forEach((node, index) => setPosition(node, 16, 672 + index * 106));
  Object.values(components.history || {}).forEach((node, index) => setPosition(node, 404, 672 + index * 66));
  Object.values(components.support || {}).forEach((node, index) => setPosition(node, 404, 850 + index * 294));
  Object.values(components.button || {}).filter((node) => node && node.type === "COMPONENT").forEach((node, index) => setPosition(node, 16 + (index % 2) * 244, 54 + Math.floor(index / 2) * 72));
  Object.values(components.chip || {}).forEach((node, index) => setPosition(node, 508, 54 + index * 42));
  (components.social || []).slice(0, 4).forEach((node, index) => setPosition(node, 616 + (index % 2) * 92, 54 + Math.floor(index / 2) * 92));
  Object.values(components.menu || {}).forEach((node, index) => setPosition(node, 16 + (index % 3) * 204, 54 + Math.floor(index / 3) * 58));
  Object.values(components.sheetRows || {}).forEach((node, index) => setPosition(node, 16 + (index % 2) * 408, 54 + Math.floor(index / 2) * 62));
  if (components.buttonStateVariants) {
    if (components.buttonStateVariants.parent !== sections.states) sections.states.appendChild(components.buttonStateVariants);
    setPosition(components.buttonStateVariants, 16, 54);
  }
  if (components.miniVariants) setPosition(components.miniVariants, 16, 154);
  if (components.navItemVariants) setPosition(components.navItemVariants, 416, 154);
}

async function buildAll() {
  fonts = await setupFonts();
  await setupTokens();
  let page = figma.root.children.find((node) => node.type === "PAGE" && node.getPluginData && node.getPluginData("pluginPage") === PAGE_MARKER);
  if (!page) page = figma.createPage();
  page.name = PAGE_NAME;
  page.setPluginData("pluginPage", PAGE_MARKER);
  clearOwned(page);
  components = Object.create(null);
  const kit = buildUiKit(page);
  buildBottomNavigation(kit.sections.navigation);
  buildButtons(kit.sections.buttons);
  buildChips(kit.sections.buttons);
  buildMenuRows(kit.sections.menus, kit.sections.sheets);
  buildStreams(kit.sections.cards);
  buildPlaylists(kit.sections.cards);
  buildHistoryRows(kit.sections.cards);
  buildTrackCards(kit.sections.cards);
  buildPlayerControls(kit.sections.playback);
  buildSupportCards(kit.sections.cards);
  buildSocialButtons(kit.sections.buttons);
  buildMiniPlayers(kit.sections.playback);
  placeComponents(kit.sections);
  const screens = [
    buildHome(page),
    buildPlayer(page),
    buildCollection(page),
    buildEmptyCollection(page),
    buildAbout(page),
    buildPlayerMenu(page),
    buildCollectionMenu(page),
    buildServiceSheet(page),
    buildTimerSheet(page),
    buildProblemSheet(page),
    buildFullHistorySheet(page)
  ];
  const fileKey = typeof figma.fileKey === "string" ? figma.fileKey : "";
  const frames = screens.map((screen) => ({ name: screen.name, id: screen.id, width: screen.width, height: screen.height }));
  figma.currentPage = page;
  figma.viewport.scrollAndZoomIntoView([screens[0], kit.root]);
  return { pageId: page.id, pageName: page.name, fileKey, frames, mullerAvailable: !fonts.fallback, variableCount: Object.keys(tokenVariables).length };
}

function showResult(result) {
  const links = result.frames.map((frame) => {
    const nodeId = frame.id.replace(":", "-");
    const href = result.fileKey ? `https://www.figma.com/design/${result.fileKey}/?node-id=${encodeURIComponent(nodeId)}` : "";
    return { name: frame.name, id: frame.id, href };
  });
  figma.ui.postMessage({ type: "result", result: { ...result, links } });
}

figma.showUI(__html__, { width: 360, height: 520, themeColors: true });
figma.ui.onmessage = async (message) => {
  if (!message || message.type !== "assets-ready") return;
  assets = Array.isArray(message.assets) ? message.assets : [];
  try {
    const result = await buildAll();
    showResult(result);
  } catch (error) {
    figma.ui.postMessage({ type: "error", message: error && error.stack ? error.stack : String(error) });
  }
};
