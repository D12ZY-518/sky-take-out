// Local-only adapter for the old 瑞吉外卖 admin build. The course assets stay untouched.
// Usage: node scripts/legacy_admin_demo.js <absolute-path-to-nginx/html/sky>
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const assetRoot = process.argv[2] && path.resolve(process.argv[2]);
const port = Number(process.env.SKY_ADMIN_DEMO_PORT || 8081);
const ticketPage = path.resolve(__dirname, '..', 'frontend', 'admin-tickets.html');
const themeCss = path.resolve(__dirname, '..', 'frontend', 'admin-theme.css');
const brandImages = {
  '/img/logo.38b01728.png': path.resolve(__dirname, '..', 'frontend', 'brand-logo.svg'),
  '/img/icon_logo.38b01728.png': path.resolve(__dirname, '..', 'frontend', 'brand-logo.svg'),
  '/img/mini-logo.bf141cfc.png': path.resolve(__dirname, '..', 'frontend', 'brand-mark.svg')
};

const types = {
  '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.woff': 'font/woff',
  '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.mp3': 'audio/mpeg'
};

const dateFields = new Set([
  'createTime', 'updateTime', 'orderTime', 'checkoutTime', 'cancelTime',
  'estimatedDeliveryTime', 'deliveryTime'
]);

function legacyDateFields(value) {
  if (Array.isArray(value)) return value.map(legacyDateFields);
  if (!value || typeof value !== 'object') return value;
  for (const [key, item] of Object.entries(value)) {
    if (dateFields.has(key) && Array.isArray(item) && item.length >= 5 && item.length <= 7 &&
        item.every(Number.isInteger)) {
      const [year, month, day, hour, minute, second = 0] = item;
      const pad = number => String(number).padStart(2, '0');
      value[key] = `${year}-${pad(month)}-${pad(day)} ${pad(hour)}:${pad(minute)}:${pad(second)}`;
    } else {
      value[key] = legacyDateFields(item);
    }
  }
  return value;
}

function legacySocketUrl(script) {
  // The old compiled build hard-codes port 80. Java's live order socket is on 8080.
  return script.replace('ws://localhost/ws/', 'ws://127.0.0.1:8080/ws/');
}

function legacyBrand(text) {
  return text.replaceAll('瑞吉外卖', '禾巷餐饮').replaceAll('苍穹外卖', '禾巷餐饮');
}

function legacyOrderPage(script) {
  // The old bundle assumes a fixed ¥6 delivery fee and a non-null packAmount.
  // This backend stores only the charged order amount, not a separate delivery fee.
  return script
    .replaceAll('t._v("菜品小计")', 't._v("订单金额")')
    .replaceAll('t._v("菜品小计：")', 't._v("订单金额：")')
    .replaceAll('(t.diaForm.amount-6-t.diaForm.packAmount).toFixed(2)',
      'Number(t.diaForm.amount||0).toFixed(2)')
    .replace('a("span",{staticClass:"amount-price"},[t._v("￥"+t._s(6))])',
      'a("span",{staticClass:"amount-price"},[t._v("未单独记录")])')
    .replace('t._v("￥"+t._s(t.diaForm.packAmount?100*t.diaForm.packAmount.toFixed(2)/100:""))',
      't._v(t.diaForm.packAmount?"￥"+t._s(100*t.diaForm.packAmount.toFixed(2)/100):"未记录")');
}

function proxyAdmin(req, res) {
  const upstream = http.request({
    hostname: '127.0.0.1', port: 8080, method: req.method,
    path: '/admin/' + req.url.slice('/api/'.length),
    headers: {...req.headers, host: '127.0.0.1:8080', 'accept-encoding': 'identity'}
  }, upstreamRes => {
    const isJson = (upstreamRes.headers['content-type'] || '').includes('application/json');
    if (!isJson || req.method === 'HEAD') {
      res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
      upstreamRes.pipe(res);
      return;
    }
    const chunks = [];
    upstreamRes.on('data', chunk => chunks.push(chunk));
    upstreamRes.on('end', () => {
      const body = Buffer.concat(chunks);
      let output = body;
      try { output = Buffer.from(JSON.stringify(legacyDateFields(JSON.parse(body.toString('utf8'))))); }
      catch (_) { /* Preserve Java's original response if it is not valid JSON. */ }
      const headers = {...upstreamRes.headers};
      delete headers['content-length'];
      delete headers['transfer-encoding'];
      res.writeHead(upstreamRes.statusCode || 502, headers);
      res.end(output);
    });
  });
  upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, {'Content-Type':'text/plain; charset=utf-8'}); res.end('Java 后端 8080 不可达。请确认 Docker 服务已启动。'); });
  req.pipe(upstream);
}

