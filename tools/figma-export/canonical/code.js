/*
 * Radio Myata - canonical design snapshot exporter.
 *
 * READ-ONLY BY CONSTRUCTION. This plugin never assigns to a node property, never
 * creates or removes a node, and never writes plugin data. It reads the open
 * document, serialises it, and hands the JSON to its own UI so a human can save
 * it. Nothing in the Figma document changes.
 *
 * It also never reads image bytes: an image fill is recorded by its hash and
 * scale mode only, so no artwork can end up in the repository.
 *
 * No file key, node id or filesystem path is hardcoded - whatever page is open
 * (or selected) when you press Export is what gets exported.
 */

const SCHEMA_VERSION = "1.0.0";
const PLUGIN_VERSION = "1.0.0";
const MAX_DEPTH = 60;

figma.showUI(__html__, { width: 380, height: 520 });

// ---------- small helpers ----------

const isMixed = (value) => value === figma.mixed;
const plain = (value) => (isMixed(value) ? "MIXED" : value);
const round = (n) => (typeof n === "number" && isFinite(n) ? Math.round(n * 100) / 100 : n);

function hex(color) {
  if (!color) return null;
  const to = (c) => Math.round(Math.max(0, Math.min(1, c)) * 255).toString(16).padStart(2, "0");
  const base = "#" + to(color.r) + to(color.g) + to(color.b);
  return color.a !== undefined && color.a < 1
    ? base + to(color.a)
    : base;
}

function has(node, prop) {
  try {
    return prop in node && node[prop] !== undefined;
  } catch (e) {
    return false;
  }
}

// ---------- paints, strokes, effects ----------

function serialisePaint(paint) {
  const base = {
    type: paint.type,
    visible: paint.visible !== false,
    opacity: paint.opacity === undefined ? 1 : round(paint.opacity),
    blendMode: paint.blendMode
  };
  if (paint.type === "SOLID") {
    base.color = hex(paint.color);
  } else if (paint.type === "IMAGE" || paint.type === "VIDEO") {
    // Metadata only. Binary data is deliberately never requested.
    base.imageHash = paint.imageHash || null;
    base.scaleMode = paint.scaleMode || null;
    base.imageTransform = paint.imageTransform || null;
    base.note = "binary image data intentionally not exported";
  } else if (paint.type && paint.type.indexOf("GRADIENT") === 0) {
    base.gradientTransform = paint.gradientTransform || null;
    base.gradientStops = (paint.gradientStops || []).map((stop) => ({
      position: round(stop.position),
      color: hex(stop.color)
    }));
  }
  if (paint.boundVariables) base.boundVariables = paint.boundVariables;
  return base;
}

function serialisePaints(value) {
  if (isMixed(value)) return "MIXED";
  if (!Array.isArray(value)) return null;
  return value.map(serialisePaint);
}

function serialiseEffect(effect) {
  return {
    type: effect.type,
    visible: effect.visible !== false,
    radius: round(effect.radius),
    color: effect.color ? hex(effect.color) : undefined,
    offset: effect.offset ? { x: round(effect.offset.x), y: round(effect.offset.y) } : undefined,
    spread: effect.spread === undefined ? undefined : round(effect.spread),
    blendMode: effect.blendMode
  };
}

// ---------- variables ----------

const variableIds = new Set();

function collectVariableIds(node) {
  const bound = has(node, "boundVariables") ? node.boundVariables : null;
  if (!bound) return;
  const visit = (value) => {
    if (!value) return;
    if (Array.isArray(value)) return value.forEach(visit);
    if (typeof value === "object") {
      if (value.type === "VARIABLE_ALIAS" && value.id) variableIds.add(value.id);
      else Object.keys(value).forEach((k) => visit(value[k]));
    }
  };
  visit(bound);
}

async function resolveVariables(activeModes) {
  const out = {};
  const collections = {};
  for (const id of variableIds) {
    let variable = null;
    try {
      variable = await figma.variables.getVariableByIdAsync(id);
    } catch (e) {
      variable = null;
    }
    if (!variable) {
      out[id] = { id, error: "variable not found or not readable" };
      continue;
    }

    let collection = collections[variable.variableCollectionId];
    if (!collection) {
      try {
        const raw = await figma.variables.getVariableCollectionByIdAsync(variable.variableCollectionId);
        collection = raw
          ? {
              id: raw.id,
              name: raw.name,
              defaultModeId: raw.defaultModeId,
              modes: raw.modes.map((m) => ({ modeId: m.modeId, name: m.name }))
            }
          : null;
      } catch (e) {
        collection = null;
      }
      collections[variable.variableCollectionId] = collection;
    }

    // Which mode this export resolved against, and the value in it.
    const modeId =
      (activeModes && activeModes[variable.variableCollectionId]) ||
      (collection && collection.defaultModeId) ||
      null;

    const valuesByMode = {};
    Object.keys(variable.valuesByMode || {}).forEach((mid) => {
      const raw = variable.valuesByMode[mid];
      valuesByMode[mid] =
        raw && typeof raw === "object" && raw.type === "VARIABLE_ALIAS"
          ? { alias: raw.id }
          : variable.resolvedType === "COLOR" && raw && typeof raw === "object"
            ? hex(raw)
            : raw;
    });

    out[id] = {
      id,
      name: variable.name,
      resolvedType: variable.resolvedType,
      collection: collection ? { id: collection.id, name: collection.name } : null,
      modes: collection ? collection.modes : [],
      exportedModeId: modeId,
      exportedModeName:
        collection && modeId
          ? (collection.modes.find((m) => m.modeId === modeId) || {}).name || null
          : null,
      resolvedValueForExportedMode: modeId ? valuesByMode[modeId] : null,
      valuesByMode
    };
  }
  return out;
}

