/**
 * "Сообщить о проблеме" endpoint — Google Apps Script Web App (G3).
 *
 * FOR OWNER REVIEW. Not deployed by this PR, and deploying it is a separate,
 * explicitly authorised step. Nothing in this file is a secret and nothing in it
 * may become one: the bot token is read from Script Properties at run time and
 * must never be typed into this source, into the repository, or into a commit
 * message.
 *
 * ======================================================================
 * WHAT IT IS
 * ======================================================================
 *
 *   Android app  ->  THIS Web App  ->  Telegram Bot API  ->  a chat you own
 *
 * The app holds only this deployment's URL, which is a capability URL: it ships
 * in the APK and anyone who downloads the app can extract it. That is acceptable
 * for a write-only report intake and is exactly the shape the existing reactions
 * endpoint (FeedbackRepository) already has. It would NOT be acceptable for the
 * bot token, which is why the token lives here and never there — a token in an
 * APK lets a stranger post as the bot and read its updates.
 *
 * **A separate deployment from the reactions one.** Reports carry free text and
 * device diagnostics; reactions carry a track name. They must not share an
 * endpoint, a sheet or a chat.
 *
 * ======================================================================
 * THE CONTRACT THE ANDROID CLIENT DEPENDS ON
 * ======================================================================
 *
 * Request:  POST, application/x-www-form-urlencoded; charset=UTF-8
 *           Exactly eight fields, all strings, all always present:
 *
 *             category      one of: playback_wont_start | stopped_by_itself |
 *                           headphones | ui | other
 *             message       the listener's own words; may be empty; <= 2000 chars
 *             app_version   e.g. "3.6.5 (202611)"
 *             device        e.g. "Xiaomi Redmi Note 12"
 *             android       e.g. "14 (API 34)"
 *             network       one of: Wi-Fi | Мобильная сеть | Другая сеть | Нет сети
 *             last_error    a Media3 constant name plus how long ago, or "нет"
 *             stream        MYATA | GOLD | XTRA
 *
 * Response: 200 with a JSON body.
 *
 *           {"ok":true}                      -> the app shows report-success
 *           {"ok":false,"error":"<reason>"}  -> the app shows report-error
 *
 *   **`ok:true` MUST mean the Telegram send itself succeeded.** The client matches
 *   the VALUE - `"ok"\s*:\s*true` - and treats everything else as a failure,
 *   including every `{"ok":false}` this file can return. See ReportAck on the
 *   Android side; matching the key alone made every failure here read as a
 *   success, which is the bug that check exists to prevent.
 *
 *   It matters more than it looks: Apps Script answers 200 to almost anything,
 *   including its own uncaught exceptions, so without it the listener would be
 *   thanked for a message nobody received — and, because the success screen is
 *   terminal, they would have no way to send it again.
 *
 *   Every failure path below therefore ANSWERS `{"ok":false,"error":...}` rather
 *   than throwing, so the client always has something unambiguous to read.
 *
 *   A non-2xx would work too, but Apps Script cannot reliably produce one, so the
 *   body is the channel.
 *
 * ======================================================================
 * SETUP (owner, once — do NOT do any of this from the repository)
 * ======================================================================
 *
 *  1. Create the bot with @BotFather and keep the token out of every file.
 *  2. Create a private group or channel for reports and add the bot to it.
 *     Get its numeric chat id (e.g. via @getidsbot, or getUpdates once).
 *  3. script.google.com -> New project -> paste this file.
 *  4. Project Settings -> Script Properties, add:
 *         TELEGRAM_BOT_TOKEN   <the token from BotFather>
 *         TELEGRAM_CHAT_ID     <the numeric chat id, e.g. -1001234567890>
 *         SHARED_SECRET        (optional; see below)
 *  5. Deploy -> New deployment -> Web app
 *         Execute as:        Me
 *         Who has access:    Anyone
 *  6. Copy the /exec URL into report.properties as REPORT_ENDPOINT.
 *     That file is untracked. Never commit it and never paste the URL into an
 *     issue, a commit message or a screenshot.
 *
 * Rotating the token later is a Script Properties edit and needs no app release.
 * That is the main reason the token is here rather than anywhere nearer the app.
 *
 * ======================================================================
 * ABUSE
 * ======================================================================
 *
 * The endpoint is unauthenticated by design — the frozen flow works with no
 * account, and requiring one would exclude exactly the listeners most likely to
 * be reporting a problem. So spam control belongs here, not in the APK:
 *
 *   - a per-execution size cap, below;
 *   - a coarse global rate limit via CacheService, below;
 *   - Apps Script's own daily UrlFetch quota as a hard ceiling.
 *
 * SHARED_SECRET is deliberately NOT implemented as a client-sent header: a secret
 * shipped in an APK is not a secret, and pretending otherwise would be worse than
 * having none. It is reserved for a future signed-request scheme.
 */

