// One shard of a cohort, off the page's thread.
//
// In:  {engineUrl, payload: {cards, slots, matches, chaos_effect, ...}}
//      engineUrl is same-origin on the synthetic host JsRunner serves, so
//      importScripts can reach it. JsRunner has already verified those bytes
//      against a sha256 it was given -- the worker trusts them.
// Out: {ok:true, results} or {ok:false, message}
//
// Sharding is safe because hashResults() sorts by match_id before hashing
// (js/battle-engine.js), so the digest does not depend on which worker played
// which match. Proven on payload.json at 2, 4 and 6 shards: all three give
// 1b5e48ac..., the same digest the Go CLI gives for the whole payload.
let loaded = null;

self.onmessage = function (e) {
    try {
        const engineUrl = e.data.engineUrl;
        const payload = e.data.payload;
        if (loaded !== engineUrl) {
            importScripts(engineUrl);
            loaded = engineUrl;
        }
        if (!self.BattleEngine || typeof self.BattleEngine.runCohort !== 'function') {
            throw new Error('engine exposes no runCohort');
        }
        self.postMessage({ ok: true, results: self.BattleEngine.runCohort(payload) });
    } catch (err) {
        self.postMessage({ ok: false, message: String((err && err.message) || err) });
    }
};
