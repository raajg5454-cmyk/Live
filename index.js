const express = require('express'), http = require('http'), crypto = require('crypto');
const { WebSocketServer } = require('ws'), Database = require('better-sqlite3');
const bcrypt = require('bcryptjs'), jwt = require('jsonwebtoken');
const {
  JWT_SECRET, ADMIN_PHONE, ADMIN_PASSWORD, TURN_URL, TURN_SECRET, STUN_URL = 'stun:stun.l.google.com:19302', PORT = 3000
} = process.env;
if (!JWT_SECRET || JWT_SECRET.length < 32 || !ADMIN_PHONE || !ADMIN_PASSWORD) throw new Error('Set JWT_SECRET (32+ chars), ADMIN_PHONE and ADMIN_PASSWORD');

const db = new Database(process.env.DB_PATH || 'livehelp.db');
db.exec('create table if not exists users(id integer primary key, name text unique, hash text, role text)');

// Admin account is created/updated from environment variables on every start.
{ const h = bcrypt.hashSync(ADMIN_PASSWORD, 12), ex = db.prepare('select id from users where name=?').get(ADMIN_PHONE);
  if (ex) db.prepare("update users set hash=?, role='agent' where id=?").run(h, ex.id);
  else db.prepare("insert into users(name,hash,role) values(?,?,'agent')").run(ADMIN_PHONE, h); }

const app = express();
app.set('trust proxy', 1);
app.use(express.json({ limit: '4kb' }));
app.use(express.static('public'));

const hits = new Map();
function limit(req, res, next) {
  const now = Date.now(), h = (hits.get(req.ip) || []).filter(t => now - t < 60000); h.push(now); hits.set(req.ip, h);
  if (h.length > 10) return res.status(429).json({ error: 'Too many attempts, wait a minute' });
  next();
}
const sign = u => jwt.sign({ id: u.id, name: u.name, role: u.role }, JWT_SECRET, { expiresIn: '30d' });
const auth = (req, res, next) => { try { req.user = jwt.verify((req.headers.authorization || '').slice(7), JWT_SECRET); next(); } catch { res.sendStatus(401); } };

app.post('/api/register', limit, (req, res) => {
  const { phone, password } = req.body || {};
  if (!/^\+?\d{7,15}$/.test(phone || '') || typeof password !== 'string' || password.length < 8 || password.length > 128)
    return res.status(400).json({ error: 'Invalid mobile number or password (min 8 characters)' });
  try {
    const r = db.prepare('insert into users(name,hash,role) values(?,?,?)').run(phone, bcrypt.hashSync(password, 12), 'customer');
    res.json({ token: sign({ id: r.lastInsertRowid, name: phone, role: 'customer' }) });
  } catch { res.status(409).json({ error: 'This number is already registered' }); }
});
const login = role => (req, res) => {
  const { phone, username, password } = req.body || {};
  const u = db.prepare('select * from users where name=?').get(String(phone || username || ''));
  if (!u || !bcrypt.compareSync(String(password || ''), u.hash)) return res.status(401).json({ error: 'Wrong mobile number or password' });
  if (u.role !== role) return res.status(403).json({ error: u.role === 'agent' ? 'Admin login: open the LiveHelp website' : 'Use the LiveHelp app to log in' });
  res.json({ token: sign(u) });
};
app.post('/api/login', limit, login('customer'));
app.post('/api/agent/login', limit, login('agent'));

app.get('/api/ice', auth, (req, res) => {
  const servers = [{ urls: [STUN_URL] }];
  if (TURN_URL && TURN_SECRET) {
    const username = `${Math.floor(Date.now() / 1000) + 3600}:${req.user.id}`;
    servers.push({ urls: TURN_URL.split(','), username, credential: crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64') });
  }
  res.json({ iceServers: servers });
});

// ---- signaling relay (admin can watch many customers at once) ----
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 64 * 1024 });
const sessions = new Map(), agents = new Set();
const send = (ws, o) => ws && ws.readyState === 1 && ws.send(JSON.stringify(o));
const waiting = () => [...sessions.values()].filter(s => !s.agent).map(s => ({ id: s.id, phone: s.cust.user.name }));
const pushQueue = () => agents.forEach(a => send(a, { type: 'queue', sessions: waiting() }));
function endSession(s) {
  if (!s || !sessions.has(s.id)) return;
  sessions.delete(s.id);
  s.cust.session = null; send(s.cust, { type: 'ended' });
  if (s.agent) send(s.agent, { type: 'ended', sessionId: s.id });
  pushQueue();
}
wss.on('connection', ws => {
  const t = setTimeout(() => { if (!ws.user) ws.close(4001); }, 5000);
  ws.alive = true; ws.on('pong', () => ws.alive = true);
  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (!ws.user) {
      try { ws.user = jwt.verify(m.token, JWT_SECRET); } catch { return ws.close(4001); }
      if (ws.user.role === 'agent') { agents.add(ws); send(ws, { type: 'queue', sessions: waiting() }); }
      return;
    }
    const isAgent = ws.user.role === 'agent';
    const own = () => { const s = isAgent ? sessions.get(m.sessionId) : ws.session; return s && (!isAgent || s.agent === ws) ? s : null; };
    switch (m.type) {
      case 'request_support': {
        if (isAgent || ws.session) return;
        const s = { id: crypto.randomUUID(), cust: ws, agent: null }; sessions.set(s.id, s); ws.session = s;
        send(ws, { type: 'waiting' }); pushQueue(); break;
      }
      case 'accept': {
        const s = sessions.get(m.sessionId);
        if (!isAgent || !s || s.agent) return;
        s.agent = ws; send(s.cust, { type: 'connected' });
        send(ws, { type: 'connected', sessionId: s.id, phone: s.cust.user.name }); pushQueue(); break;
      }
      case 'signal': { const s = own(); if (!s || !s.agent) return; send(isAgent ? s.cust : s.agent, { type: 'signal', sessionId: s.id, data: m.data }); break; }
      case 'end': endSession(own()); break;
    }
  });
  ws.on('close', () => {
    clearTimeout(t); agents.delete(ws);
    if (ws.session) endSession(ws.session);
    [...sessions.values()].filter(s => s.agent === ws).forEach(endSession);
  });
});
setInterval(() => wss.clients.forEach(c => { if (!c.alive) return c.terminate(); c.alive = false; c.ping(); }), 30000);
server.listen(PORT, () => console.log('LiveHelp server on', PORT));
