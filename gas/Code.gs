/**
 * A22 Camera Link - temporary signaling only.
 * Media data is never stored or relayed by this script.
 * Deploy as a Web App: Execute as Me / Anyone.
 */
const SIGNAL_TTL_SECONDS = 1800;
const MAX_POLL_MESSAGES = 100;

function doGet(e) {
  const params = (e && e.parameter) || {};
  if (params.action !== "poll") return output_({ok:true, service:"A22 Camera Link signaling", mediaRelay:false});
  const room = validRoom_(params.room);
  const ownSide = params.from === "android" ? "android" : "ipad";
  const after = Math.max(0, Number(params.after || 0));
  if (!room || !Number.isFinite(after)) return output_({error:"invalid_request"});

  const cache = CacheService.getScriptCache();
  const current = Number(cache.get("r_" + room + "_seq") || 0);
  const start = Math.max(1, Math.floor(after) + 1);
  const end = Math.min(current, start + MAX_POLL_MESSAGES - 1);
  const messages = [];
  for (let id = start; id <= end; id++) {
    const raw = cache.get("r_" + room + "_m_" + id);
    if (!raw) continue;
    try {
      const message = JSON.parse(raw);
      if (message.from !== ownSide) messages.push(message);
    } catch (error) {
      // Ignore a malformed/expired cache item.
    }
  }
  const next = Math.max(Math.floor(after), end);
  const result = {ok:true, messages:messages, next:next};
  const callback = String(params.callback || "");
  if (callback) {
    if (!/^[A-Za-z_$][A-Za-z0-9_$]{0,50}$/.test(callback)) return output_({error:"invalid_callback"});
    return ContentService.createTextOutput(callback + "(" + JSON.stringify(result) + ");")
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return output_(result);
}

function doPost(e) {
  try {
    const raw = e && e.postData && e.postData.contents ? e.postData.contents : "";
    const body = JSON.parse(raw);
    const room = validRoom_(body.room);
    const from = body.from === "android" ? "android" : (body.from === "ipad" ? "ipad" : "");
    const allowed = ["offer", "answer", "candidate", "iceconfig", "hangup"];
    if (!room || !from || allowed.indexOf(body.type) < 0) return output_({error:"invalid_request"});

    const lock = LockService.getScriptLock();
    lock.waitLock(10000);
    try {
      const cache = CacheService.getScriptCache();
      const seqKey = "r_" + room + "_seq";
      const next = Number(cache.get(seqKey) || 0) + 1;
      const message = {
        id: next,
        room: room,
        from: from,
        type: body.type,
        data: body.data === undefined ? null : body.data,
        createdAt: Date.now()
      };
      cache.put("r_" + room + "_m_" + next, JSON.stringify(message), SIGNAL_TTL_SECONDS);
      cache.put(seqKey, String(next), SIGNAL_TTL_SECONDS);
    } finally {
      lock.releaseLock();
    }
    return output_({ok:true});
  } catch (error) {
    return output_({error:String(error && error.message ? error.message : error)});
  }
}

function validRoom_(value) {
  const room = String(value || "");
  return /^[0-9]{6}$/.test(room) ? room : "";
}

function output_(value) {
  return ContentService.createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}