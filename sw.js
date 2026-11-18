const VERSION = 'v3';
const SHELL_CACHE = 'omni-shell-' + VERSION;
const ASSET_CACHE = 'omni-assets-' + VERSION;
const SHELL_URL = '/index.html';
const ASSETS = ['/icon.svg', '/manifest.json'];

// A redirect must never be stored. Replaying a cached 307 sends the next
// load through the same hop, and Cache.put rejects 3xx outright.
function isCacheableResponse(res) {
  return !!(res && res.type === 'basic' && res.status >= 200 && res.status < 300);
}

async function cacheFresh(cacheName, key, url) {
  let res = await fetch(url, { redirect: 'manual', cache: 'no-store' });
  if (res.status >= 300 && res.status < 400) {
    const loc = res.headers.get('Location');
    if (!loc) return;
    const next = new URL(loc, self.location.origin);
    if (next.origin !== self.location.origin) return;
    res = await fetch(next.href, { redirect: 'manual', cache: 'no-store' });
  }
  if (!isCacheableResponse(res)) return;
  const cache = await caches.open(cacheName);
  await cache.put(key, res);
}

self.addEventListener('install', (e) => {
  e.waitUntil(
    Promise.all([
      cacheFresh(SHELL_CACHE, SHELL_URL, SHELL_URL),
      Promise.all(ASSETS.map((url) => cacheFresh(ASSET_CACHE, url, url))),
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
//
// Same-origin requests on *.workers.dev are the app itself. Cross-origin
// calls, including the API worker, never reach this handler.
self.addEventListener('fetch', (e) => {
  const request = e.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.includes('/api/')) return;

  const isShell = request.mode === 'navigate' || url.pathname === '/' || url.pathname === SHELL_URL;

  if (isShell) {
    e.respondWith(
      fetch(request)
        .then((res) => {
          if (isCacheableResponse(res) && !res.redirected) {
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
          if (isCacheableResponse(res) && !res.redirected) {
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
