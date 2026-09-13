const VERSION = 'v2';
const SHELL_CACHE = 'omni-shell-' + VERSION;
const ASSET_CACHE = 'omni-assets-' + VERSION;
const SHELL_URL = '/index.html';
const ASSETS = ['/icon.svg', '/manifest.json'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    Promise.all([
      caches.open(SHELL_CACHE).then((c) => c.add(SHELL_URL)),
      caches.open(ASSET_CACHE).then((c) => c.addAll(ASSETS)),
    ]).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== SHELL_CACHE && k !== ASSET_CACHE)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

// The app shell changes on every deploy, so it is served network-first and only
// falls back to cache when offline. Static assets are cached but refreshed in
// the background (stale-while-revalidate) so a bumped VERSION is not the only
// thing standing between a user and a current build.
self.addEventListener('fetch', (e) => {
  const request = e.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.includes('/api/') || url.hostname.includes('workers.dev')) return;

  const isShell = request.mode === 'navigate' || url.pathname === '/' || url.pathname === SHELL_URL;

  if (isShell) {
    e.respondWith(
      fetch(request)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(SHELL_CACHE).then((c) => c.put(SHELL_URL, copy)).catch(() => {});
          }
          return res;
        })
        .catch(() =>
          caches.match(SHELL_URL, { cacheName: SHELL_CACHE }).then(
            (cached) => cached || Response.error()
          )
        )
    );
    return;
  }

  e.respondWith(
    caches.match(request, { cacheName: ASSET_CACHE }).then((cached) => {
      const network = fetch(request)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(ASSET_CACHE).then((c) => c.put(request, copy)).catch(() => {});
          }
          return res;
        })
        .catch(() => cached);
      return cached || network;
    })
  );
});
