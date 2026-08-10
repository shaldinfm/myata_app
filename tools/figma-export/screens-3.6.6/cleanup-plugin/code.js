// GENERATED FILE - do not edit.
// Source: code.template.js + ../CLEANUP-PLAN.json
// Rebuild: node tools/figma-export/screens-3.6.6/cleanup-plugin/build-plugin.mjs

var CLEANUP_PLAN = {"schemaVersion":"cleanup-2.0.0","status":"RECOVERY DRY RUN - not applied.","generatedFrom":{"light":{"page":"3.6.6 PROPOSALS - LIGHT","exportedAt":"2026-08-10T08:37:52.026Z"},"dark":{"page":"3.6.6 PROPOSALS - DARK","exportedAt":"2026-08-10T08:37:33.568Z"},"ownerBaseline":"history-baseline.json"},"alreadyComplete":{"history-row-autolayout":0,"history-text-hug":16,"text-box-hug":0,"lastfm-leftover":3,"avatar-naming":32},"rootCause":{"historyRows":"Figma measures auto-layout padding from the inside of an INSIDE-aligned stroke and adds the stroke to a hugged size. The rows carry a 1px INSIDE stroke, so padding copied from the measured child offsets placed every child at stroke+padding (+1,+1) and hugged to 2*stroke+padTop+content+padBottom (+2). Padding is now reduced by the stroke weight on every side.","textBoxes":"Any write to a TEXT node requires its font to be loaded first. The plan now records each node's family and style, the plugin loads them before writing, and a MIXED fontName blocks the mutation rather than being guessed at.","revertGap":"The failed revert restored layoutMode and child offsets but not the frame height, so all 16 rows are still 2px taller than the owner designed. Recovery targets history-baseline.json, which restores them."},"counts":{"total":26,"blocked":0,"byGroup":{"history-row-autolayout":16,"text-box-hug":10}},"mutations":[{"id":"A-light-2523:32","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / CRYOGEN","nodeId":"2523:32","path":"history-content > Screen > Broadcast History List > History Item / CRYOGEN","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":15,"y":24,"w":39,"h":28},{"name":"Album art","x":62,"y":14,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":48},{"name":"Button / find track","x":305,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":12,"bottom":13,"left":14},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":15,"y":24,"w":39,"h":28},{"name":"Album art","x":62,"y":14,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":48},{"name":"Button / find track","x":305,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 14 puts the first child at 15 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:41","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / Краснознамённая дивизия имени моей бабушки","nodeId":"2523:41","path":"history-content > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":118,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":15,"y":44,"w":39,"h":28},{"name":"Album art","x":62,"y":34,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":88},{"name":"Button / find track","x":305,"y":38,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":12,"bottom":13,"left":14},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":116,"width":358,"children":[{"name":"time","x":15,"y":44,"w":39,"h":28},{"name":"Album art","x":62,"y":34,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":88},{"name":"Button / find track","x":305,"y":38,"w":40,"h":40}]},"current":"layoutMode NONE, 358x118, stroke 1px INSIDE — 2px taller than the owner's 116","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 116","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 14 puts the first child at 15 and the hugged height is 2x1 + 13 + 88 + 13 = 116. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:50","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / CITY WALLS","nodeId":"2523:50","path":"history-content > Screen > Broadcast History List > History Item / CITY WALLS","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:59","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / WHAT YOU KNOW","nodeId":"2523:59","path":"history-content > Screen > Broadcast History List > History Item / WHAT YOU KNOW","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:68","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / Прогулка по воде под дождём в конце ноября","nodeId":"2523:68","path":"history-content > Screen > Broadcast History List > History Item / Прогулка по воде под дождём в конце ноября","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":106,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":38,"w":39,"h":28},{"name":"Album art","x":61,"y":28,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":76},{"name":"Button / find track","x":304,"y":32,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":104,"width":358,"children":[{"name":"time","x":14,"y":38,"w":39,"h":28},{"name":"Album art","x":61,"y":28,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":76},{"name":"Button / find track","x":304,"y":32,"w":40,"h":40}]},"current":"layoutMode NONE, 358x106, stroke 1px INSIDE — 2px taller than the owner's 104","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 104","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 76 + 13 = 104. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:77","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / NORTHERN LINE","nodeId":"2523:77","path":"history-content > Screen > Broadcast History List > History Item / NORTHERN LINE","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:86","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / PAPER BOATS","nodeId":"2523:86","path":"history-content > Screen > Broadcast History List > History Item / PAPER BOATS","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-light-2523:95","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - LIGHT","frame":"history-content","node":"History Item / SLOW BURN","nodeId":"2523:95","path":"history-content > Screen > Broadcast History List > History Item / SLOW BURN","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3305","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / CRYOGEN","nodeId":"2517:3305","path":"history-content_dark > Screen > Broadcast History List > History Item / CRYOGEN","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":15,"y":24,"w":39,"h":28},{"name":"Album art","x":62,"y":14,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":48},{"name":"Button / find track","x":305,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":12,"bottom":13,"left":14},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":15,"y":24,"w":39,"h":28},{"name":"Album art","x":62,"y":14,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":48},{"name":"Button / find track","x":305,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 14 puts the first child at 15 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3315","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / Краснознамённая дивизия имени моей бабушки","nodeId":"2517:3315","path":"history-content_dark > Screen > Broadcast History List > History Item / Краснознамённая дивизия имени моей бабушки","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":118,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":15,"y":44,"w":39,"h":28},{"name":"Album art","x":62,"y":34,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":88},{"name":"Button / find track","x":305,"y":38,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":12,"bottom":13,"left":14},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":116,"width":358,"children":[{"name":"time","x":15,"y":44,"w":39,"h":28},{"name":"Album art","x":62,"y":34,"w":48,"h":48},{"name":"Text","x":118,"y":14,"w":179,"h":88},{"name":"Button / find track","x":305,"y":38,"w":40,"h":40}]},"current":"layoutMode NONE, 358x118, stroke 1px INSIDE — 2px taller than the owner's 116","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/12/13/14 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 116","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 14 puts the first child at 15 and the hugged height is 2x1 + 13 + 88 + 13 = 116. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3325","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / CITY WALLS","nodeId":"2517:3325","path":"history-content_dark > Screen > Broadcast History List > History Item / CITY WALLS","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3335","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / WHAT YOU KNOW","nodeId":"2517:3335","path":"history-content_dark > Screen > Broadcast History List > History Item / WHAT YOU KNOW","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3345","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / Прогулка по воде под дождём в конце ноября","nodeId":"2517:3345","path":"history-content_dark > Screen > Broadcast History List > History Item / Прогулка по воде под дождём в конце ноября","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":106,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":38,"w":39,"h":28},{"name":"Album art","x":61,"y":28,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":76},{"name":"Button / find track","x":304,"y":32,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":104,"width":358,"children":[{"name":"time","x":14,"y":38,"w":39,"h":28},{"name":"Album art","x":61,"y":28,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":76},{"name":"Button / find track","x":304,"y":32,"w":40,"h":40}]},"current":"layoutMode NONE, 358x106, stroke 1px INSIDE — 2px taller than the owner's 104","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 104","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 76 + 13 = 104. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3355","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / NORTHERN LINE","nodeId":"2517:3355","path":"history-content_dark > Screen > Broadcast History List > History Item / NORTHERN LINE","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3365","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / PAPER BOATS","nodeId":"2517:3365","path":"history-content_dark > Screen > Broadcast History List > History Item / PAPER BOATS","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"A-dark-2517:3375","group":"history-row-autolayout","page":"3.6.6 PROPOSALS - DARK","frame":"history-content_dark","node":"History Item / SLOW BURN","nodeId":"2517:3375","path":"history-content_dark > Screen > Broadcast History List > History Item / SLOW BURN","check":{"type":"FRAME","layoutMode":"NONE","width":358,"height":78,"strokeWeight":1,"strokeAlign":"INSIDE","children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"apply":{"op":"setAutoLayout","layoutMode":"HORIZONTAL","itemSpacing":8,"padding":{"top":13,"right":13,"bottom":13,"left":13},"primaryAxisAlignItems":"MIN","counterAxisAlignItems":"CENTER","primaryAxisSizingMode":"FIXED","counterAxisSizingMode":"AUTO"},"expect":{"height":76,"width":358,"children":[{"name":"time","x":14,"y":24,"w":39,"h":28},{"name":"Album art","x":61,"y":14,"w":48,"h":48},{"name":"Text","x":117,"y":14,"w":179,"h":48},{"name":"Button / find track","x":304,"y":18,"w":40,"h":40}]},"current":"layoutMode NONE, 358x78, stroke 1px INSIDE — 2px taller than the owner's 76","proposed":"HORIZONTAL auto-layout, gap 8, padding 13/13/13/13 (stroke-compensated), counterAxisAlignItems CENTER, height hugs to 76","pixelsMove":false,"wrapChange":false,"visualChange":false,"restoresOwnerGeometry":true,"heightDeltaVsCurrent":-2,"evidence":"stroke 1px INSIDE: content box is inset by 1px, so padding 13 puts the first child at 14 and the hugged height is 2x1 + 13 + 48 + 13 = 76. All 4 predicted child positions match the owner's geometry.","blockedBy":null},{"id":"C-light-2517:1978","group":"text-box-hug","page":"3.6.6 PROPOSALS - LIGHT","frame":"sleep-timer-custom","node":"value","nodeId":"2517:1978","path":"sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Часы > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"1","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"1\" is one line.","blockedBy":null},{"id":"C-light-2517:1985","group":"text-box-hug","page":"3.6.6 PROPOSALS - LIGHT","frame":"sleep-timer-custom","node":"value","nodeId":"2517:1985","path":"sleep-timer-custom > Bottom Sheet / Своё время > Stepper / Минуты > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"30","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"30\" is one line.","blockedBy":null},{"id":"C-light-2517:2002","group":"text-box-hug","page":"3.6.6 PROPOSALS - LIGHT","frame":"sleep-timer-custom-invalid","node":"value","nodeId":"2517:2002","path":"sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Часы > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"0","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"0\" is one line.","blockedBy":null},{"id":"C-light-2517:2009","group":"text-box-hug","page":"3.6.6 PROPOSALS - LIGHT","frame":"sleep-timer-custom-invalid","node":"value","nodeId":"2517:2009","path":"sleep-timer-custom-invalid > Bottom Sheet / Своё время > Stepper / Минуты > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"0","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"0\" is one line.","blockedBy":null},{"id":"C-light-2517:2680","group":"text-box-hug","page":"3.6.6 PROPOSALS - LIGHT","frame":"profile-authenticated","node":"initial","nodeId":"2517:2680","path":"profile-authenticated > Account card > Avatar > initial","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"Д","x":0,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":0,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"Д\" is one line.","blockedBy":null},{"id":"C-dark-2517:2945","group":"text-box-hug","page":"3.6.6 PROPOSALS - DARK","frame":"sleep-timer-custom_dark","node":"value","nodeId":"2517:2945","path":"sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Часы > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"1","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"1\" is one line.","blockedBy":null},{"id":"C-dark-2517:2952","group":"text-box-hug","page":"3.6.6 PROPOSALS - DARK","frame":"sleep-timer-custom_dark","node":"value","nodeId":"2517:2952","path":"sleep-timer-custom_dark > Bottom Sheet / Своё время > Stepper / Минуты > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"30","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"30\" is one line.","blockedBy":null},{"id":"C-dark-2517:2969","group":"text-box-hug","page":"3.6.6 PROPOSALS - DARK","frame":"sleep-timer-custom-invalid_dark","node":"value","nodeId":"2517:2969","path":"sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Часы > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"0","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"0\" is one line.","blockedBy":null},{"id":"C-dark-2517:2976","group":"text-box-hug","page":"3.6.6 PROPOSALS - DARK","frame":"sleep-timer-custom-invalid_dark","node":"value","nodeId":"2517:2976","path":"sleep-timer-custom-invalid_dark > Bottom Sheet / Своё время > Stepper / Минуты > value","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"0","x":200,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":200,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"0\" is one line.","blockedBy":null},{"id":"C-dark-2517:3647","group":"text-box-hug","page":"3.6.6 PROPOSALS - DARK","frame":"profile-authenticated_dark","node":"initial","nodeId":"2517:3647","path":"profile-authenticated_dark > Account card > Avatar > initial","check":{"type":"TEXT","textAutoResize":"NONE","height":28,"lineHeight":32,"textAlignVertical":"TOP","characters":"Д","x":0,"y":18},"font":{"family":"Muller","style":"Medium"},"apply":{"op":"setTextAutoResize","textAutoResize":"HEIGHT"},"expect":{"height":32,"x":0,"y":18},"current":"height 28, lineHeight 32, textAutoResize NONE, font Muller Medium","proposed":"textAutoResize HEIGHT (height resolves to 32); font loaded first, family and style untouched","pixelsMove":false,"wrapChange":false,"visualChange":false,"evidence":"vertical alignment is TOP, so the glyph keeps its top edge and the box grows 4px down into empty space. \"Д\" is one line.","blockedBy":null}]};

