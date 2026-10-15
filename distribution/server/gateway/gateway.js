// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * The go-ahead of the data gateway (GP-336, ADR-0022), for nginx's auth_request: may this session, or
 * this personal key, read the geodata of the organization in the address?
 *
 * Geoportal answers it, through the catalog, as the person: with the session cookie and the
 * organization, or with the key, so that it refuses an ended session, a revoked key, a guest or another
 * organization, and counts the requests on the quota of the organization. The answer is kept ten seconds
 * per credential and organization (the zone gw_decisions); in between the requests let through are
 * counted here (gw_served) and the next go-ahead brings them to Geoportal.
 *
 * It answers 204 with the name of the person for GeoServer (X-Geoportal-User, only for a session: a key is
 * checked again by the AuthKey filter of GeoServer), the workspace and the organization; 401 without a
 * credential that works; 403 with the reason.
 */

const crypto = require('crypto');

const decisions = ngx.shared.gw_decisions;
const served = ngx.shared.gw_served;

/** Text that can go into the JSON of the refusal and into a header: no quotes, controls or backslashes. */
function plain(text) {
  return String(text || '')
    .replace(/[\u0000-\u001f"\\]/g, ' ')
    .slice(0, 300);
}

async function ask(r, v, key, session, pending) {
  const headers = {
    'X-Gateway-Organization': v.gw_org,
    'X-Gateway-Workspace': v.gw_ws || '',
    'X-Gateway-Served': String(pending),
    Accept: 'application/json',
  };
  if (key) {
    // A personal key acts on its own organization.
    headers.Authorization = 'Bearer ' + key;
  } else {
    headers.Cookie = 'mosaikit-session=' + session;
    headers['X-Mosaikit-Organization'] = v.gw_org;
  }
  let reply;
  try {
    reply = await ngx.fetch(v.gw_check_url, { method: 'GET', headers: headers });
  } catch (e) {
    r.error('gateway: Geoportal does not answer the go-ahead: ' + e);
    return { status: 503 };
  }
  if (reply.status === 200) {
    const body = await reply.json();
    return { status: 204, user: body.user, workspace: body.workspace, organization: body.organization };
  }
  if (reply.status === 401) {
    return { status: 401 };
  }
  if (reply.status === 403 || reply.status === 404) {
    let detail = 'Not allowed.';
    try {
      const problem = await reply.json();
      // "Quota exceeded: The organization may use ..." says why; "Forbidden" alone says nothing.
      detail = problem.title && problem.title !== 'Forbidden' && problem.detail
        ? problem.title + ': ' + problem.detail
        : problem.detail || detail;
    } catch (e) {
      // not a problem detail
    }
    return { status: 403, reason: plain(detail) };
  }
  r.error('gateway: the go-ahead answered ' + reply.status);
  return { status: 502 };
}

async function check(r) {
  const v = r.variables;
  const key = v.gw_authkey || '';
  const session = v.mk_session || '';
  if (!key && !session) {
    r.return(401);
    return;
  }
  const credential = key ? 'key:' + key : 'session:' + session;
  const id = v.gw_org + '|' + (v.gw_ws || '') + '|' + crypto.createHash('sha256').update(credential).digest('base64url');
  let decision;
  let cache = 'HIT';
  const kept = decisions.get(id);
  if (kept) {
    decision = JSON.parse(kept);
  } else {
    cache = 'MISS';
    const pending = served.get(id) || 0;
    decision = await ask(r, v, key, session, pending);
    if (decision.status >= 500) {
      r.return(decision.status);
      return;
    }
    if (decision.status === 204 && pending > 0) {
      served.incr(id, -pending, 0);
    }
    decisions.set(id, JSON.stringify(decision));
  }
  r.headersOut['X-Geoportal-Cache'] = cache;
  if (decision.status !== 204) {
    if (decision.reason) {
      r.headersOut['X-Geoportal-Refusal'] = decision.reason;
    }
    r.return(decision.status);
    return;
  }
  served.incr(id, 1, 0);
  if (!key) {
    r.headersOut['X-Geoportal-User'] = decision.user;
  }
  r.headersOut['X-Geoportal-Account'] = decision.user;
  r.headersOut['X-Geoportal-Workspace'] = decision.workspace;
  r.headersOut['X-Geoportal-Organization'] = decision.organization;
  r.return(204);
}

export default { check };
