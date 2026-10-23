// Mock KYC para la demo: node mock-kyc/server.mjs [puerto]
import http from 'node:http';
import { randomUUID } from 'node:crypto';

const port = Number(process.argv[2] ?? 8082);
const verifications = new Map();

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

function view(v) {
  switch (v.scenario) {
    case 'REJECTED':
      return { id: v.id, status: 'REJECTED', walletStatus: 'REJECTED' };
    case 'MANUAL_REVIEW':
      return v.decision
        ? { id: v.id, status: v.decision === 'ACTIVE' ? 'APPROVED' : 'REJECTED', walletStatus: v.decision }
        : { id: v.id, status: 'PENDING', walletStatus: 'PENDING' };
    default:
      return { id: v.id, status: 'APPROVED', walletStatus: 'ACTIVE' };
  }
}

http.createServer((req, res) => {
  let raw = '';
  req.on('data', (chunk) => (raw += chunk));
  req.on('end', () => {
    let body = {};
    try { body = raw ? JSON.parse(raw) : {}; } catch { return send(res, 400, { error: 'JSON inválido' }); }

    const parts = new URL(req.url, 'http://localhost').pathname.split('/').filter(Boolean);
    if (parts[0] !== 'kyc' || parts[1] !== 'verifications') return send(res, 404, { error: 'No encontrado' });

    if (req.method === 'POST' && parts.length === 2) {
      if (body.scenario === 'UNAVAILABLE') return send(res, 503, { error: 'Proveedor no disponible' });
      const v = { id: randomUUID(), scenario: body.scenario ?? 'APPROVED', decision: null };
      verifications.set(v.id, v);
      return send(res, 201, { id: v.id });
    }

    const v = verifications.get(parts[2]);
    if (!v) return send(res, 404, { error: 'Verificación no encontrada' });

    if (req.method === 'GET' && parts.length === 3) return send(res, 200, view(v));
    if (req.method === 'POST' && parts[3] === 'decision') {
      v.decision = body.decision;
      return send(res, 200, view(v));
    }
    send(res, 404, { error: 'No encontrado' });
  });
}).listen(port, '127.0.0.1', () => console.log(`Mock KYC en http://localhost:${port}`));