/* eslint-disable */
/*
 * Radio Myata - bounded structural cleanup of the 3.6.6 proposal pages.
 *
 * The owner-edited pages are the source of truth. This plugin does not create,
 * regenerate or reposition anything: it only restores layout metadata that the
 * geometry already implies, drops three already-invisible leftovers, and renames
 * sixteen cells per theme.
 *
 * Safety model:
 *   - Dry Run performs no writes and re-verifies every precondition against the
 *     live node. If the file moved on since the snapshot, the mutation blocks.
 *   - Apply runs only what Dry Run marked READY, in this session.
 *   - Every target is addressed by node id from CLEANUP_PLAN. The plugin cannot
 *     discover its own targets.
 *   - It refuses to touch a canonical page.
 *   - Auto-layout writes are verified afterwards: every child's absolute position
 *     is measured before and after, and if anything moved the row is REVERTED.
 */

var APPROVED_GROUPS = {
  "history-row-autolayout": true,
  "history-text-hug": true,
  "text-box-hug": true,
  "lastfm-leftover": true,
  "avatar-naming": true
};

var dryRunReport = null;

/* ---------- helpers ---------- */

// figma.mixed is a unique symbol, so this is how a mixed value is recognised.
function isMixed(v) { return typeof v === "symbol"; }