// ---------- node serialisation ----------

async function serialiseNode(node, depth) {
  collectVariableIds(node);

  const out = {
    id: node.id,
    name: node.name,
    type: node.type,
    visible: node.visible !== false,
    locked: node.locked === true
  };

  // geometry
  if (has(node, "x")) out.x = round(node.x);
  if (has(node, "y")) out.y = round(node.y);
  if (has(node, "width")) out.width = round(node.width);
  if (has(node, "height")) out.height = round(node.height);
  if (has(node, "rotation") && node.rotation) out.rotation = round(node.rotation);
  if (has(node, "opacity") && node.opacity !== 1) out.opacity = round(node.opacity);
  if (has(node, "blendMode") && node.blendMode !== "PASS_THROUGH") out.blendMode = node.blendMode;
  if (has(node, "clipsContent")) out.clipsContent = node.clipsContent;

  // auto layout
  if (has(node, "layoutMode")) {
    out.layout = {
      layoutMode: node.layoutMode,
      itemSpacing: round(node.itemSpacing),
      counterAxisSpacing: has(node, "counterAxisSpacing") ? round(node.counterAxisSpacing) : undefined,
      padding: {
        top: round(node.paddingTop),
        right: round(node.paddingRight),
        bottom: round(node.paddingBottom),
        left: round(node.paddingLeft)
      },
      primaryAxisAlignItems: node.primaryAxisAlignItems,
      counterAxisAlignItems: node.counterAxisAlignItems,
      primaryAxisSizingMode: node.primaryAxisSizingMode,
      counterAxisSizingMode: node.counterAxisSizingMode,
      layoutWrap: has(node, "layoutWrap") ? node.layoutWrap : undefined
    };
  }
  if (has(node, "layoutAlign")) out.layoutAlign = node.layoutAlign;
  if (has(node, "layoutGrow")) out.layoutGrow = node.layoutGrow;
  // A child of an auto-layout frame can opt out of the flow entirely. That is
  // invisible in geometry but decides whether the layout is reproducible in
  // Android, so it has to be in the snapshot.
  if (has(node, "layoutPositioning")) out.layoutPositioning = node.layoutPositioning;
  if (has(node, "layoutSizingHorizontal")) out.layoutSizingHorizontal = node.layoutSizingHorizontal;
  if (has(node, "layoutSizingVertical")) out.layoutSizingVertical = node.layoutSizingVertical;
  if (has(node, "constraints")) out.constraints = node.constraints;

  // corners
  if (has(node, "cornerRadius")) {
    if (isMixed(node.cornerRadius)) {
      out.cornerRadius = {
        topLeft: round(node.topLeftRadius),
        topRight: round(node.topRightRadius),
        bottomRight: round(node.bottomRightRadius),
        bottomLeft: round(node.bottomLeftRadius)
      };
    } else {
      out.cornerRadius = round(node.cornerRadius);
    }
  }

  // paint
  if (has(node, "fills")) out.fills = serialisePaints(node.fills);
  if (has(node, "strokes") && node.strokes && node.strokes.length) {
    out.strokes = serialisePaints(node.strokes);
    out.strokeWeight = isMixed(node.strokeWeight)
      ? {
          top: round(node.strokeTopWeight),
          right: round(node.strokeRightWeight),
          bottom: round(node.strokeBottomWeight),
          left: round(node.strokeLeftWeight)
        }
      : round(node.strokeWeight);
    if (has(node, "strokeAlign")) out.strokeAlign = node.strokeAlign;
    if (has(node, "dashPattern") && node.dashPattern && node.dashPattern.length) {
      out.dashPattern = node.dashPattern;
    }
  }
  if (has(node, "effects") && node.effects && node.effects.length) {
    out.effects = node.effects.map(serialiseEffect);
  }

  // text
  if (node.type === "TEXT") {
    const fontName = plain(node.fontName);
    out.text = {
      characters: node.characters,
      fontFamily: fontName && fontName !== "MIXED" ? fontName.family : "MIXED",
      fontStyle: fontName && fontName !== "MIXED" ? fontName.style : "MIXED",
      fontSize: plain(node.fontSize),
      fontWeight: plain(node.fontWeight),
      lineHeight: plain(node.lineHeight),
      letterSpacing: plain(node.letterSpacing),
      textAlignHorizontal: node.textAlignHorizontal,
      textAlignVertical: node.textAlignVertical,
      textAutoResize: node.textAutoResize,
      // The ellipsis settings. Without these an audit cannot tell a wrapped
      // title from one Figma is quietly truncating.
      textTruncation: has(node, "textTruncation") ? node.textTruncation : undefined,
      maxLines: has(node, "maxLines") ? node.maxLines : undefined,
      textCase: plain(node.textCase),
      textDecoration: plain(node.textDecoration),
      paragraphSpacing: node.paragraphSpacing,
      paragraphIndent: node.paragraphIndent
    };
    if (has(node, "textStyleId")) out.text.textStyleId = plain(node.textStyleId);
  }

  // components, instances, variants
  if (node.type === "COMPONENT" || node.type === "COMPONENT_SET") {
    out.component = { key: node.key || null, description: node.description || "" };
    if (has(node, "variantProperties") && node.variantProperties) {
      out.component.variantProperties = node.variantProperties;
    }
    if (node.type === "COMPONENT_SET" && has(node, "variantGroupProperties")) {
      try {
        out.component.variantGroupProperties = node.variantGroupProperties;
      } catch (e) {
        /* not always readable */
      }
    }
  }
  if (node.type === "INSTANCE") {
    out.instance = {};
    try {
      const main = await node.getMainComponentAsync();
      out.instance.mainComponent = main
        ? { id: main.id, name: main.name, key: main.key || null }
        : null;
    } catch (e) {
      out.instance.mainComponent = null;
    }
    if (has(node, "variantProperties") && node.variantProperties) {
      out.instance.variantProperties = node.variantProperties;
    }
    if (has(node, "componentProperties") && node.componentProperties) {
      const props = {};
      Object.keys(node.componentProperties).forEach((k) => {
        const p = node.componentProperties[k];
        props[k] = { type: p.type, value: p.type === "INSTANCE_SWAP" ? "<instance>" : p.value };
      });
      out.instance.componentProperties = props;
    }
  }

  // style + variable bindings
  if (has(node, "boundVariables") && node.boundVariables && Object.keys(node.boundVariables).length) {
    out.boundVariables = node.boundVariables;
  }
  if (has(node, "fillStyleId")) {
    const v = plain(node.fillStyleId);
    if (v) out.fillStyleId = v;
  }
  if (has(node, "strokeStyleId") && node.strokeStyleId) out.strokeStyleId = node.strokeStyleId;
  if (has(node, "effectStyleId") && node.effectStyleId) out.effectStyleId = node.effectStyleId;

  // children
  if (has(node, "children") && node.children.length) {
    if (depth >= MAX_DEPTH) {
      out.childrenTruncated = true;
    } else {
      out.children = [];
      for (const child of node.children) {
        out.children.push(await serialiseNode(child, depth + 1));
      }
    }
  }

  return out;
}

