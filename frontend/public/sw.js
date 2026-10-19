/*
 * Service worker de CoopHabitat — mode hors ligne partiel.
 * Règle stricte : seules l'application (fichiers statiques) et trois informations publiques sont mises en cache.
 * Aucune réponse de l'API privée (données personnelles ou financières) n'est jamais stockée ici.
 */
const VERSION = 'coophabitat-v2';
const SHELL = `${VERSION}-app`;
const PUBLIC = `${VERSION}-public`;
const PUBLIC_API = ['/api/v1/public/info', '/api/v1/public/projects', '/api/v1/public/documents'];
const STATIC = /\.(?:js|css|svg|png|jpe?g|webp|avif|woff2?|webmanifest)$/;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(['/', '/index.html', '/icon.svg', '/manifest.webmanifest'])).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  // À la déconnexion, l'application peut demander l'effacement complet des caches.
  if (event.data === 'vider-caches') event.waitUntil(caches.keys().then((keys) => Promise.all(keys.map((k) => caches.delete(k)))));
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname.startsWith('/api/')) {
    if (PUBLIC_API.includes(url.pathname) && !req.headers.has('Authorization')) {
      event.respondWith(networkFirst(req, PUBLIC));
    }
    return; // toute autre requête d'API passe directement par le réseau, sans cache
  }

  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).catch(() => caches.match('/index.html')));
    return;
  }

  if (STATIC.test(url.pathname)) event.respondWith(cacheFirst(req));
});

async function cacheFirst(req) {
  const hit = await caches.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok) (await caches.open(SHELL)).put(req, res.clone());
  return res;
}

async function networkFirst(req, name) {
  try {
    const res = await fetch(req);
    if (res.ok) (await caches.open(name)).put(req, res.clone());
    return res;
  } catch (e) {
    const hit = await caches.match(req);
    if (hit) return hit;
    throw e;
  }
}