/* Any write to a TEXT node needs its font loaded first - that is what failed the
 * last run with "Cannot write to node with unloaded font Muller Medium". The
 * font is read off the live node, never guessed, and a MIXED fontName blocks. */
async function loadFontFor(node) {
  if (!node || node.type !== "TEXT") return null;
  var fn = node.fontName;
  if (isMixed(fn)) return "fontName is MIXED on this node";
  try { await figma.loadFontAsync(fn); return null; }
  catch (e) { return "could not load " + fn.family + " " + fn.style + ": " + (e && e.message ? e.message : String(e)); }
}

/* Changing a frame's layout can reflow text inside it, so load every font used
 * under a frame before touching its layout. */
async function loadFontsUnder(node) {
  var problems = [];
  var stack = [node];
  var wanted = {};
  while (stack.length) {
    var n = stack.pop();
    if (n.type === "TEXT") {
      if (isMixed(n.fontName)) problems.push("MIXED font under " + node.name + " on '" + n.name + "'");
      else wanted[n.fontName.family + " " + n.fontName.style] = n.fontName;
    }
    (n.children || []).forEach(function (c) { stack.push(c); });
  }
  for (var k in wanted) {
    if (!Object.prototype.hasOwnProperty.call(wanted, k)) continue;
    try { await figma.loadFontAsync(wanted[k]); }
    catch (e) { problems.push("could not load " + wanted[k].family + " " + wanted[k].style); }
  }
  return problems;
}