var MAX_MESSAGE = 2000;
var MAX_FIELD = 200;

var CATEGORIES = {
  playback_wont_start: 'Музыка не запускается',
  stopped_by_itself: 'Музыка остановилась сама',
  headphones: 'Проблема с наушниками',
  ui: 'Проблема с интерфейсом',
  other: 'Другое'
};

function doPost(e) {
  try {
    if (!e || !e.parameter) return fail('no_body');

    if (isRateLimited()) return fail('rate_limited');

    var category = String(e.parameter.category || '');
    if (!CATEGORIES.hasOwnProperty(category)) return fail('bad_category');

    var report = {
      category: category,
      message: clamp(e.parameter.message, MAX_MESSAGE),
      app_version: clamp(e.parameter.app_version, MAX_FIELD),
      device: clamp(e.parameter.device, MAX_FIELD),
      android: clamp(e.parameter.android, MAX_FIELD),
      network: clamp(e.parameter.network, MAX_FIELD),
      last_error: clamp(e.parameter.last_error, MAX_FIELD),
      stream: clamp(e.parameter.stream, MAX_FIELD)
    };

    // The whole point of the endpoint: this must throw or return false rather
    // than let a failure be reported to the listener as a success.
    sendToTelegram(format(report));

    return ok();
  } catch (err) {
    // The reason is for the log, not for the listener - the app draws one frozen
    // sentence for every failure. Never echo the request back in an error.
    console.error('report failed: ' + err);
    return fail('send_failed');
  }
}

/** A GET is a health check, so the deployment can be verified without posting. */
function doGet() {
  return json({ ok: true, service: 'myata-report', version: 1 });
}

function sendToTelegram(text) {
  var props = PropertiesService.getScriptProperties();
  var token = props.getProperty('TELEGRAM_BOT_TOKEN');
  var chatId = props.getProperty('TELEGRAM_CHAT_ID');
  if (!token || !chatId) throw new Error('endpoint is not configured');

  var response = UrlFetchApp.fetch(
    'https://api.telegram.org/bot' + token + '/sendMessage',
    {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify({
        chat_id: chatId,
        text: text,
        parse_mode: 'HTML',
        disable_web_page_preview: true
      }),
      muteHttpExceptions: true
    }
  );

  var code = response.getResponseCode();
  var body = response.getContentText();
  if (code < 200 || code >= 300) {
    // Never let the token reach a log line. The URL contains it, so only the
    // status and Telegram's own description are recorded.
    throw new Error('telegram http ' + code + ': ' + clamp(body, 300));
  }
  var parsed = JSON.parse(body);
  if (!parsed.ok) throw new Error('telegram rejected: ' + clamp(parsed.description, 300));
}

function format(r) {
  return [
    '<b>' + escapeHtml(CATEGORIES[r.category]) + '</b>',
    r.message ? '' : null,
    r.message ? escapeHtml(r.message) : null,
    '',
    '<code>' + escapeHtml(r.app_version) + ' · ' + escapeHtml(r.device) + '</code>',
    '<code>Android ' + escapeHtml(r.android) + ' · ' + escapeHtml(r.network) + '</code>',
    '<code>' + escapeHtml(r.stream) + ' · ' + escapeHtml(r.last_error) + '</code>'
  ].filter(function (line) { return line !== null; }).join('\n');
}

/**
 * A coarse ceiling, not a per-listener limit.
 *
 * There is no identity to key on and there must not be one - the request carries
 * nothing that identifies a device, by design. So this caps total intake per
 * minute, which is enough to stop a script pointed at the URL from filling the
 * chat, and is not enough to stop a determined one. If that ever happens the
 * answer is to rotate the deployment URL, which is a one-line change to
 * report.properties and one app release.
 */
function isRateLimited() {
  var cache = CacheService.getScriptCache();
  var key = 'rate:' + Math.floor(Date.now() / 60000);
  var count = Number(cache.get(key) || 0) + 1;
  cache.put(key, String(count), 120);
  return count > 60;
}

function clamp(value, max) {
  var s = value == null ? '' : String(value);
  return s.length > max ? s.substring(0, max) : s;
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function ok() {
  return json({ ok: true });
}

function fail(reason) {
  return json({ ok: false, error: reason });
}

function json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
