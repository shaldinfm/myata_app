/*
 * The 3.6.6 proposal screens.
 *
 * Nothing in this file redraws a canonical screen. Where a flow changes an
 * existing screen (the Collection inline buttons), that is written up as a
 * repair mutation in README.md instead of being re-drawn here, because
 * re-drawing a canonical frame is how a copy silently becomes a fork.
 */
import { TYPE } from "./tokens.mjs";
import {
  SCREEN_W, MARGIN, CONTENT_W, APPBAR_H, NAV_H, F, T, V, E, A, icon,
  topAppBar, bottomNav, sheetShell, sheetRow, SHEET, menu, button, card,
  historyItem, HISTORY_ART, snackbar
} from "./primitives.mjs";

const S = [];
const add = (id, group, title, w, h, nodes, notes) => S.push({ id, group, title, w, h, nodes, notes });

/* ---------- shared small pieces ---------- */

const field = (label, value, o) => [
  T("Label / " + label, label, { x: MARGIN, y: o.y, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" }),
  F("Input / " + label, { x: MARGIN, y: o.y + 24, w: CONTENT_W, h: 56, r: 12, fill: "surface",
                          stroke: o.focus ? "primary" : "outline", sw: o.focus ? 2 : 1 }, [
    T("value", value, { x: 16, y: 17, w: CONTENT_W - 32, h: 22, ty: TYPE.fieldText,
                        fill: o.placeholder ? "textSecondary" : "textPrimary" })
  ])
];

/* Row track, left to right, with a fixed slot for every element:
 *
 *   16  leading icon 24   56  label ... status   306 | 12 gap | 318 chevron 24  342
 *
 * The chevron slot is reserved whether or not the row has one, so a status
 * string can never reach it. The status is right-aligned and ends at 306; the
 * label ends at 200 when a status is present and at the status edge otherwise.
 * Row height stays 64 (72 when a sub-line is used instead of a status). */
const ROW = { iconX: 16, labelX: 56, statusRight: 306, chevronX: 318, gap: 8 };

const listRow = (label, o = {}) => {
  const h = o.sub ? 72 : 64;
  const ch = [];
  const lx = o.ic || o.asset ? ROW.labelX : ROW.iconX;

  if (o.asset) ch.push(A(o.asset, { x: ROW.iconX, y: (h - 24) / 2, w: 24, h: 24,
                                    tint: o.danger ? "error" : "textSecondary" }));
  else if (o.ic) ch.push(icon(o.ic, { x: ROW.iconX, y: (h - 24) / 2, color: o.danger ? "error" : "textSecondary" }));

  // Reserve the trailing slot even when empty, so nothing can grow into it.
  const trailingEdge = (o.chevron || o.check) ? ROW.chevronX - ROW.gap : ROW.statusRight + ROW.gap;
  const statusW = 106;
  const labelRight = o.value ? ROW.statusRight - statusW - ROW.gap : trailingEdge;

  ch.push(T("label", label, { x: lx, y: o.sub ? 14 : 21, w: labelRight - lx, h: 22, ty: TYPE.sheetRow,
                              fill: o.danger ? "error" : "textPrimary" }));
  if (o.sub) ch.push(T("sub", o.sub, { x: lx, y: 38, w: trailingEdge - lx, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" }));
  if (o.value) ch.push(T("value", o.value, { x: ROW.statusRight - statusW, y: (h - 20) / 2, w: statusW, h: 20,
                                             ty: TYPE.bodySecondary, align: "RIGHT",
                                             fill: o.valueAccent ? "primary" : "textSecondary" }));
  if (o.chevron) ch.push(F("chevron", { x: ROW.chevronX, y: (h - 24) / 2, w: 24, h: 24 }, [
    V("chevron", { w: 24, h: 24, path: "M9 5 L16 12 L9 19", stroke: "textSecondary", sw: 1.8 })
  ]));
  if (o.check) ch.push(icon("check", { x: ROW.chevronX, y: (h - 24) / 2, color: "primary" }));
  return F("Row / " + label, { x: MARGIN, y: o.y, w: CONTENT_W, h, r: 12, fill: "surface",
                               stroke: o.selected ? "primary" : "outline", sw: o.selected ? 2 : 1 }, ch);
};

/* Real marks, referenced by node id out of the open file - see spec/assets.json.
 * Apple Music has no mark anywhere in the file, so its slot stays empty rather
 * than being filled with a drawn approximation. */
const SERVICES = [
  { label: "Spotify", asset: "logo/spotify" },
  { label: "Apple Music", asset: "logo/apple-music" },
  { label: "YouTube Music", asset: "logo/youtube-music" },
  { label: "Яндекс Музыка", asset: "logo/yandex-music" }
];

const sectionLabel = (text, y) =>
  T("Section / " + text, text, { x: MARGIN, y, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" });

const centeredState = (o) => {
  const nodes = [topAppBar(o.title, { back: true })];
  nodes.push(F("Illustration", { x: (SCREEN_W - 96) / 2, y: 168, w: 96, h: 96, r: 48, fill: "surfaceContainer" }, [
    icon(o.ic, { x: 36, y: 36, color: o.iconColor || "primary" })
  ]));
  nodes.push(T("headline", o.headline, { x: MARGIN, y: 296, w: CONTENT_W, h: 32, ty: TYPE.sheetTitle, align: "CENTER", fill: "textHeading" }));
  o.body.forEach((line, i) =>
    nodes.push(T("body" + i, line, { x: MARGIN, y: 340 + i * 22, w: CONTENT_W, h: 22, ty: TYPE.fieldText, align: "CENTER", fill: "textSecondary" })));
  nodes.push(button(o.action, { y: 340 + o.body.length * 22 + 28, x: MARGIN, kind: o.actionKind || "primary" }));
  return { nodes, h: 340 + o.body.length * 22 + 28 + 52 + 32 };
};

/* ================= 1. SLEEP TIMER ================= */
/* Entry point is the existing 'Menu row / Таймер сна' on the Player menu.
 * Presentation is the canonical bottom sheet, so the flow adds no new pattern. */

const TIMER_OPTIONS = ["15 минут", "30 минут", "45 минут", "60 минут"];
const rowY = (i) => SHEET.rowTop + 20 + i * SHEET.pitch;   // +20 leaves room for the subtitle

add("sleep-timer-select", "Таймер сна", "Таймер сна · выбор", SHEET.w, rowY(3) + SHEET.rowH + SHEET.padBottom,
  [sheetShell("Bottom Sheet / Таймер сна", "Таймер сна", rowY(3) + SHEET.rowH + SHEET.padBottom,
    TIMER_OPTIONS.map((label, i) => sheetRow(label, { y: rowY(i), ic: "clock", iconColor: "textSecondary" })),
    { subtitle: "Остановить воспроизведение через" })],
  "No option is preselected. Choosing one starts the timer and closes the sheet.");

{
  const dividerY = rowY(3) + SHEET.rowH + 12;
  const offY = dividerY + 13;
  const h = offY + SHEET.rowH + SHEET.padBottom;
  add("sleep-timer-active", "Таймер сна", "Таймер сна · активен", SHEET.w, h,
    [sheetShell("Bottom Sheet / Таймер сна активен", "Таймер сна", h,
      TIMER_OPTIONS.map((label, i) =>
        sheetRow(label, {
          y: rowY(i), ic: i === 1 ? "check" : "clock",
          iconColor: i === 1 ? "primary" : "textSecondary",
          trailing: i === 1 ? "осталось 24 мин" : null
        })
      ).concat([
        F("Divider", { x: MARGIN, y: dividerY, w: SHEET.w - 2 * MARGIN, h: 1, fill: "outline" }),
        sheetRow("Отключить таймер", { y: offY, ic: "timerOff", iconColor: "error", labelColor: "error" })
      ]),
      { subtitle: "Воспроизведение остановится в 23:47" })],
    "Reopening the sheet while a timer runs shows this state. The remaining time is " +
    "computed from the stored absolute end time, never from a counter that restarts.");
}

add("sleep-timer-menu-active", "Таймер сна", "Меню плеера · таймер активен", 260, 10 + 4 * 52 + 46,
  [menu("Menu / Плеер (таймер активен)", [
    { label: "Найти трек", ic: "disc" },
    { label: "Таймер сна", ic: "clock", trailing: "24 мин" },
    { label: "Сообщить о проблеме", ic: "alert" },
    { label: "История эфира", ic: "doc" }
  ], { w: 260 })],
  "The canonical menu is 206 wide, which cannot hold a label plus a trailing value. " +
  "Widened to 260. This is the only change proposed to the existing menu - both rows stay.");

add("sleep-timer-cancelled", "Таймер сна", "Таймер сна · отменён", CONTENT_W, 56,
  [snackbar("Snackbar / Таймер отключён", "Таймер сна отключён", { ic: "timerOff", iconColor: "textSecondary", action: "Вернуть" })],
  "Composited over the Player, 16px from the sides, above the mini player. Not a screen.");

add("sleep-timer-completed", "Таймер сна", "Таймер сна · сработал", CONTENT_W, 56,
  [snackbar("Snackbar / Таймер сработал", "Таймер сна завершён", { ic: "pause", action: "Продолжить" })],
  "Shown once when the timer fires and playback stops. 'Продолжить' resumes; nothing " +
  "resumes on its own, and nothing resumes after a reboot.");

/* ================= 2. REPORT A PROBLEM ================= */

const CATEGORIES = [
  { label: "Музыка не запускается", ic: "playNote" },
  { label: "Музыка остановилась сама", ic: "pause" },
  { label: "Проблема с наушниками", ic: "headphone" },
  { label: "Проблема с интерфейсом", ic: "layout" },
  { label: "Другое", ic: "question" }
];

const DIAG = [
  "Версия приложения — 3.6.6 (202611)",
  "Устройство — Xiaomi Redmi Note 12",
  "Android — 14 (API 34)",
  "Сеть в момент сбоя — Wi-Fi",
  "Последняя ошибка — HTTP 403, 2 мин назад",
  "Поток — Мята FM, 128 kbps"
];

function reportForm(state) {
  const selected = state === "empty" ? -1 : 1;
  const nodes = [topAppBar("Сообщить о проблеме", { back: true })];
  nodes.push(T("prompt", "Что случилось?", { x: MARGIN, y: 80, w: CONTENT_W, h: 22, ty: TYPE.sheetRow, fill: "textHeading" }));

  CATEGORIES.forEach((c, i) =>
    nodes.push(listRow(c.label, { y: 110 + i * 64, ic: c.ic, selected: i === selected, check: i === selected })));

  nodes.push(T("descLabel", "Описание (необязательно)", { x: MARGIN, y: 446, w: CONTENT_W, h: 22, ty: TYPE.sheetRow, fill: "textHeading" }));
  const desc = state === "empty"
    ? { text: ["Опишите, что произошло"], placeholder: true }
    : { text: ["Играло минут двадцать, потом звук пропал,", "кнопка стала «play» сама."], placeholder: false };
  nodes.push(F("Input / Описание", { x: MARGIN, y: 476, w: CONTENT_W, h: 120, r: 12, fill: "surface", stroke: "outline" },
    desc.text.map((line, i) =>
      T("line" + i, line, { x: 16, y: 16 + i * 22, w: CONTENT_W - 32, h: 22, ty: TYPE.fieldText,
                            fill: desc.placeholder ? "textSecondary" : "textPrimary" }))));

  nodes.push(card("Diagnostics", { y: 612, h: 238 }, [
    T("title", "Что будет отправлено", { x: 16, y: 16, w: 300, h: 22, ty: TYPE.sheetRow, fill: "textHeading" }),
    ...DIAG.map((line, i) => T("d" + i, line, { x: 16, y: 50 + i * 24, w: 326, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })),
    F("NoticeRule", { x: 16, y: 194, w: 326, h: 1, fill: "outline" }),
    T("notice", "Личные данные не отправляются.", { x: 16, y: 204, w: 326, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })
  ]));

  let y = 874;
  if (state === "error") {
    nodes.push(F("Banner / error", { x: MARGIN, y, w: CONTENT_W, h: 56, r: 12, fill: "surface", stroke: "error" }, [
      icon("alert", { x: 16, y: 16, color: "error" }),
      T("msg", "Не удалось отправить сообщение.", { x: 52, y: 18, w: 290, h: 20, ty: TYPE.bodySecondary, fill: "error" })
    ]));
    y += 72;
  }
  const btn = { empty: ["Отправить", "disabled"], filled: ["Отправить", "primary"],
                sending: ["Отправляем…", "disabled"], error: ["Отправить ещё раз", "primary"] }[state];
  nodes.push(button(btn[0], { x: MARGIN, y, kind: btn[1] }));
  return { nodes, h: y + 52 + 24 };
}

for (const [state, title, note] of [
  ["empty", "Сообщить о проблеме · пустая форма", "Nothing is preselected and Send is disabled until a category is chosen."],
  ["filled", "Сообщить о проблеме · заполнено", "A category is chosen and the description is optional."],
  ["sending", "Сообщить о проблеме · отправка", "The form stays visible and editable-looking but the button is inert."],
  ["error", "Сообщить о проблеме · ошибка", "The chosen category and the typed description are preserved. Nothing is cleared on failure."]
]) {
  const b = reportForm(state);
  add("report-" + state, "Сообщить о проблеме", title, SCREEN_W, b.h, b.nodes, note);
}

add("report-success", "Сообщить о проблеме", "Сообщить о проблеме · отправлено", SCREEN_W, 456, [
  topAppBar("Сообщить о проблеме", { back: true }),
  card("Success", { y: 120, h: 236 }, [
    F("Badge", { x: 147, y: 32, w: 64, h: 64, r: 32, fill: "primary" }, [icon("check", { x: 20, y: 20, color: "onPrimary" })]),
    T("headline", "Спасибо!", { x: 0, y: 116, w: CONTENT_W, h: 32, ty: TYPE.sheetTitle, align: "CENTER", fill: "textHeading" }),
    T("b0", "Сообщение отправлено. Если понадобится,", { x: 0, y: 160, w: CONTENT_W, h: 22, ty: TYPE.fieldText, align: "CENTER", fill: "textSecondary" }),
    T("b1", "мы ответим в Telegram.", { x: 0, y: 182, w: CONTENT_W, h: 22, ty: TYPE.fieldText, align: "CENTER", fill: "textSecondary" })
  ]),
  button("Готово", { x: MARGIN, y: 380 })
], "The app posts to our own endpoint. The Telegram bot token lives on that endpoint and never ships in the APK.");

/* ================= 3. FULL BROADCAST HISTORY ================= */

const HISTORY = [
  { time: "10:45", title: "CRYOGEN", artist: "MUSE" },
  { time: "10:41", title: "MEET ME IN LOVE", artist: "BLOSSOMS" },
  { time: "10:36", title: "CITY WALLS", artist: "TWENTY ONE PILOTS" },
  { time: "10:31", title: "WHAT YOU KNOW", artist: "TWO DOOR CINEMA CLUB" },
  { time: "10:27", title: "SUNLIT ROOM", artist: "GLASS ANIMALS" },
  { time: "10:22", title: "NORTHERN LINE", artist: "FOALS" },
  { time: "10:18", title: "PAPER BOATS", artist: "ALT-J" },
  { time: "10:13", title: "SLOW BURN", artist: "KAYTRANADA" }
];
const HIST_PITCH = 82;

{
  const last = 80 + (HISTORY.length - 1) * HIST_PITCH + 74;
  add("history-content", "История эфира", "История эфира · список", SCREEN_W, last + 24 + 20 + 24, [
    topAppBar("История эфира", { back: true }),
    ...HISTORY.map((h, i) => historyItem({ ...h, y: 80 + i * HIST_PITCH })),
    T("footer", "Показаны последние 30 треков", { x: MARGIN, y: last + 24, w: CONTENT_W, h: 20,
                                                  ty: TYPE.bodySecondary, align: "CENTER", fill: "textSecondary" })
  ], "Same row as the Player's Broadcast History, promoted to full content width because " +
     "there is no outer card here. Capped at 30 entries; older ones are dropped, not paged.");
}

{
  const n = 8, last = 80 + (n - 1) * HIST_PITCH + 74;
  add("history-loading", "История эфира", "История эфира · загрузка", SCREEN_W, last + 24, [
    topAppBar("История эфира", { back: true }),
    ...Array.from({ length: n }, (_, i) =>
      F("Skeleton " + (i + 1), { x: MARGIN, y: 80 + i * HIST_PITCH, w: CONTENT_W, h: 74, r: 8, stroke: "outline" }, [
        F("s-art", { ...HISTORY_ART, fill: "outline", opacity: 0.5 }),
        F("s-title", { x: 116, y: 18, w: 150, h: 16, r: 8, fill: "outline", opacity: 0.5 }),
        F("s-artist", { x: 116, y: 44, w: 96, h: 12, r: 6, fill: "outline", opacity: 0.35 })
      ]))
  ], "The skeleton is the square album-art thumbnail at the real geometry, not a bar. " +
     "Row height, art position and text baselines match the loaded row exactly, so nothing shifts.");
}

{
  const b = centeredState({
    title: "История эфира", ic: "doc", headline: "Пока нет истории",
    body: ["Треки появятся здесь, как только", "начнётся эфир."], action: "Обновить", actionKind: "outline"
  });
  add("history-empty", "История эфира", "История эфира · пусто", SCREEN_W, b.h, b.nodes,
    "Shown when the endpoint answers with an empty list - distinct from the error state below.");
}

/* The find action on a history row opens this sheet - the same four services as
 * Collection, without the destructive row, since there is nothing saved to delete.
 * It is the canonical 'Bottom Sheet / Найти трек' with the generic disc icons
 * replaced by the real marks; the canonical frame itself is not touched. */
add("find-track-sheet", "История эфира", "Найти трек · общий шит", SHEET.w,
  rowY(3) + SHEET.rowH + SHEET.padBottom,
  [sheetShell("Bottom Sheet / Найти трек", "CRYOGEN", rowY(3) + SHEET.rowH + SHEET.padBottom,
    SERVICES.map((s, i) => sheetRow(s.label, { y: rowY(i), asset: s.asset })),
    { subtitle: "MUSE" })],
  "One find-track pattern shared by Broadcast History, the player menu and Collection. " +
  "Collection adds a divider and a destructive row below these four; nothing else differs.");

{
  const b = centeredState({
    title: "История эфира", ic: "alert", iconColor: "error", headline: "Не удалось загрузить",
    body: ["Проверьте подключение", "и попробуйте ещё раз."], action: "Повторить"
  });
  add("history-error", "История эфира", "История эфира · ошибка", SCREEN_W, b.h, b.nodes,
    "Shown on a network or HTTP failure. Retry re-requests; it does not clear a cached list.");
}

/* ================= 4. COLLECTION UX ================= */

{
  const dividerY = rowY(3) + SHEET.rowH + 12;
  const delY = dividerY + 13;
  const h = delY + SHEET.rowH + SHEET.padBottom;
  add("collection-track-sheet", "Коллекция", "Коллекция · действия с треком", SHEET.w, h,
    [sheetShell("Bottom Sheet / Действия с треком", "CRYOGEN", h,
      SERVICES.map((s, i) => sheetRow(s.label, { y: rowY(i), asset: s.asset }))
        .concat([
          F("Divider", { x: MARGIN, y: dividerY, w: SHEET.w - 2 * MARGIN, h: 1, fill: "outline" }),
          sheetRow("Удалить из коллекции", { y: delY, ic: "trash", iconColor: "error", labelColor: "error" })
        ]),
      { subtitle: "MUSE" })],
    "Replaces the per-track inline button. Four services, a divider, then the destructive " +
    "action last and separated - the same shape as the canonical 'Найти трек' sheet it extends.");
}

add("collection-overflow-menu", "Коллекция", "Коллекция · меню экспорта", 260, 10 + 2 * 52 + 46,
  [menu("Menu / Коллекция", [
    { label: "Экспорт в TXT", ic: "doc" },
    { label: "Экспорт в CSV", ic: "doc" }
  ], { w: 260 })],
  "Opens from the TopAppBar overflow. Replaces the permanent 'Экспортировать список' button " +
  "in the header, which costs 88px of vertical space on every visit for an action used rarely.");

/* ================= 5. ACCOUNT / SETTINGS (revised from PR #23) ================= */

{
  const nodes = [topAppBar("Вход", { back: true }),
    T("b0", "Войдите, чтобы коллекция синхронизировалась", { x: MARGIN, y: 92, w: CONTENT_W, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    T("b1", "между устройствами.", { x: MARGIN, y: 114, w: CONTENT_W, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    ...field("Email", "denis@example.com", { y: 156 }),
    ...field("Пароль", "••••••••••", { y: 252 }),
    T("forgot", "Забыли пароль?", { x: MARGIN, y: 344, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, align: "RIGHT", fill: "primary" }),
    button("Войти", { x: MARGIN, y: 388 }),
    button("Создать аккаунт", { x: MARGIN, y: 456, kind: "outline" }),
    T("guest", "Продолжить без аккаунта", { x: MARGIN, y: 532, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, align: "CENTER", fill: "primary" })
  ];
  add("auth-sign-in", "Аккаунт", "Вход", SCREEN_W, 576, nodes,
    "Revised from PR #23: same flow, canonical colours and type. Sign-in is optional " +
    "everywhere - the app must stay fully usable without an account.");
}

add("auth-create-account", "Аккаунт", "Создание аккаунта", SCREEN_W, 596, [
  topAppBar("Создать аккаунт", { back: true }),
  ...field("Имя", "Денис", { y: 92 }),
  ...field("Email", "denis@example.com", { y: 188 }),
  ...field("Пароль", "Придумайте пароль", { y: 284, placeholder: true, focus: true }),
  T("hint", "Минимум 8 символов", { x: MARGIN, y: 372, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" }),
  button("Создать аккаунт", { x: MARGIN, y: 412 }),
  T("have", "Уже есть аккаунт? Войти", { x: MARGIN, y: 488, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, align: "CENTER", fill: "primary" })
], "Focused field uses a 2px primary border - the only focus treatment in the system, since no canonical screen shows one.");

/* Card padding: 32 top, 32 bottom, 24 between the avatar and the headline and
 * 12 between headline and body - was 28/18/18/6, which read as cramped. */
add("profile-guest", "Аккаунт", "Профиль · гость", SCREEN_W, 700, [
  topAppBar("Профиль", { back: true }),
  card("Guest card", { y: 96, h: 226 }, [
    F("Avatar", { x: 143, y: 32, w: 72, h: 72, r: 36, fill: "surfaceContainer" }, [icon("person", { x: 24, y: 24, color: "textSecondary" })]),
    T("h", "Вы не вошли", { x: 0, y: 128, w: CONTENT_W, h: 32, ty: TYPE.sheetTitle, align: "CENTER", fill: "textHeading" }),
    T("b", "Коллекция хранится только на этом устройстве.", { x: 0, y: 172, w: CONTENT_W, h: 22, ty: TYPE.fieldText, align: "CENTER", fill: "textSecondary" })
  ]),
  button("Войти", { x: MARGIN, y: 346 }),
  button("Создать аккаунт", { x: MARGIN, y: 414, kind: "outline" }),
  sectionLabel("Что даёт аккаунт", 494),
  listRow("Синхронизация коллекции", { y: 522, ic: "sync" }),
  listRow("Восстановление коллекции", { y: 594, ic: "download" })
], "Guest is a first-class state, not a degraded one. Nothing on this screen blocks playback.");

add("profile-authenticated", "Аккаунт", "Профиль · вошли", SCREEN_W, 620, [
  topAppBar("Профиль", { back: true }),
  card("Account card", { y: 96, h: 104 }, [
    F("Avatar", { x: 16, y: 20, w: 64, h: 64, r: 32, fill: "primary" }, [
      T("initial", "Д", { x: 0, y: 18, w: 64, h: 28, ty: TYPE.sheetTitle, align: "CENTER", fill: "onPrimary" })
    ]),
    T("name", "Денис", { x: 96, y: 30, w: 200, h: 28, ty: TYPE.historyTitle, fill: "textPrimary" }),
    T("email", "denis@example.com", { x: 96, y: 58, w: 240, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })
  ]),
  sectionLabel("Синхронизация", 224),
  listRow("Облачная синхронизация", { y: 252, ic: "sync", sub: "Включена" }),
  listRow("Последняя синхронизация", { y: 332, ic: "clock", sub: "2 мин назад" }),
  sectionLabel("Аккаунт", 428),
  listRow("Сменить пароль", { y: 456, ic: "doc", chevron: true }),
  listRow("Выйти", { y: 528, ic: "logout", danger: true })
], "Sign-out is destructive-styled but does not delete the local collection - that is stated in the confirm dialog, not here.");

add("settings", "Настройки", "Настройки", SCREEN_W, 792, [
  topAppBar("Настройки", { back: true }),
  sectionLabel("Аккаунт", 84),
  listRow("Профиль", { y: 112, ic: "person", value: "Не вошли", chevron: true }),
  sectionLabel("Внешний вид", 196),
  listRow("Тема", { y: 224, ic: "theme", value: "Системная", chevron: true }),
  sectionLabel("Воспроизведение", 308),
  listRow("Качество потока", { y: 336, ic: "equalizer", value: "Авто", chevron: true }),
  listRow("Таймер сна", { y: 408, ic: "clock", value: "Выключен", chevron: true }),
  sectionLabel("Интеграции", 492),
  listRow("Last.fm", { y: 520, asset: "logo/lastfm", value: "Не подключён", chevron: true }),
  sectionLabel("Прочее", 604),
  listRow("Сообщить о проблеме", { y: 632, ic: "message", chevron: true }),
  listRow("О приложении", { y: 704, ic: "info", value: "3.6.6", chevron: true })
], "Revised from PR #23: the same groups, but Sleep Timer is surfaced here as well as in " +
   "the player menu, because a timer you can only reach mid-playback is hard to find.");

add("settings-appearance", "Настройки", "Настройки · тема", SCREEN_W, 420, [
  topAppBar("Тема", { back: true }),
  listRow("Системная", { y: 92, ic: "layout", sub: "Как в настройках устройства", selected: true, check: true }),
  listRow("Светлая", { y: 172, ic: "layout" }),
  listRow("Тёмная", { y: 244, ic: "layout" }),
  T("note", "Тема применяется сразу, без перезапуска.", { x: MARGIN, y: 336, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })
], "Three options only. PR #23 proposed a separate AMOLED variant; dropped, because the " +
   "canonical dark background is #0f253e and a true-black variant would be a second dark theme to maintain.");

add("settings-sync", "Настройки", "Настройки · синхронизация", SCREEN_W, 676, [
  topAppBar("Синхронизация", { back: true }),
  card("Status", { y: 92, h: 116 }, [
    F("Badge", { x: 16, y: 26, w: 64, h: 64, r: 32, fill: "surfaceContainer" }, [icon("check", { x: 20, y: 20, color: "primary" })]),
    T("s", "Синхронизировано", { x: 96, y: 34, w: 240, h: 28, ty: TYPE.historyTitle, fill: "textPrimary" }),
    T("t", "2 мин назад · 48 треков", { x: 96, y: 62, w: 240, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })
  ]),
  sectionLabel("Остальные состояния этой карточки", 232),
  listRow("Синхронизация…", { y: 260, ic: "sync", sub: "24 из 48 треков" }),
  listRow("Не удалось синхронизировать", { y: 340, ic: "alert", sub: "Нажмите, чтобы повторить" }),
  listRow("Синхронизация выключена", { y: 420, ic: "timerOff", sub: "Включите её в профиле" }),
  sectionLabel("Управление", 512),
  listRow("Синхронизировать сейчас", { y: 540, ic: "sync" }),
  listRow("Удалить копию в облаке", { y: 612, ic: "trash", danger: true })
], "The three rows under 'Состояния' are the alternate states of the card above, drawn " +
   "inline so all four can be reviewed on one frame. In the app only one is visible at a time.");

/* Card padding is 16 on every edge, so the button bottom sits at h-16 rather
 * than the 4px it had before. Cards are 16 + 28 + 6 + 44 + 22 + 12 + 44 + 16. */
add("settings-lastfm", "Настройки", "Настройки · Last.fm", SCREEN_W, 570, [
  topAppBar("Last.fm", { back: true }),
  card("Not connected", { y: 92, h: 172 }, [
    A("logo/lastfm", { x: 16, y: 18, w: 24, h: 24, tint: "textSecondary" }),
    T("h", "Не подключён", { x: 48, y: 16, w: 240, h: 28, ty: TYPE.historyTitle, fill: "textPrimary" }),
    T("b0", "Подключите Last.fm, чтобы отмечать", { x: 16, y: 56, w: 326, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    T("b1", "прослушанные треки.", { x: 16, y: 78, w: 326, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    button("Подключить", { x: 16, y: 112, w: 326, h: 44, name: "connect" })
  ]),
  card("Connected", { y: 288, h: 172 }, [
    A("logo/lastfm", { x: 16, y: 18, w: 24, h: 24, tint: "primary" }),
    T("h", "Подключён", { x: 48, y: 16, w: 240, h: 28, ty: TYPE.historyTitle, fill: "textPrimary" }),
    icon("check", { x: 318, y: 18, color: "primary" }),
    T("b0", "denis_fm · 1 248 скробблов", { x: 16, y: 56, w: 326, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    T("b1", "Последний: CRYOGEN — MUSE", { x: 16, y: 78, w: 326, h: 22, ty: TYPE.fieldText, fill: "textSecondary" }),
    button("Отключить", { x: 16, y: 112, w: 326, h: 44, kind: "outline", name: "disconnect" })
  ]),
  T("note0", "Пароль Last.fm не хранится в приложении:", { x: MARGIN, y: 488, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" }),
  T("note1", "используется веб-авторизация.", { x: MARGIN, y: 510, w: CONTENT_W, h: 20, ty: TYPE.bodySecondary, fill: "textSecondary" })
], "Both connection states on one frame for review. Authorisation goes through Last.fm's " +
   "own web flow, so no credentials are entered in the app.");

export const SCREENS = S;