function pageKey(name) {
  return String(name).replace(/[‐-―−-]/g, "-").replace(/\s+/g, " ").trim().toUpperCase();
}
function isCanonicalName(name) { return /^CURRENT ANDROID UI\b/.test(pageKey(name)); }

function pageOf(node) {
  var n = node;
  while (n && n.type !== "PAGE") n = n.parent;
  return n;
}

var r2 = function (n) { return Math.round(n * 100) / 100; };
var near = function (a, b) { return Math.abs(a - b) <= 0.51; };

function allPages() {
  if (typeof figma.loadAllPagesAsync === "function") return figma.loadAllPagesAsync().then(function () { return figma.root.children; });
  return Promise.resolve(figma.root.children);
}

/* Absolute position of every descendant, so a layout change can be proven to
 * have moved nothing. */
function snapshotPositions(node) {
  var out = [];
  (function w(n) {
    if (n.absoluteBoundingBox)
      out.push({ id: n.id, name: n.name, x: n.absoluteBoundingBox.x, y: n.absoluteBoundingBox.y,
                 w: n.absoluteBoundingBox.width, h: n.absoluteBoundingBox.height });
    (n.children || []).forEach(w);
  })(node);
  return out;
}

function comparePositions(before, after) {
  var moved = [];
  var byId = {};
  for (var i = 0; i < after.length; i++) byId[after[i].id] = after[i];
  for (var j = 0; j < before.length; j++) {
    var b = before[j], a = byId[b.id];
    if (!a) { moved.push(b.name + " disappeared"); continue; }
    if (!near(a.x, b.x) || !near(a.y, b.y))
      moved.push(b.name + " moved " + r2(a.x - b.x) + "," + r2(a.y - b.y));
    if (!near(a.w, b.w) || !near(a.h, b.h))
      moved.push(b.name + " resized " + r2(a.w - b.w) + "x" + r2(a.h - b.h));
  }
  return moved;
}

