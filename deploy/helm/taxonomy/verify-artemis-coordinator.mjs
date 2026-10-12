import { isIP } from 'node:net';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

function normalizedIp(ip) {
  if (!isIP(ip)) throw new Error('Invalid observed IP');
  return isIP(ip) === 6 ? new URL(`http://[${ip}]`).hostname : ip;
}
// Artemis 2.53 ActiveMQServerControlImpl.listConnectionsAsJSON emits clientAddress
// from the remote socket, plus sessionCount. No substring or hostname matching.
export function matchingConnectionCount(response, ip) {
  const expected = normalizedIp(ip);
  if (response?.status !== 200 || typeof response.value !== 'string') throw new Error('Unobserved broker connections');
  const connections = JSON.parse(response.value);
  if (!Array.isArray(connections) || connections.length > 2048) throw new Error('Unbounded connection observation');
  let count = 0;
  for (const connection of connections) {
    if (typeof connection?.clientAddress !== 'string' || !Number.isSafeInteger(connection.sessionCount) || connection.sessionCount < 0)
      throw new Error('Incomplete connection observation');
    const address = connection.clientAddress.match(/^\/?(?:\[([a-fA-F0-9:]+)\]|([0-9.]+)):(\d{1,5})$/);
    if (!address || Number(address[3]) < 1 || Number(address[3]) > 65535) throw new Error('Invalid remote socket');
    if (normalizedIp(address[1] ?? address[2]) === expected) count++;
  }
  return count;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    let body = '';
    for await (const chunk of process.stdin) {
      body += chunk;
      if (Buffer.byteLength(body) > 65536) throw new Error('Oversized observation');
    }
    const connectionCount = matchingConnectionCount(JSON.parse(body), process.argv[2]);
    console.log(JSON.stringify({observed:true, observedAt:new Date().toISOString(), connectionCount}));
  } catch {
    // Never print management response fields, identities, user names or server errors.
    console.error('Coordinator broker connection observation unavailable');
    process.exitCode = 1;
  }
}
