import adminHtml from './admin.html';

const encoder = new TextEncoder();
const json = (status, value) => new Response(JSON.stringify(value), {
  status, headers: {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff'}
});
const bytesToHex = bytes => Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
const hash = async value => bytesToHex(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))));
const decode64 = value => Uint8Array.from(atob(value), c => c.charCodeAt(0));

// Android SHA256withECDSA produces ASN.1 DER. Web Crypto ECDSA expects 64-byte P1363.
export function derToRaw(input) {
  const data = input instanceof Uint8Array ? input : new Uint8Array(input);
  let offset = 0;
  const take = () => { if (offset >= data.length) throw Error('bad signature'); return data[offset++]; };
  const length = () => { const n = take(); if (n < 128) return n; if (n !== 0x81) throw Error('bad signature'); return take(); };
  if (take() !== 0x30 || length() !== data.length - offset) throw Error('bad signature');
  const raw = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    if (take() !== 0x02) throw Error('bad signature');
    let size = length();
    if (size < 1 || size > 33 || offset + size > data.length) throw Error('bad signature');
    let number = data.slice(offset, offset + size); offset += size;
    if (number.length === 33) {
      if (number[0] !== 0) throw Error('bad signature');
      number = number.slice(1);
    }
    if (number.length > 32) throw Error('bad signature');
    raw.set(number, part * 32 + 32 - number.length);
  }
  if (offset !== data.length) throw Error('bad signature');
  return raw;
}

async function verify(publicKey, action, code, nonce, signature) {
  try {
    const key = await crypto.subtle.importKey('spki', decode64(publicKey), {name: 'ECDSA', namedCurve: 'P-256'}, false, ['verify']);
    return await crypto.subtle.verify({name: 'ECDSA', hash: 'SHA-256'}, key, derToRaw(decode64(signature)), encoder.encode(`${action}\n${code}\n${nonce}`));
  } catch { return false; }
}

async function body(request) {
  if (Number(request.headers.get('content-length') || 0) > 8192) throw Error('invalid request');
  const text = await request.text();
  if (!text || text.length > 8192) throw Error('invalid request');
  const parsed = JSON.parse(text);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw Error('invalid request');
  return parsed;
}

function admin(request, env) {
  const offered = request.headers.get('authorization') || '';
  const expected = 'Bearer ' + (env.DMA_ADMIN_TOKEN || '');
  if (!env.DMA_ADMIN_TOKEN || env.DMA_ADMIN_TOKEN.length < 32 || offered.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < offered.length; i++) diff |= offered.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}

async function device(request, env, action) {
  const value = await body(request);
  const code = String(value.key || '').trim().toUpperCase();
  const publicKey = String(value.public_key || '');
  const nonce = String(value.nonce || '');
  const signature = String(value.signature || '');
  if (!/^DMA-(?:[0-9A-F]{6}-){3}[0-9A-F]{6}$/.test(code) || publicKey.length > 1024 ||
      nonce.length < 16 || nonce.length > 100 || signature.length > 512) return json(400, {error: 'invalid request'});
  if (!await verify(publicKey, action, code, nonce, signature)) return json(403, {valid: false, reason: 'signature'});
  const now = Math.floor(Date.now() / 1000);
  const codeHash = await hash(code);
  const id = (await hash(publicKey)).slice(0, 16);
  const model = String(value.model || 'Unknown').slice(0, 100);
  const condition = action === 'activate' ? '(public_key IS NULL OR public_key = ?)' : 'public_key = ?';
  let results;
  try {
    results = await env.DB.batch([
      env.DB.prepare('INSERT INTO nonces (nonce, created_at) VALUES (?, ?)').bind(nonce, now),
      env.DB.prepare(`UPDATE licenses SET device_id=?, public_key=?, model=?, first_seen=COALESCE(first_seen, ?), last_seen=? WHERE code_hash=? AND revoked_at IS NULL AND ${condition}`)
        .bind(id, publicKey, model, now, now, codeHash, publicKey)
    ]);
  } catch (error) {
    if (String(error).includes('UNIQUE constraint failed')) return json(403, {valid: false, reason: 'replay'});
    throw error;
  }
  if (results[1].meta.changes !== 1) {
    const row = await env.DB.prepare('SELECT revoked_at, public_key FROM licenses WHERE code_hash=?').bind(codeHash).first();
    const reason = !row || row.revoked_at ? 'unknown or revoked' : row.public_key ? 'already bound to another phone' : 'activation required';
    return json(403, {valid: false, reason});
  }
  if (crypto.getRandomValues(new Uint8Array(1))[0] === 0) {
    await env.DB.prepare('DELETE FROM nonces WHERE created_at < ?').bind(now - 3600).run();
  }
  return json(200, {valid: true, device_id: id});
}

async function adminPost(request, env, path) {
  const value = await body(request);
  if (path === '/admin/api/create') {
    const label = String(value.label || '').trim().slice(0, 80);
    if (!label) return json(400, {error: 'label required'});
    const random = bytesToHex(crypto.getRandomValues(new Uint8Array(12))).toUpperCase();
    const code = 'DMA-' + random.match(/.{6}/g).join('-');
    await env.DB.prepare('INSERT INTO licenses (code_hash, label, created_at) VALUES (?, ?, ?)')
      .bind(await hash(code), label, Math.floor(Date.now() / 1000)).run();
    return json(201, {key: code});
  }
  if (path === '/admin/api/revoke') {
    const id = String(value.id || '');
    if (!/^[0-9a-f]{64}$/.test(id)) return json(400, {error: 'invalid id'});
    const result = await env.DB.prepare('UPDATE licenses SET revoked_at=? WHERE code_hash=? AND revoked_at IS NULL')
      .bind(Math.floor(Date.now() / 1000), id).run();
    return json(result.meta.changes ? 200 : 404, {revoked: result.meta.changes === 1});
  }
  return json(404, {error: 'not found'});
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    try {
      if (request.method === 'GET' && path === '/health') return json(200, {ok: true});
      if (request.method === 'GET' && path === '/admin') return new Response(adminHtml, {headers: {
        'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store',
        'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'"
      }});
      if (path.startsWith('/admin/api/')) {
        if (!admin(request, env)) return json(401, {error: 'unauthorized'});
        if (request.method === 'GET' && path === '/admin/api/licenses') {
          const result = await env.DB.prepare('SELECT code_hash AS id, label, created_at, revoked_at, device_id, model, first_seen, last_seen FROM licenses ORDER BY created_at DESC').all();
          return json(200, {licenses: result.results});
        }
        if (request.method === 'POST') return adminPost(request, env, path);
      }
      if (request.method === 'POST' && path === '/api/activate') return device(request, env, 'activate');
      if (request.method === 'POST' && path === '/api/check') return device(request, env, 'check');
      return json(404, {error: 'not found'});
    } catch (error) {
      if (error instanceof SyntaxError || error.message === 'invalid request') return json(400, {error: 'invalid request'});
      return json(500, {error: 'server error'});
    }
  }
};
