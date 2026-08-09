"use strict";

// Local, dependency-free Figma development plugin.
// The values below are a checked snapshot of the Android XML layouts/resources.
// The plugin deliberately does not use Figma MCP, network access, or Android code.

const PAGE_NAME = "CURRENT ANDROID UI";
const PAGE_MARKER = "current-android-ui-export";
const UI_KIT_NAME = "UI KIT / Current Android UI";
const HOME_NAME = "Home / Main (375×667)";
const WIDTH = 375;
const HEIGHT = 667;
const HOME_VERSION = "2026-08-04-v2";
const REFERENCE_WIDTH = 375;
const REFERENCE_HEIGHT = 833.333333;

const COLORS = Object.freeze({
  background: "#1C3F5F",
  streamBackground: "#1C4771",
  textPrimary: "#F5F7FA",
  white: "#FFFFFF",
  black: "#000000",
  myata: "#00E5FF",
  gold: "#FFFF00",
  xtra: "#FFCCFF",
  pink: "#FF3F7B",
  navSurface: "#132740",
  cardSurface: "#0F253E",
  itemSurface: "#0A1D33",
  historySurface: "#204971",
  selected: "#5FD9B4",
  border: "#1E3754",
  muted: "#88FFFFFF",
  inactiveNav: "#67686D"
});

const SOURCE = Object.freeze({
  homeLayout: "app/src/main/res/layout/fragment_main.xml",
  activityLayout: "app/src/main/res/layout/activity_main.xml",
  playlistLayout: "app/src/main/res/layout/rw_playlist_item.xml",
  navigation: "app/src/main/res/navigation/navgraph.xml",
  banners: [
    "app/src/main/res/drawable/myata_banner_new.xml",
    "app/src/main/res/drawable/gold_banner_new.xml",
    "app/src/main/res/drawable/xtra_banner_new.xml"
  ],
  navigationIcons: [
    "app/src/main/res/drawable/home_new.xml",
    "app/src/main/res/drawable/player_new.xml",
    "app/src/main/res/drawable/donate_new.xml",
    "app/src/main/res/drawable/info_new.xml",
    "app/src/main/res/drawable/ic_favorites_nav.xml"
  ]
});