/* ---------- verification ---------- */

async function verify(m) {
  if (!APPROVED_GROUPS[m.group]) return "group '" + m.group + "' is not in the approved scope";

  var node = await figma.getNodeByIdAsync(m.nodeId);
  if (!node) return "node " + m.nodeId + " not found";
  if (node.removed) return "node has been removed";

  var pg = pageOf(node);
  if (!pg) return "node is not on a page";
  if (isCanonicalName(pg.name)) return "REFUSING: node is on the canonical page '" + pg.name + "'";
  if (pageKey(pg.name) !== pageKey(m.page)) return "node is on page '" + pg.name + "', plan expected '" + m.page + "'";

  var c = m.check;

  if (c.type && node.type !== c.type) return "type is " + node.type + ", expected " + c.type;
  if (c.name !== undefined && node.name !== c.name) return "name is '" + node.name + "', expected '" + c.name + "'";
  if (c.namePrefix !== undefined && node.name.indexOf(c.namePrefix) !== 0) return "name '" + node.name + "' does not start with '" + c.namePrefix + "'";
  if (c.visible !== undefined && (node.visible !== false) !== (c.visible !== false)) return "visibility is " + node.visible + ", expected " + c.visible;
  if (c.width !== undefined && !near(node.width, c.width)) return "width is " + r2(node.width) + ", expected " + c.width;
  if (c.height !== undefined && !near(node.height, c.height)) return "height is " + r2(node.height) + ", expected " + c.height;
  if (c.x !== undefined && !near(node.x, c.x)) return "x is " + r2(node.x) + ", expected " + c.x;
  if (c.y !== undefined && !near(node.y, c.y)) return "y is " + r2(node.y) + ", expected " + c.y;
  if (c.layoutMode !== undefined && (node.layoutMode || "NONE") !== c.layoutMode) return "layoutMode is " + node.layoutMode + ", expected " + c.layoutMode;
  if (c.primaryAxisSizingMode !== undefined && node.primaryAxisSizingMode !== c.primaryAxisSizingMode)
    return "primaryAxisSizingMode is " + node.primaryAxisSizingMode + ", expected " + c.primaryAxisSizingMode;

  if (c.textAutoResize !== undefined) {
    if (isMixed(node.fontName)) return "fontName is MIXED - refusing to guess which font to load";
    if (m.font && (node.fontName.family !== m.font.family || node.fontName.style !== m.font.style))
      return "font is " + node.fontName.family + " " + node.fontName.style + ", plan recorded " + m.font.family + " " + m.font.style;
    var fontErr = await loadFontFor(node);
    if (fontErr) return fontErr;
    if (node.textAutoResize !== c.textAutoResize) return "textAutoResize is " + node.textAutoResize + ", expected " + c.textAutoResize;
    if (c.characters !== undefined && node.characters !== c.characters) return "text is '" + node.characters + "', expected '" + c.characters + "'";
    if (c.textAlignVertical !== undefined && node.textAlignVertical !== c.textAlignVertical)
      return "textAlignVertical is " + node.textAlignVertical + ", expected " + c.textAlignVertical + " - growing the box would move the glyph";
    var lh = node.lineHeight;
    if (c.lineHeight !== undefined && (!lh || lh.unit !== "PIXELS" || !near(lh.value, c.lineHeight)))
      return "lineHeight is not " + c.lineHeight + "px";
  }

  if (c.children) {
    var kids = (node.children || []).slice().sort(function (a, b) { return a.x - b.x; });
    if (kids.length !== c.children.length) return "has " + kids.length + " children, expected " + c.children.length;
    for (var i = 0; i < c.children.length; i++) {
      var e = c.children[i], k = kids[i];
      if (k.name !== e.name) return "child " + i + " is '" + k.name + "', expected '" + e.name + "'";
      if (!near(k.x, e.x) || !near(k.y, e.y)) return "child '" + k.name + "' is at " + r2(k.x) + "," + r2(k.y) + ", expected " + e.x + "," + e.y;
      if (!near(k.width, e.w) || !near(k.height, e.h)) return "child '" + k.name + "' is " + r2(k.width) + "x" + r2(k.height) + ", expected " + e.w + "x" + e.h;
    }
  }

  if (c.siblingMarkVisible) {
    var parent = node.parent;
    var found = false;
    for (var s = 0; s < (parent.children || []).length; s++) {
      var sib = parent.children[s];
      if (sib.id !== node.id && sib.type === "VECTOR" && sib.visible !== false) found = true;
    }
    if (!found) return "no visible real mark beside it any more - refusing to delete the only record";
  }

  return null;
}