const server = http.createServer((req, res) => {
  let url;
  try { url = new URL(req.url, 'http://127.0.0.1'); }
  catch (_) { res.writeHead(400); res.end(); return; }
  if (url.pathname.startsWith('/api/')) { proxyAdmin(req, res); return; }
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return; }
  if (brandImages[url.pathname]) {
    res.setHeader('Content-Type', 'image/svg+xml; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    if (req.method === 'HEAD') res.end(); else fs.createReadStream(brandImages[url.pathname]).pipe(res);
    return;
  }
  if (url.pathname === '/service-tickets.html') {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    if (req.method === 'HEAD') res.end(); else fs.createReadStream(ticketPage).pipe(res);
    return;
  }
  if (url.pathname === '/admin-theme.css') {
    res.setHeader('Content-Type', 'text/css; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    if (req.method === 'HEAD') res.end(); else fs.createReadStream(themeCss).pipe(res);
    return;
  }
  let name;
  try { name = decodeURIComponent(url.pathname); }
  catch (_) { res.writeHead(400); res.end(); return; }
  if (name.includes('\0') || name.includes('\\') || name.split('/').includes('..')) { res.writeHead(400); res.end(); return; }
  const requested = path.resolve(assetRoot, '.' + name);
  if (requested !== assetRoot && !requested.startsWith(assetRoot + path.sep)) { res.writeHead(403); res.end(); return; }
  let file = requested;
  try { if (!fs.statSync(file).isFile()) file = path.join(assetRoot, 'index.html'); }
  catch (_) { if (path.extname(name)) { res.writeHead(404); res.end(); return; } file = path.join(assetRoot, 'index.html'); }
  const ext = path.extname(file).toLowerCase();
  res.setHeader('Content-Type', types[ext] || 'application/octet-stream');
  res.setHeader('Cache-Control', 'no-store');
  if (file === path.join(assetRoot, 'index.html')) {
    const html = fs.readFileSync(file, 'utf8');
    const ticketLink = `<style>#sky-ticket-link{position:fixed;left:16px;bottom:22px;z-index:10001;width:158px;box-sizing:border-box;padding:11px 12px;border-radius:12px;background:#195a49;color:#fff;text-align:center;font-size:14px;text-decoration:none;font-weight:600;box-shadow:0 8px 24px #123e3533}#sky-ticket-link:hover{background:#26715c}</style><a id="sky-ticket-link" href="/service-tickets.html">Agent 售后工单</a><script>(function(){var link=document.getElementById('sky-ticket-link');function refresh(){link.style.display=location.hash.indexOf('/login')>=0?'none':'block'}window.addEventListener('hashchange',refresh);refresh()})()</script>`;
    const themed = legacyBrand(html).replace('</head>', '<link rel="stylesheet" href="/admin-theme.css"></head>');
    res.end(req.method === 'HEAD' ? undefined : themed.replace('</body>', ticketLink + '</body>'));
  } else if (path.basename(file).startsWith('app.') && ext === '.js') {
    res.end(req.method === 'HEAD' ? undefined : legacyBrand(legacySocketUrl(fs.readFileSync(file, 'utf8'))));
  } else if (path.basename(file).startsWith('shopTable.') && ext === '.js') {
    res.end(req.method === 'HEAD' ? undefined : legacyOrderPage(fs.readFileSync(file, 'utf8')));
  } else {
    const stream = fs.createReadStream(file);
    stream.on('error', () => { if (!res.headersSent) res.writeHead(500); res.end(); });
    if (req.method === 'HEAD') res.end(); else stream.pipe(res);
  }
});
if (require.main === module) {
  if (!assetRoot || !fs.existsSync(path.join(assetRoot, 'index.html'))) {
    console.error('Pass the old admin build directory containing index.html.');
    process.exit(1);
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('Invalid SKY_ADMIN_DEMO_PORT.');
    process.exit(1);
  }
  server.listen(port, '127.0.0.1', () => console.log(`Legacy admin demo: http://127.0.0.1:${port}/`));
}

module.exports = {legacyDateFields, legacySocketUrl, legacyOrderPage, legacyBrand};