// ---------- export ----------

function activeModesForPage(page) {
  // Explicit modes set on the page win; anything else falls back to the
  // collection default, which resolveVariables handles.
  try {
    return page.explicitVariableModes || {};
  } catch (e) {
    return {};
  }
}

async function buildSnapshot(scope) {
  variableIds.clear();

  const page = figma.currentPage;
  const roots =
    scope === "selection" && page.selection.length
      ? page.selection.slice()
      : page.children.slice();

  const frames = [];
  for (const node of roots) {
    frames.push(await serialiseNode(node, 0));
  }

  const activeModes = activeModesForPage(page);
  const variables = await resolveVariables(activeModes);

  let fileName = null;
  let fileKey = null;
  try {
    fileName = figma.root.name;
  } catch (e) {
    /* ignore */
  }
  try {
    fileKey = figma.fileKey || null; // undefined unless the plugin is allowed to see it
  } catch (e) {
    fileKey = null;
  }

  return {
    schemaVersion: SCHEMA_VERSION,
    pluginVersion: PLUGIN_VERSION,
    exportedAt: new Date().toISOString(),
    source: {
      fileName,
      fileKey,
      pageName: page.name,
      pageId: page.id,
      scope: scope === "selection" && page.selection.length ? "selection" : "page",
      topLevelFrameCount: frames.length,
      explicitVariableModes: activeModes
    },
    notes: [
      "Read-only export. The Figma document was not modified.",
      "Image fills are recorded as hash and scale mode only; no binary image data is exported."
    ],
    variables,
    frames
  };
}

figma.ui.onmessage = async (msg) => {
  if (!msg || msg.type !== "export") return;
  try {
    figma.ui.postMessage({ type: "status", text: "Reading document…" });
    const snapshot = await buildSnapshot(msg.scope);
    const json = JSON.stringify(snapshot, null, 2);
    figma.ui.postMessage({
      type: "result",
      json,
      summary: {
        page: snapshot.source.pageName,
        file: snapshot.source.fileName,
        scope: snapshot.source.scope,
        frames: snapshot.source.topLevelFrameCount,
        variables: Object.keys(snapshot.variables).length,
        bytes: json.length
      }
    });
  } catch (error) {
    figma.ui.postMessage({ type: "error", text: String((error && error.stack) || error) });
  }
};