const PATHS = Object.freeze({
  home: "M15.84 71.0628H29.1V46.3086H55.62V71.0628H68.88V33.9316L42.36 15.366L15.84 33.9316V71.0628ZM7 79.3141V29.8059L42.36 5.05176L77.72 29.8059V79.3141H46.78V54.56H37.94V79.3141H7Z",
  player: "M14.1445 79.795C12.1997 79.795 10.5347 79.1025 9.14975 77.7175C7.76476 76.3325 7.07227 74.6676 7.07227 72.7227V30.2891C7.07227 28.8157 7.47008 27.4896 8.26571 26.3109C9.06134 25.1322 10.1369 24.2777 11.4924 23.7472L56.2246 5.53613L58.5231 11.3708L29.3499 23.2168H70.7227C72.6676 23.2168 74.3325 23.9093 75.7175 25.2943C77.1025 26.6793 77.795 28.3441 77.795 30.2891V72.7227C77.795 74.6676 77.1025 76.3325 75.7175 77.7175C74.3325 79.1025 72.6676 79.795 70.7227 79.795H14.1445ZM14.1445 72.7227H70.7227V47.9698H14.1445V72.7227ZM28.2891 69.1866C30.7644 69.1866 32.8566 68.332 34.5657 66.6229C36.2749 64.9138 37.1294 62.8215 37.1294 60.3463C37.1294 57.871 36.2749 55.7787 34.5657 54.0696C32.8566 52.3605 30.7644 51.5059 28.2891 51.5059C25.8138 51.5059 23.7216 52.3605 22.0124 54.0696C20.3033 55.7787 19.4487 57.871 19.4487 60.3463C19.4487 62.8215 20.3033 64.9138 22.0124 66.6229C23.7216 68.332 25.8138 69.1866 28.2891 69.1866ZM14.1445 40.8975H56.5782V33.8252H63.6505V40.8975H70.7227V30.2891H14.1445V40.8975Z",
  donate: "M57 45.2439L41.7833 30.4268C39.8889 28.5976 38.2847 26.5701 36.9708 24.3445C35.6569 22.1189 35 19.6951 35 17.0732C35 13.7195 36.1764 10.8689 38.5292 8.52134C40.8819 6.17378 43.7389 5 47.1 5C49.0556 5 50.8889 5.41159 52.6 6.23476C54.3111 7.05793 55.7778 8.17073 57 9.57317C58.2222 8.17073 59.6889 7.05793 61.4 6.23476C63.1111 5.41159 64.9444 5 66.9 5C70.2611 5 73.1181 6.17378 75.4708 8.52134C77.8236 10.8689 79 13.7195 79 17.0732C79 19.6951 78.3583 22.1189 77.075 24.3445C75.7917 26.5701 74.2028 28.5976 72.3083 30.4268L57 45.2439ZM57 35L66.9917 25.2134C68.1528 24.0549 69.2222 22.8201 70.2 21.5091C71.1778 20.1982 71.6667 18.7195 71.6667 17.0732C71.6667 15.7317 71.2083 14.6037 70.2917 13.689C69.375 12.7744 68.2444 12.3171 66.9 12.3171C66.0444 12.3171 65.2347 12.4848 64.4708 12.8201C63.7069 13.1555 63.05 13.6585 62.5 14.3293L57 20.9146L51.5 14.3293C50.95 13.6585 50.2933 13.1555 49.5292 12.8201C48.7653 12.4848 47.9556 12.3171 47.1 12.3171C45.7556 12.3171 44.625 12.7744 43.7083 13.689C42.7917 14.6037 42.3333 15.7317 42.3333 17.0732C42.3333 18.7195 42.8222 20.1982 43.8 21.5091C44.7778 22.8201 45.8472 24.0549 47.0083 25.2134L57 35ZM24 65.3659L49.4833 72.3171L71.3 65.5488C70.9944 65 70.5514 64.5274 69.9708 64.1311C69.3903 63.7348 68.7333 63.5366 68 63.5366H49.4833C47.8333 63.5366 46.5194 63.4756 45.5417 63.3537C44.5639 63.2318 43.5556 62.9878 42.5167 62.622L33.9917 59.7866L36.0083 52.6524L43.4333 55.122C44.4722 55.4268 45.6944 55.6707 47.1 55.8537C48.5056 56.0366 50.5833 56.1585 53.3333 56.2195C53.3333 55.5488 53.1347 54.9085 52.7375 54.2988C52.3403 53.689 51.8667 53.2927 51.3167 53.1098L29.8667 45.2439H24V65.3659ZM2 78.1707V37.9268H29.8667C30.2944 37.9268 30.7222 37.9726 31.15 38.064C31.5778 38.1555 31.975 38.2622 32.3417 38.3841L53.8833 46.3415C55.9 47.0732 57.5347 48.3537 58.7875 50.1829C60.0403 52.0121 60.6667 54.0244 60.6667 56.2195H68C71.0556 56.2195 73.6528 57.2256 75.7917 59.2378C77.9306 61.25 79 63.9024 79 67.1951V70.8537L49.6667 80L24 72.8659V78.1707H2ZM9.33333 70.8537H16.6667V45.2439H9.33333V70.8537Z",
  info: "M38.8975 60.1143H45.9698V38.8975H38.8975V60.1143ZM42.4336 31.8252C43.4355 31.8252 44.2754 31.4863 44.9531 30.8086C45.6309 30.1308 45.9698 29.291 45.9698 28.2891C45.9698 27.2872 45.6309 26.4473 44.9531 25.7696C44.2754 25.0918 43.4355 24.753 42.4336 24.753C41.4317 24.753 40.5919 25.0918 39.9141 25.7696C39.2364 26.4473 38.8975 27.2872 38.8975 28.2891C38.8975 29.291 39.2364 30.1308 39.9141 30.8086C40.5919 31.4863 41.4317 31.8252 42.4336 31.8252ZM42.4336 77.795C37.542 77.795 32.945 76.8668 28.6427 75.0103C24.3404 73.1538 20.598 70.6343 17.4155 67.4518C14.2329 64.2693 11.7134 60.5269 9.85697 56.2246C8.0005 51.9223 7.07227 47.3253 7.07227 42.4336C7.07227 37.542 8.0005 32.945 9.85697 28.6427C11.7134 24.3404 14.2329 20.598 17.4155 17.4155C20.598 14.2329 24.3404 11.7134 28.6427 9.85697C32.945 8.0005 37.542 7.07227 42.4336 7.07227C47.3253 7.07227 51.9223 8.0005 56.2246 9.85697C60.5269 11.7134 64.2693 14.2329 67.4518 17.4155C70.6343 20.598 73.1538 24.3404 75.0103 28.6427C76.8668 32.945 77.795 37.542 77.795 42.4336C77.795 47.325 76.8668 51.9223 75.0103 56.2246C73.1538 60.5269 70.6343 64.2693 67.4518 67.4518C64.2693 70.6343 60.5269 73.1538 56.2246 75.0103C51.9223 76.8668 47.325 77.795 42.4336 77.795ZM42.4336 70.7227C50.331 70.7227 57.0202 67.9822 62.5012 62.5012C67.9822 57.0202 70.7227 50.331 70.7227 42.4336C70.7227 34.5363 67.9822 27.8471 62.5012 22.3661C57.0202 16.885 50.331 14.1445 42.4336 14.1445C34.5363 14.1445 27.8471 16.885 22.3661 22.3661C16.885 27.8471 14.1445 34.536 14.1445 42.4336C14.1445 50.331 16.885 57.0202 22.3661 62.5012C27.8471 67.9822 34.5363 70.7227 42.4336 70.7227Z",
  favorites: "M42.687,55.7753C45.2242,55.7753 47.3901,54.8959 49.1847,53.1372C50.9793,51.3785 51.8766,49.2255 51.8766,46.6784V23.2865H62.6443V17.717H48.1636V39.7166C47.4829,39.1596 46.6685,38.7264 45.7205,38.417C44.7724,38.1076 43.7612,37.9529 42.687,37.9529C40.2698,37.9529 38.2267,38.7997 36.5577,40.4935C34.8887,42.1866 34.0542,44.2597 34.0542,46.7128C34.0542,49.1652 34.8887,51.2887 36.5577,53.0834C38.2267,54.878 40.2698,55.7753 42.687,55.7753ZM21.7085,68.121C20.2233,68.121 18.9238,67.5641 17.8099,66.4502C16.696,65.3363 16.139,64.0367 16.139,62.5515V10.5695C16.139,9.0843 16.696,7.78475 17.8099,6.67085C18.9238,5.55695 20.2233,5 21.7085,5H73.6905C75.1757,5 76.4753,5.55695 77.5892,6.67085 79.26,8.0843 79.26,9.0843 79.26,10.5695V62.5515C79.26,64.0367 78.7031,65.3363 77.5892,66.4502C76.4753,67.5641 75.1757,68.121 73.6905,68.121H21.7085ZM21.7085,62.5515H73.6905V10.5695H21.7085V62.5515ZM10.5695,79.26C9.0843,79.26 7.78475,78.7031 6.67085,77.5892C5.55695,76.4753 5,75.1757 5,73.6905V16.139H10.5695V73.6905H68.121V79.26H10.5695Z"
});

const HEART_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" fill="none" stroke="#FFFFFF" stroke-width="2"/></svg>';
const HISTORY_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><path fill="#FFFFFF" d="M5 11.5a.5.5 0 0 1 .5-.5h9a.5.5 0 0 1 0 1h-9a.5.5 0 0 1-.5-.5m0-4a.5.5 0 0 1 .5-.5h9a.5.5 0 0 1 0 1h-9a.5.5 0 0 1-.5-.5m0-4a.5.5 0 0 1 .5-.5h9a.5.5 0 0 1 0 1h-9a.5.5 0 0 1-.5-.5m-3 1a1 1 0 1 0 0-2 1 1 0 0 0 0 2m0 4a1 1 0 1 0 0-2 1 1 0 0 0 0 2m0 4a1 1 0 1 0 0-2 1 1 0 0 0 0 2"/></svg>';
const PLAY_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="60" height="61" viewBox="0 0 60 61"><path fill="#000000" d="M59 31c0 16.57-13.43 30-29 30S1 47.57 1 31h13.47c0 8.88 6.95 16.07 15.53 16.07S45.53 39.88 45.53 31H59Z"/><circle cx="30" cy="30" r="29" fill="#00E5FF" stroke="#000000"/><path d="M23 18.5v23L43 30 23 18.5Z" fill="#000000"/></svg>';