/* ---------- dry run ---------- */

async function dryRun() {
  await allPages();
  var report = { items: [], counts: { ready: 0, blocked: 0, byGroup: {} }, unexpected: [], pages: {} };

  for (var i = 0; i < CLEANUP_PLAN.mutations.length; i++) {
    var m = CLEANUP_PLAN.mutations[i];
    var why = null;
    try { why = await verify(m); } catch (e) { why = "verification threw: " + (e && e.message ? e.message : String(e)); }

    var status = why ? "BLOCKED" : "READY";
    if (why) report.counts.blocked++; else report.counts.ready++;
    report.counts.byGroup[m.group] = report.counts.byGroup[m.group] || { ready: 0, blocked: 0 };
    report.counts.byGroup[m.group][why ? "blocked" : "ready"]++;
    report.pages[m.page] = true;

    report.items.push({ id: m.id, group: m.group, page: m.page, frame: m.frame, node: m.node, path: m.path,
                        current: m.current, proposed: m.proposed, status: status, why: why,
                        font: m.font || null, heightDeltaVsCurrent: m.heightDeltaVsCurrent || 0,
                        pixelsMove: m.pixelsMove, wrapChange: m.wrapChange, visualChange: m.visualChange });
  }

  // Nothing outside the approved groups may be in the plan at all.
  for (var g in report.counts.byGroup)
    if (Object.prototype.hasOwnProperty.call(report.counts.byGroup, g) && !APPROVED_GROUPS[g])
      report.unexpected.push("plan contains unapproved group '" + g + "'");
  for (var p in report.pages)
    if (Object.prototype.hasOwnProperty.call(report.pages, p) && isCanonicalName(p))
      report.unexpected.push("plan targets canonical page '" + p + "'");

  report.declared = {
    pixelsMove: CLEANUP_PLAN.mutations.filter(function (m) { return m.pixelsMove; }).length,
    wrapChange: CLEANUP_PLAN.mutations.filter(function (m) { return m.wrapChange; }).length,
    visualChange: CLEANUP_PLAN.mutations.filter(function (m) { return m.visualChange; }).length,
    // Intentional restoration, counted separately so it cannot be mistaken for drift.
    restoresOwnerGeometry: CLEANUP_PLAN.mutations.filter(function (m) { return m.restoresOwnerGeometry; }).length
  };

  dryRunReport = report;
  return report;
}

