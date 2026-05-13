/* Service Worker - Voice Notes PWA */
const CACHE = 'voice-notes-v4';
const ASSETS = ['./', './index.html', './style.css', './app.js', './manifest.json', './icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
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
    const cached = await cache.match(e.request);

    // Cache থেকে সাথেসাথে দেখাও; background-এ নতুন version নামাও (stale-while-revalidate)
    const fetchAndUpdate = fetch(e.request, { cache: 'no-store' })
      .then(fresh => { if (fresh.ok) cache.put(e.request, fresh.clone()); return fresh; })
      .catch(() => null);

    if (cached) {
      // Cache hit — তাৎক্ষণিক দেখাও, নতুন version চুপচাপ পেছনে নামাও
      fetchAndUpdate;
      return cached;
    }

    // Cache miss — নেটওয়ার্ক থেকে আনো, না হলে index.html
    const fresh = await fetchAndUpdate;
    return fresh || await cache.match('./index.html');
  })());
});