function parseAndroidColor(hex) {
  const value = hex.replace("#", "");
  if (value.length === 6) {
    return {
      r: parseInt(value.slice(0, 2), 16) / 255,
      g: parseInt(value.slice(2, 4), 16) / 255,
      b: parseInt(value.slice(4, 6), 16) / 255,
      a: 1
    };
  }
  if (value.length === 8) {
    return {
      r: parseInt(value.slice(2, 4), 16) / 255,
      g: parseInt(value.slice(4, 6), 16) / 255,
      b: parseInt(value.slice(6, 8), 16) / 255,
      a: parseInt(value.slice(0, 2), 16) / 255
    };
  }
  throw new Error(`Unsupported Android color: ${hex}`);
}

function solid(hex) {
  const color = parseAndroidColor(hex);
  return {type: "SOLID", color: {r: color.r, g: color.g, b: color.b}, opacity: color.a};
}

function rgba(hex) {
  const color = parseAndroidColor(hex);
  return {r: color.r, g: color.g, b: color.b, a: color.a};
}

function mark(node, source) {
  node.setPluginData("androidSource", source);
  node.setPluginData("plugin", PAGE_MARKER);
  return node;
}

function directChild(parent, name, type) {
  return parent.children.find((child) => child.name === name && (!type || child.type === type)) || null;
}

function ensureFrame(parent, name, width, height, x, y) {
  let frame = parent.children.find((child) => child.type === "FRAME" && child.name === name && isPluginOwned(child)) || null;
  if (!frame) {
    frame = figma.createFrame();
    frame.name = name;
    parent.appendChild(frame);
    mark(frame, SOURCE.homeLayout);
  }
  frame.resize(width, height);
  frame.x = x;
  frame.y = y;
  return frame;
}

function ensureOwnedFrame(parent, name, width, height, x, y, source) {
  let frame = parent.children.find((child) => child.type === "FRAME" && child.name === name && isPluginOwned(child)) || null;
  if (!frame) {
    frame = figma.createFrame();
    frame.name = name;
    parent.appendChild(frame);
    mark(frame, source || SOURCE.homeLayout);
  }
  frame.resize(width, height);
  frame.x = x;
  frame.y = y;
  return frame;
}

function ensureComponent(parent, name, width, height, x, y) {
  let component = parent.children.find((child) => child.type === "COMPONENT" && child.name === name && isPluginOwned(child)) || null;
  let created = false;
  if (!component) {
    component = figma.createComponent();
    component.name = name;
    parent.appendChild(component);
    mark(component, "Figma component reconstructed from Android XML");
    created = true;
  }
  component.resize(width, height);
  component.x = x;
  component.y = y;
  return {node: component, created};
}

function appendText(parent, name, characters, options, fonts, source) {
  const text = figma.createText();
  parent.appendChild(text);
  text.name = name + (fonts.fallback ? " [Inter fallback: Muller unavailable]" : "");
  text.fontName = options.weight === "heavy" ? fonts.heavy : fonts.regular;
  text.fontSize = options.size;
  text.characters = characters;
  text.textAutoResize = "HEIGHT";
  text.resize(options.width || 200, options.height || Math.ceil(options.size * 1.45));
  text.textAlignHorizontal = options.align || "LEFT";
  text.textAlignVertical = options.verticalAlign || "TOP";
  text.fills = [solid(options.color || COLORS.textPrimary)];
  text.lineHeight = {value: options.lineHeight || Math.ceil(options.size * 1.25), unit: "PIXELS"};
  text.letterSpacing = {value: options.letterSpacing || 0, unit: "PIXELS"};
  mark(text, source || SOURCE.homeLayout);
  if (options.styleId) {
    try { text.textStyleId = options.styleId; } catch (error) { /* direct properties remain authoritative */ }
  }
  return text;
}

function appendRectangle(parent, name, width, height, x, y, color, radius, source) {
  const rectangle = figma.createRectangle();
  rectangle.name = name;
  rectangle.resize(width, height);
  rectangle.x = x;
  rectangle.y = y;
  rectangle.fills = [solid(color)];
  if (radius !== undefined) rectangle.cornerRadius = radius;
  parent.appendChild(rectangle);
  mark(rectangle, source || SOURCE.homeLayout);
  return rectangle;
}

function applyImageFill(node, asset, source) {
  if (!asset || !Array.isArray(asset.bytes) || typeof figma.createImage !== "function") return false;
  try {
    const image = figma.createImage(new Uint8Array(asset.bytes));
    node.fills = [{type: "IMAGE", imageHash: image.hash, scaleMode: "FILL"}];
    node.setPluginData("imageAsset", asset.name || "unnamed image asset");
    if (source) node.setPluginData("androidSource", source);
    return true;
  } catch (error) {
    node.setPluginData("imageAssetError", error.message || String(error));
    return false;
  }
}

function appendEllipse(parent, name, width, height, x, y, color, source) {
  const ellipse = figma.createEllipse();
  ellipse.name = name;
  ellipse.resize(width, height);
  ellipse.x = x;
  ellipse.y = y;
  ellipse.fills = [solid(color)];
  parent.appendChild(ellipse);
  mark(ellipse, source || SOURCE.homeLayout);
  return ellipse;
}

function appendSvg(parent, name, svg, width, height, x, y, source) {
  const vectorRoot = figma.createNodeFromSvg(svg);
  vectorRoot.name = name;
  vectorRoot.resize(width, height);
  vectorRoot.x = x;
  vectorRoot.y = y;
  parent.appendChild(vectorRoot);
  mark(vectorRoot, source || "Android vector drawable");
  return vectorRoot;
}