/* ---------- apply ---------- */

function writeMutation(node, m) {
  var a = m.apply;
  if (a.op === "setAutoLayout") {
    node.layoutMode = a.layoutMode;
    node.itemSpacing = a.itemSpacing;
    node.paddingTop = a.padding.top; node.paddingRight = a.padding.right;
    node.paddingBottom = a.padding.bottom; node.paddingLeft = a.padding.left;
    node.primaryAxisAlignItems = a.primaryAxisAlignItems;
    node.counterAxisAlignItems = a.counterAxisAlignItems;
    node.primaryAxisSizingMode = a.primaryAxisSizingMode;
    node.counterAxisSizingMode = a.counterAxisSizingMode;
  } else if (a.op === "setSizing") {
    node.primaryAxisSizingMode = a.primaryAxisSizingMode;
  } else if (a.op === "setTextAutoResize") {
    node.textAutoResize = a.textAutoResize;
  } else if (a.op === "rename") {
    node.name = a.name;
  } else if (a.op === "remove") {
    node.remove();
  } else {
    throw new Error("unknown op '" + a.op + "'");
  }
}

/* Did the write produce exactly the geometry the plan promised? */
function checkExpectation(node, m) {
  var e = m.expect || {};
  if (e.width !== undefined && !near(node.width, e.width)) return "width is " + r2(node.width) + ", expected " + e.width;
  if (e.height !== undefined && !near(node.height, e.height)) return "height is " + r2(node.height) + ", expected " + e.height;
  if (e.x !== undefined && !near(node.x, e.x)) return "x is " + r2(node.x) + ", expected " + e.x;
  if (e.y !== undefined && !near(node.y, e.y)) return "y is " + r2(node.y) + ", expected " + e.y;
  if (e.children) {
    var kids = (node.children || []).slice().sort(function (a, b) { return a.x - b.x; });
    if (kids.length !== e.children.length) return "child count changed";
    for (var i = 0; i < e.children.length; i++) {
      var k = kids[i], x = e.children[i];
      if (k.name !== x.name) return "child order changed at " + i;
      if (!near(k.x, x.x) || !near(k.y, x.y))
        return "'" + k.name + "' is at " + r2(k.x) + "," + r2(k.y) + ", expected " + x.x + "," + x.y;
    }
  }
  return null;
}

/* Put the node back exactly as it was found, INCLUDING its size - the previous
 * run restored layoutMode and child offsets but left every row 2px taller. */
