/* Service Worker - Voice Notes PWA */
const CACHE = 'voice-notes-v7';
const ASSETS = ['./', './index.html', './style.css', './app.js', './manifest.json', './icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    // সব পুরনো cache মুছো
    const keys = await caches.keys();
    const oldCaches = keys.filter(k => k !== CACHE);
    await Promise.all(oldCaches.map(k => caches.delete(k)));
    await self.clients.claim();
    if (oldCaches.length > 0) {
      const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      clients.forEach(client => client.postMessage({ type: 'APP_UPDATED' }));
    }
  })());
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/')) return;

  e.respondWith((async () => {
    const cache = await caches.open(CACHE);

    // index.html: cache-first + background refresh (TWA cold start stays fast)
    const isRoot = url.pathname === '/' || url.pathname === '/index.html';
    if (isRoot) {
      const cached = await cache.match(e.request) || await cache.match('./index.html');
      const refresh = fetch(e.request, { cache: 'no-store' })
        .then(fresh => { if (fresh.ok) cache.put(e.request, fresh.clone()); return fresh; })
        .catch(() => null);
      if (cached) {
        refresh;
        return cached;
      }
      const fresh = await refresh;
      return fresh || cached;
    }

    // বাকি সব: Cache-first, background update (stale-while-revalidate)
    const cached = await cache.match(e.request);
    const fetchAndUpdate = fetch(e.request, { cache: 'no-store' })
      .then(fresh => { if (fresh.ok) cache.put(e.request, fresh.clone()); return fresh; })
      .catch(() => null);

    if (cached) {
      fetchAndUpdate; // background update, await করছি না
      return cached;
    }

    const fresh = await fetchAndUpdate;
    return fresh || await cache.match('./index.html');
  })());
});