function appendVector(parent, name, data, width, height, x, y, color, source) {
  try {
    const vector = figma.createVector();
    vector.name = name;
    vector.vectorPaths = [{windingRule: "NONZERO", data}];
    vector.fills = [solid(color)];
    vector.resize(width, height);
    vector.x = x;
    vector.y = y;
    parent.appendChild(vector);
    mark(vector, source || "Android vector reconstruction");
    return vector;
  } catch (error) {
    const fallback = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><path d="${data}" fill="${color}"/></svg>`;
    return appendSvg(parent, name + " / SVG fallback", fallback, width, height, x, y, source);
  }
}

function makeAutoFrame(parent, name, width, height, direction, source) {
  const frame = figma.createFrame();
  frame.name = name;
  frame.resize(width, height);
  frame.layoutMode = direction;
  frame.primaryAxisSizingMode = "FIXED";
  frame.counterAxisSizingMode = "FIXED";
  frame.itemSpacing = 0;
  frame.fills = [];
  parent.appendChild(frame);
  mark(frame, source || SOURCE.homeLayout);
  return frame;
}

function setFixedSizing(node) {
  try {
    node.layoutSizingHorizontal = "FIXED";
    node.layoutSizingVertical = "FIXED";
  } catch (error) { /* older Plugin API versions default to fixed sizing */ }
}

function iconSvg(path, fill, viewBox) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 ${viewBox || 85} ${viewBox || 85}"><path d="${path}" fill="${fill}"/></svg>`;
}

function stationSvg(accent, variant) {
  const diagonal = variant === "GOLD" ? "M0 150 L154 0 H316 V70 L163 198 H0Z" : variant === "XTRA" ? "M0 24 L90 0 H316 V62 L95 198 H0Z" : "M0 0 H210 L316 105 V198 H0Z";
  const ellipse = variant === "GOLD" ? "<ellipse cx=\"270\" cy=\"12\" rx=\"82\" ry=\"42\"/>" : variant === "XTRA" ? "<ellipse cx=\"275\" cy=\"180\" rx=\"92\" ry=\"46\"/>" : "<ellipse cx=\"38\" cy=\"172\" rx=\"88\" ry=\"50\"/>";
  return `<svg xmlns="http://www.w3.org/2000/svg" width="316" height="198" viewBox="0 0 316 198"><rect width="316" height="198" rx="20" fill="${COLORS.pink}"/><path d="${diagonal}" fill="${COLORS.streamBackground}"/><path d="M0 126 C92 70 158 214 316 92 V198 H0Z" fill="${accent}" opacity="0.88"/>${ellipse.replace("/>", ` fill="${accent}" opacity="0.82"/>`)}</svg>`;
}

function ensurePage() {
  let page = figma.root.children.find((candidate) => candidate.type === "PAGE" && candidate.name === PAGE_NAME && candidate.getPluginData("plugin") === PAGE_MARKER);
  if (!page) {
    page = figma.createPage();
    page.name = PAGE_NAME;
    page.setPluginData("plugin", PAGE_MARKER);
    page.setPluginData("source", "Kotlin/XML Android app snapshot");
  }
  return page;
}

function isPluginOwned(node) {
  try { return node.getPluginData("plugin") === PAGE_MARKER; } catch (error) { return false; }
}

function removeOwnedChildren(parent) {
  [...parent.children].forEach((child) => {
    if (isPluginOwned(child)) child.remove();
  });
}

function findAsset(assets, name) {
  return (assets || []).find((asset) => asset && asset.name === name) || null;
}

function ensureReference(page, assets) {
  let reference = page.children.find((child) => child.type === "RECTANGLE" && isPluginOwned(child) && child.getPluginData("role") === "reference");
  if (!reference) {
    reference = figma.createRectangle();
    reference.name = "Reference / Home screenshot 576×1280 (locked)";
    page.appendChild(reference);
    mark(reference, "tools/figma-export/assets/reference-home.png");
    reference.setPluginData("role", "reference");
  }
  reference.resize(REFERENCE_WIDTH, REFERENCE_HEIGHT);
  reference.x = 1700;
  reference.y = 0;
  reference.cornerRadius = 0;
  reference.opacity = 0.92;
  reference.locked = false;
  const screenshot = findAsset(assets, "reference-home.png");
  if (screenshot) {
    applyImageFill(reference, screenshot, "tools/figma-export/assets/reference-home.png · visual reference only");
    reference.setPluginData("referenceStatus", "supplied screenshot imported as a separate locked layer");
  } else {
    reference.fills = [solid(COLORS.background)];
    reference.setPluginData("referenceStatus", "screenshot asset not received by plugin UI");
  }
  reference.name = screenshot
    ? "Reference / Home screenshot 576×1280 (locked)"
    : "Reference / Home screenshot 576×1280 (asset missing)";
  reference.locked = true;
  return reference;
}

function refreshPluginUiKit(uiKit) {
  if (uiKit.getPluginData("homeVisualVersion") === HOME_VERSION) return;
  removeOwnedChildren(uiKit);
  uiKit.setPluginData("legendBuilt", "");
}

async function resolveFonts() {
  const fallbackRegular = {family: "Inter", style: "Regular"};
  const fallbackHeavy = {family: "Inter", style: "Black"};
  let available = [];
  try { available = await figma.listAvailableFontsAsync(); } catch (error) { available = []; }
  const matching = available
    .map((entry) => entry.fontName)
    .filter(Boolean);
  const muller = matching.filter((font) => font.family.toLowerCase().includes("muller"));
  const inter = matching.filter((font) => font.family.toLowerCase() === "inter");
  const pickStyle = (fonts, tests) => fonts.find((font) => tests.some((test) => font.style.toLowerCase().includes(test))) || fonts[0];
  const mullerRegular = pickStyle(muller, ["regular", "book", "normal"]);
  const mullerHeavy = pickStyle(muller, ["black", "heavy", "bold"]);
  const interRegular = pickStyle(inter, ["regular", "normal"]) || fallbackRegular;
  const interHeavy = pickStyle(inter, ["black", "heavy", "bold"]) || fallbackHeavy;
  const fallback = !mullerRegular;
  const regular = mullerRegular || interRegular;
  const heavy = mullerHeavy || interHeavy || regular;
  const fontsToLoad = [regular, heavy, fallback ? interRegular : regular].filter(Boolean);
  await Promise.all(fontsToLoad.map((font) => figma.loadFontAsync(font)));
  return {
    regular,
    heavy,
    fallback,
    mullerAvailable: Boolean(mullerRegular),
    mullerCandidates: muller.map((font) => `${font.family} / ${font.style}`),
    source: fallback ? "Inter fallback: Muller unavailable" : "Muller loaded"
  };
}

async function createPaintTokens() {
  const styles = {};
  const variables = {};
  const localPaintStyles = typeof figma.getLocalPaintStylesAsync === "function"
    ? await figma.getLocalPaintStylesAsync()
    : figma.getLocalPaintStyles();
  for (const [token, hex] of Object.entries(COLORS)) {
    const styleName = `CURRENT ANDROID UI / Color / ${token}`;
    let style = localPaintStyles.find((candidate) => candidate.name === styleName);
    if (!style) style = figma.createPaintStyle();
    style.name = styleName;
    style.paints = [solid(hex)];
    styles[token] = style;
  }
  try {
    if (figma.variables && typeof figma.variables.createVariableCollection === "function") {
      const collections = typeof figma.variables.getLocalVariableCollectionsAsync === "function"
        ? await figma.variables.getLocalVariableCollectionsAsync()
        : figma.variables.getLocalVariableCollections();
      let collection = collections.find((candidate) => candidate.name === "CURRENT ANDROID UI / Tokens");
      if (!collection) collection = figma.variables.createVariableCollection("CURRENT ANDROID UI / Tokens");
      try { collection.renameMode(collection.defaultModeId, "Android reference"); } catch (error) { /* mode already has a custom name */ }
      const local = typeof figma.variables.getLocalVariablesAsync === "function"
        ? await figma.variables.getLocalVariablesAsync()
        : figma.variables.getLocalVariables();
      for (const [token, hex] of Object.entries(COLORS)) {
        let variable = local.find((candidate) => candidate.variableCollectionId === collection.id && candidate.name === token);
        if (!variable) variable = figma.variables.createVariable(token, collection, "COLOR");
        variable.setValueForMode(collection.defaultModeId, rgba(hex));
        try { variable.scopes = ["FRAME_FILL", "SHAPE_FILL", "TEXT_FILL"]; } catch (error) { /* optional on older API */ }
        variables[token] = variable;
      }
    }
  } catch (error) {
    // Paint styles remain the portable fallback when Variables API is unavailable.
  }
  return {styles, variables, variableCount: Object.keys(variables).length};
}

async function createTypographyStyles(fonts) {
  const styles = {};
  const localTextStyles = typeof figma.getLocalTextStylesAsync === "function"
    ? await figma.getLocalTextStylesAsync()
    : figma.getLocalTextStyles();
  const specs = {
    title: {name: "CURRENT ANDROID UI / Typography / Title", size: 25, font: fonts.regular, lineHeight: 31},
    body: {name: "CURRENT ANDROID UI / Typography / Body", size: 17, font: fonts.regular, lineHeight: 24},
    playerArtist: {name: "CURRENT ANDROID UI / Typography / Player artist", size: 24, font: fonts.heavy, lineHeight: 28},
    playerTrack: {name: "CURRENT ANDROID UI / Typography / Player track", size: 18, font: fonts.regular, lineHeight: 22},
    caption: {name: "CURRENT ANDROID UI / Typography / Caption", size: 12, font: fonts.regular, lineHeight: 16}
  };
  for (const [key, spec] of Object.entries(specs)) {
    try {
      let style = localTextStyles.find((candidate) => candidate.name === spec.name);
      if (!style) style = figma.createTextStyle();
      style.name = spec.name;
      style.fontName = spec.font;
      style.fontSize = spec.size;
      style.lineHeight = {value: spec.lineHeight, unit: "PIXELS"};
      style.letterSpacing = {value: 0, unit: "PIXELS"};
      styles[key] = style;
    } catch (error) {
      // Direct text properties are still used if a host API does not expose styles.
    }
  }
  return styles;
}

function buildBottomNavigation(uiKit, fonts) {
  const result = ensureComponent(uiKit, "Component / Bottom Navigation", 325, 54, 20, 105);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.layoutMode = "HORIZONTAL";
  component.primaryAxisSizingMode = "FIXED";
  component.counterAxisSizingMode = "FIXED";
  component.itemSpacing = 0;
  component.cornerRadius = 27;
  component.fills = [solid(COLORS.navSurface)];
  component.strokes = [solid(COLORS.black)];
  component.strokeWeight = 1.5;
  component.effects = [{type: "DROP_SHADOW", color: rgba("#66000000"), offset: {x: 0, y: 4}, radius: 8, spread: 0, visible: true, blendMode: "NORMAL"}];
  const icons = [
    ["Home", PATHS.home, "#FFFFFF", 85],
    ["Player", PATHS.player, COLORS.inactiveNav, 85],
    ["Donate", PATHS.donate, COLORS.inactiveNav, 85],
    ["Info", PATHS.info, COLORS.inactiveNav, 80],
    ["Favorites", PATHS.favorites, COLORS.white, 84]
  ];
  icons.forEach(([label, path, color, viewBox]) => {
    const slot = figma.createFrame();
    slot.name = `Nav slot / ${label}`;
    slot.resize(65, 54);
    slot.fills = [];
    slot.layoutMode = "NONE";
    component.appendChild(slot);
    setFixedSizing(slot);
    appendSvg(slot, `Vector / ${label} icon`, iconSvg(path, color, viewBox), 24, 24, 20.5, 15, SOURCE.navigationIcons.join(", "));
  });
  component.setPluginData("built", "1");
  return component;
}

function buildPlayButton(uiKit) {
  const result = ensureComponent(uiKit, "Component / Play Button / MYATA", 60, 61, 370, 105);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.fills = [];
  appendSvg(component, "Vector / btn_play.xml", PLAY_SVG, 60, 61, 0, 0, "app/src/main/res/drawable/btn_play.xml");
  appendVector(component, "Vector / play triangle editable", "M 0 0 L 18 10 L 0 20 Z", 18, 20, 21, 20, COLORS.black, "Android player control vector");
  component.setPluginData("built", "1");
  return component;
}

function buildPlayerControls(uiKit, fonts, playButton) {
  const result = ensureComponent(uiKit, "Component / Player Controls", 275, 61, 450, 105);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.layoutMode = "HORIZONTAL";
  component.primaryAxisSizingMode = "FIXED";
  component.counterAxisSizingMode = "FIXED";
  component.itemSpacing = 56;
  component.fills = [];
  const heart = appendSvg(component, "Icon / heart outline", HEART_SVG, 30, 30, 0, 15, "app/src/main/res/drawable/ic_heart_outline.xml");
  setFixedSizing(heart);
  const play = playButton.createInstance();
  play.name = "Instance / Play Button / MYATA";
  component.appendChild(play);
  setFixedSizing(play);
  const history = appendSvg(component, "Icon / history", HISTORY_SVG, 30, 30, 0, 15, "app/src/main/res/drawable/ic_history.xml");
  setFixedSizing(history);
  component.setPluginData("built", "1");
  return component;
}

function buildStationCard(uiKit, fonts, label, accent, x, assets) {
  const result = ensureComponent(uiKit, `Component / Station Card / ${label}`, 316, 198, x, 220);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.cornerRadius = 20;
  component.clipsContent = true;
  component.fills = [];
  component.effects = [{type: "DROP_SHADOW", color: rgba("#55000000"), offset: {x: 0, y: 4}, radius: 8, spread: 0, visible: true, blendMode: "NORMAL"}];
  const assetName = `${label.toLowerCase()}_banner_new.xml`;
  const vectorAsset = findAsset(assets, assetName);
  if (vectorAsset && vectorAsset.svg) {
    appendSvg(component, `Vector / ${label} banner / exact Android XML`, vectorAsset.svg, 316, 198, 0, 0, vectorAsset.source || assetName);
    component.setPluginData("bannerAsset", assetName);
  } else {
    appendSvg(component, `Vector / ${label} banner / fallback`, stationSvg(accent, label), 316, 198, 0, 0, SOURCE.banners.join(", "));
    const labelText = appendText(component, `Text / ${label} / fallback`, label, {size: 30, width: 280, height: 42, color: COLORS.white, weight: "heavy", align: "CENTER"}, fonts, SOURCE.banners.join(", "));
    labelText.x = 18;
    labelText.y = 75;
    const accentVector = label === "MYATA" ? "M 0 0 L 22 0 L 11 18 Z" : label === "GOLD" ? "M 0 18 L 11 0 L 22 18 Z" : "M 0 9 L 11 0 L 22 9 L 11 18 Z";
    appendVector(component, `Vector / ${label} accent / fallback`, accentVector, 22, 18, 147, 162, accent, SOURCE.banners.join(", "));
  }
  component.setPluginData("built", "1");
  return component;
}

function buildPlaylistCard(uiKit, fonts, variant, imageAsset, fallbackAsset) {
  const result = ensureComponent(uiKit, `Component / Playlist Card / ${variant}`, 140, 140, 20 + (Number(variant) - 1) * 160, 470);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.cornerRadius = 20;
  component.clipsContent = true;
  component.fills = [];
  component.effects = [{type: "DROP_SHADOW", color: rgba("#55000000"), offset: {x: 0, y: 4}, radius: 8, spread: 0, visible: true, blendMode: "NORMAL"}];
  const image = appendRectangle(component, `Image fill / playlist ${variant}`, 140, 140, 0, 0, COLORS.streamBackground, 20, imageAsset && imageAsset.source ? imageAsset.source : "https://radiomyata.ru/covers/playlists.txt");
  const applied = applyImageFill(image, imageAsset || fallbackAsset, (imageAsset && imageAsset.source) || "Android raster playlist asset");
  if (!applied) {
    image.fills = [solid(COLORS.streamBackground)];
    appendText(component, `Text / playlist ${variant} / asset missing`, `Плейлист ${variant}`, {size: 12, width: 120, height: 22, color: COLORS.textPrimary, align: "CENTER"}, fonts, "Remote playlist image unavailable").y = 59;
  }
  component.setPluginData("imageAsset", (imageAsset || fallbackAsset || {}).name || "missing");
  component.setPluginData("imageRole", imageAsset && imageAsset.fromReference ? "reference screenshot crop" : "Android raster fallback");
  component.setPluginData("built", "1");
  return component;
}

function buildPrimaryButton(uiKit, fonts) {
  const result = ensureComponent(uiKit, "Component / Button / Primary", 314, 70, 230, 470);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.layoutMode = "HORIZONTAL";
  component.primaryAxisSizingMode = "FIXED";
  component.counterAxisSizingMode = "FIXED";
  component.primaryAxisAlignItems = "CENTER";
  component.counterAxisAlignItems = "CENTER";
  component.cornerRadius = 15;
  component.fills = [solid(COLORS.pink)];
  appendText(component, "Text / primary button", "ПОДДЕРЖАТЬ МЯТУ", {size: 20, width: 280, height: 28, color: COLORS.white, weight: "heavy", align: "CENTER"}, fonts, "app/src/main/res/layout/fragment_donate.xml");
  component.setPluginData("built", "1");
  return component;
}

function buildDonateAmount(uiKit, fonts) {
  const result = ensureComponent(uiKit, "Component / Donation Amount Button", 110, 58, 570, 470);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.layoutMode = "HORIZONTAL";
  component.primaryAxisSizingMode = "FIXED";
  component.counterAxisSizingMode = "FIXED";
  component.primaryAxisAlignItems = "CENTER";
  component.counterAxisAlignItems = "CENTER";
  component.cornerRadius = 15;
  component.fills = [solid(COLORS.white)];
  appendText(component, "Text / amount", "100 ₽", {size: 18, width: 100, height: 25, color: COLORS.black, weight: "heavy", align: "CENTER"}, fonts, "app/src/main/res/layout/fragment_donate.xml");
  component.setPluginData("built", "1");
  return component;
}

function buildHistoryRow(uiKit, fonts) {
  const result = ensureComponent(uiKit, "Component / History Row", 315, 48, 710, 470);
  const component = result.node;
  if (!result.created && component.getPluginData("built") === "1") return component;
  component.layoutMode = "HORIZONTAL";
  component.primaryAxisSizingMode = "FIXED";
  component.counterAxisSizingMode = "FIXED";
  component.itemSpacing = 8;
  component.cornerRadius = 16;
  component.paddingLeft = 12;
  component.paddingRight = 12;
  component.counterAxisAlignItems = "CENTER";
  component.fills = [solid(COLORS.itemSurface)];
  appendText(component, "Text / time", "12:34", {size: 11, width: 36, height: 16, color: COLORS.muted}, fonts, "app/src/main/res/layout/item_history_track.xml");
  appendText(component, "Text / artist", "ARTIST", {size: 11, width: 95, height: 16, color: COLORS.white, weight: "heavy"}, fonts, "app/src/main/res/layout/item_history_track.xml");
  appendText(component, "Text / track", "TRACK TITLE", {size: 11, width: 115, height: 16, color: COLORS.muted}, fonts, "app/src/main/res/layout/item_history_track.xml");
  component.setPluginData("built", "1");
  return component;
}

function buildRasterAssetLegend(uiKit, fonts, assets) {
  const name = "UI KIT / Android raster sources";
  let frame = uiKit.children.find((child) => child.type === "FRAME" && child.name === name && isPluginOwned(child)) || null;
  if (!frame) {
    frame = ensureFrame(uiKit, name, 500, 190, 20, 920);
    frame.fills = [solid(COLORS.itemSurface)];
    frame.cornerRadius = 20;
    appendText(frame, "Text / Android raster sources", "ANDROID RASTER SOURCES · IMAGE FILLS", {size: 14, width: 420, height: 20, color: COLORS.textPrimary, weight: "heavy"}, fonts, "app/src/main/res/drawable");
    ["zaglushka_1_img.png", "zaglushka_3_img.png", "zaglushka_4_img.png"].forEach((assetName, index) => {
      const asset = findAsset(assets, assetName);
      const preview = appendRectangle(frame, `Image fill / ${assetName}`, 120, 120, 20 + index * 145, 45, COLORS.streamBackground, 15, `app/src/main/res/drawable/${assetName}`);
      if (asset) applyImageFill(preview, asset, `app/src/main/res/drawable/${assetName}`);
      preview.setPluginData("assetAudit", asset ? "Android resource imported as image fill" : "Android resource missing from plugin bundle");
    });
    frame.setPluginData("built", "1");
  }
  return frame;
}

function buildUiKitLegend(uiKit, fonts, tokens) {
  if (uiKit.getPluginData("legendBuilt") === "1") return;
  appendText(uiKit, "UI KIT / title", "UI KIT · CURRENT ANDROID UI", {size: 22, width: 450, height: 30, color: COLORS.textPrimary, weight: "heavy"}, fonts, "tools/figma-export/screens.json");
  const fontText = fonts.fallback ? "Font audit: Muller unavailable → Inter fallback" : "Font audit: Muller loaded";
  appendText(uiKit, "UI KIT / font audit", fontText, {size: 13, width: 500, height: 22, color: fonts.fallback ? COLORS.xtra : COLORS.myata}, fonts, "tools/figma-export/code.js");
  appendText(uiKit, "UI KIT / source audit", "XML Views · 375×667 dp · components are editable", {size: 13, width: 550, height: 22, color: COLORS.muted}, fonts, "tools/figma-export/screens.json");

  const tokenEntries = Object.entries(COLORS);
  tokenEntries.forEach(([token, hex], index) => {
    const col = index % 6;
    const row = Math.floor(index / 6);
    const x = 20 + col * 135;
    const y = 700 + row * 72;
    const swatch = appendRectangle(uiKit, `Token / ${token}`, 112, 50, x, y, hex, 15, "Android colors.xml and XML layout values");
    swatch.setPluginData("token", token);
    const labelColor = token === "gold" || token === "white" || token === "xtra" || token === "selected" ? COLORS.black : COLORS.white;
    appendText(uiKit, `Token label / ${token}`, `${token}\n${hex}`, {size: 9, width: 104, height: 30, color: labelColor, align: "CENTER", lineHeight: 11}, fonts, "tools/figma-export/screens.json").x = x + 4;
    const labels = uiKit.children.filter((child) => child.name.startsWith(`Token label / ${token}`));
    if (labels.length) labels[labels.length - 1].y = y + 9;
  });
  uiKit.setPluginData("legendBuilt", "1");
}

function buildHome(home, fonts, components) {
  home.layoutMode = "NONE";
  home.clipsContent = false;
  home.fills = [];
  home.setPluginData("viewport", `${WIDTH}x${HEIGHT} dp`);
  home.setPluginData("source", JSON.stringify([SOURCE.activityLayout, SOURCE.homeLayout, SOURCE.playlistLayout]));
  home.setPluginData("homeVisualVersion", HOME_VERSION);
  appendRectangle(home, "Background / #1C3F5F", WIDTH, HEIGHT, 0, 0, COLORS.background, 0, SOURCE.homeLayout);
  const title = appendText(home, "Text / Наши потоки", "Наши потоки", {size: 25, width: 330, height: 34, color: COLORS.textPrimary}, fonts, SOURCE.homeLayout);
  title.x = 22;
  title.y = 60;
  const heading = home.children.find((child) => child.name.startsWith("Text / Наши потоки"));
  if (heading) heading.y = 60;

  const streamViewport = ensureFrame(home, "HorizontalScrollView / streams", WIDTH, 218, 0, 105);
  streamViewport.clipsContent = true;
  const streamTrack = makeAutoFrame(streamViewport, "Auto Layout / stream cards", 997, 198, "HORIZONTAL", SOURCE.homeLayout);
  streamTrack.x = 0;
  streamTrack.y = 10;
  streamTrack.paddingLeft = 21;
  streamTrack.paddingRight = 14;
  streamTrack.itemSpacing = 7;
  [components.stationMyata, components.stationGold, components.stationXtra].forEach((component, index) => {
    const instance = component.createInstance();
    instance.name = `Instance / Station Card / ${index === 0 ? "MYATA" : index === 1 ? "GOLD" : "XTRA"}`;
    streamTrack.appendChild(instance);
    setFixedSizing(instance);
  });

  const playlistHeading = appendText(home, "Text / Мятные плейлисты", "Мятные плейлисты", {size: 25, width: 330, height: 34, color: COLORS.textPrimary}, fonts, SOURCE.homeLayout);
  playlistHeading.x = 22;
  playlistHeading.y = 350;
  const playlistViewport = ensureFrame(home, "RecyclerView / playlists", WIDTH, 190, 0, 390);
  playlistViewport.clipsContent = true;
  const playlistTrack = makeAutoFrame(playlistViewport, "Auto Layout / playlist cards", 471, 140, "HORIZONTAL", SOURCE.playlistLayout);
  playlistTrack.x = 0;
  playlistTrack.y = 0;
  playlistTrack.paddingLeft = 9;
  playlistTrack.paddingRight = 14;
  playlistTrack.itemSpacing = 14;
  components.playlists.forEach((component, index) => {
    const number = String(index + 1).padStart(2, "0");
    const instance = component.createInstance();
    instance.name = `Instance / Playlist Card / ${number}`;
    playlistTrack.appendChild(instance);
    setFixedSizing(instance);
  });

  const nav = components.bottomNavigation.createInstance();
  nav.name = "Instance / Bottom Navigation / Home active";
  nav.x = 25;
  nav.y = 603;
  home.appendChild(nav);
  mark(nav, SOURCE.activityLayout);
  home.setPluginData("built", "1");
  return true;
}

function collectAssetsFromUi() {
  if (typeof __html__ === "undefined" || !figma.ui || typeof figma.showUI !== "function") return Promise.resolve([]);
  return new Promise((resolve) => {
    let settled = false;
    let timeoutId = null;
    const finish = (assets) => {
      if (settled) return;
      settled = true;
      if (timeoutId) clearTimeout(timeoutId);
      resolve(Array.isArray(assets) ? assets : []);
    };
    figma.ui.onmessage = (message) => {
      if (message && message.type === "assets-ready") finish(message.assets);
      if (message && message.type === "skip") finish([]);
    };
    figma.showUI(__html__, {width: 440, height: 300, themeColors: true});
    timeoutId = setTimeout(() => finish([]), 15000);
  });
}

async function main() {
  const assets = await collectAssetsFromUi();
  const page = ensurePage();
  await figma.setCurrentPageAsync(page);
  const fonts = await resolveFonts();
  const tokens = await createPaintTokens();
  await createTypographyStyles(fonts);
  ensureReference(page, assets);
  const uiKit = ensureOwnedFrame(page, UI_KIT_NAME, 1200, 1120, 430, 0, "tools/figma-export/screens.json");
  refreshPluginUiKit(uiKit);
  uiKit.fills = [solid(COLORS.cardSurface)];
  uiKit.strokes = [solid(COLORS.border)];
  uiKit.strokeWeight = 1;
  const bottomNavigation = buildBottomNavigation(uiKit, fonts);
  const playButton = buildPlayButton(uiKit);
  buildPlayerControls(uiKit, fonts, playButton);
  const stationMyata = buildStationCard(uiKit, fonts, "MYATA", COLORS.myata, 20, assets);
  const stationGold = buildStationCard(uiKit, fonts, "GOLD", COLORS.gold, 360, assets);
  const stationXtra = buildStationCard(uiKit, fonts, "XTRA", COLORS.xtra, 700, assets);
  const androidCover1 = findAsset(assets, "zaglushka_1_img.png");
  const androidCover3 = findAsset(assets, "zaglushka_3_img.png");
  const androidCover4 = findAsset(assets, "zaglushka_4_img.png");
  const cover1 = findAsset(assets, "custom-cover-01.png") || findAsset(assets, "reference-cover-01.png") || androidCover1;
  const cover2 = findAsset(assets, "custom-cover-02.png") || findAsset(assets, "reference-cover-02.png") || androidCover3;
  const cover3 = findAsset(assets, "custom-cover-03.png") || findAsset(assets, "reference-cover-03.png") || androidCover4;
  const playlist01 = buildPlaylistCard(uiKit, fonts, "01", cover1, androidCover1);
  const playlist02 = buildPlaylistCard(uiKit, fonts, "02", cover2, androidCover3);
  const playlist03 = buildPlaylistCard(uiKit, fonts, "03", cover3, androidCover4);
  const primaryButton = buildPrimaryButton(uiKit, fonts);
  const donationAmount = buildDonateAmount(uiKit, fonts);
  const historyRow = buildHistoryRow(uiKit, fonts);
  buildUiKitLegend(uiKit, fonts, tokens);
  buildRasterAssetLegend(uiKit, fonts, assets);
  uiKit.setPluginData("homeVisualVersion", HOME_VERSION);

  const oldHome = page.children.find((child) => child.type === "FRAME" && child.name === HOME_NAME && isPluginOwned(child));
  if (oldHome) oldHome.remove();
  const home = ensureOwnedFrame(page, HOME_NAME, WIDTH, HEIGHT, 0, 0, SOURCE.homeLayout);
  const homeCreated = buildHome(home, fonts, {
    bottomNavigation,
    playButton,
    stationMyata,
    stationGold,
    stationXtra,
    playlists: [playlist01, playlist02, playlist03],
    primaryButton,
    donationAmount,
    historyRow
  });

  page.setPluginData("plugin", PAGE_MARKER);
  page.setPluginData("fontAudit", JSON.stringify(fonts));
  page.setPluginData("tokens", JSON.stringify({colors: Object.keys(COLORS), paintStyles: Object.keys(tokens.styles), variables: Object.keys(tokens.variables)}));
  page.setPluginData("stage", "home-and-ui-kit");
  page.setPluginData("homeVisualVersion", HOME_VERSION);
  page.setPluginData("assetAudit", JSON.stringify(assets.map((asset) => asset.name)));
  figma.notify(`CURRENT ANDROID UI готов: Home ${WIDTH}×${HEIGHT}. ${fonts.source}.`);
  figma.closePlugin();
  return {pageId: page.id, homeCreated, fontAudit: fonts, variableCount: tokens.variableCount, componentCount: 11};
}

main().catch((error) => {
  console.error(error);
  figma.notify(`Current Android UI Export: ошибка — ${error.message || error}`);
  figma.closePlugin();
});