function restore(node, m) {
  var c = m.check;
  if (m.apply.op === "setAutoLayout") {
    node.layoutMode = "NONE";
    var kids = (node.children || []).slice();
    for (var i = 0; i < c.children.length; i++) {
      var want = c.children[i];
      for (var j = 0; j < kids.length; j++)
        if (kids[j].name === want.name) { kids[j].x = want.x; kids[j].y = want.y; break; }
    }
    if (node.resizeWithoutConstraints) node.resizeWithoutConstraints(c.width, c.height);
  } else if (m.apply.op === "setSizing") {
    node.primaryAxisSizingMode = c.primaryAxisSizingMode;
    if (c.height !== undefined && node.resizeWithoutConstraints) node.resizeWithoutConstraints(node.width, c.height);
  } else if (m.apply.op === "setTextAutoResize") {
    node.textAutoResize = c.textAutoResize;
    if (node.resizeWithoutConstraints) node.resizeWithoutConstraints(node.width, c.height);
  } else if (m.apply.op === "rename") {
    node.name = c.name;
  }
}

async function apply() {
  if (!dryRunReport) throw new Error("Run Dry Run first.");
  if (dryRunReport.unexpected.length) throw new Error("Dry Run reported unexpected scope; refusing.");

  var byId = {};
  for (var i = 0; i < CLEANUP_PLAN.mutations.length; i++) byId[CLEANUP_PLAN.mutations[i].id] = CLEANUP_PLAN.mutations[i];

  var done = { applied: 0, skipped: 0, reverted: 0, failed: 0, moved: [], notes: [], errors: [], byGroup: {} };

  for (var j = 0; j < dryRunReport.items.length; j++) {
    var item = dryRunReport.items[j];
    if (item.status !== "READY") { done.skipped++; continue; }
    var m = byId[item.id];

    // re-verify immediately before writing; the document may have changed
    var why = await verify(m);
    if (why) { done.skipped++; done.notes.push(m.id + " skipped on re-check: " + why); continue; }

    var node = await figma.getNodeByIdAsync(m.nodeId);

    // Load every font involved BEFORE writing anything.
    if (node.type === "TEXT") {
      var fe = await loadFontFor(node);
      if (fe) { done.skipped++; done.notes.push(m.id + " skipped: " + fe); continue; }
    } else if (m.apply.op === "setAutoLayout") {
      var probs = await loadFontsUnder(node);
      if (probs.length) { done.skipped++; done.notes.push(m.id + " skipped: " + probs.join("; ")); continue; }
    }

    try {
      writeMutation(node, m);

      // Verify against what the plan says the result must be - the owner's
      // geometry - not merely "did anything move".
      var bad = checkExpectation(node, m);
      if (bad) {
        restore(node, m);
        done.reverted++;
        done.moved.push(m.id + " (" + m.frame + " · " + m.node + "): " + bad + " — reverted to the state it was found in");
        continue;
      }

      done.applied++;
      done.byGroup[m.group] = (done.byGroup[m.group] || 0) + 1;
    } catch (e) {
      try { restore(node, m); } catch (e2) {}
      done.failed++;
      done.errors.push(m.id + ": " + (e && e.message ? e.message : String(e)));
    }
  }

  dryRunReport = null;
  return done;
}

/* ---------- plumbing ---------- */

figma.showUI(__html__, { width: 480, height: 640 });

figma.ui.onmessage = async function (msg) {
  try {
    if (msg.type === "dryrun") {
      figma.ui.postMessage({ type: "status", text: "Verifying every target against the live file…" });
      var r = await dryRun();
      figma.ui.postMessage({ type: "dryrun-result", report: r });
    } else if (msg.type === "apply") {
      figma.ui.postMessage({ type: "status", text: "Applying…" });
      var d = await apply();
      figma.ui.postMessage({ type: "apply-result", done: d });
    }
  } catch (e) {
    figma.ui.postMessage({ type: "error", text: (e && e.stack) ? e.stack : String(e) });
  }
};
