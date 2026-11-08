const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {legacyDateFields, legacySocketUrl, legacyOrderPage, legacyBrand} = require('./legacy_admin_demo');

const response = {
  code: 1,
  data: {
    records: [{
      id: 1,
      orderTime: [2026, 9, 17, 12, 30],
      updateTime: [2026, 9, 17, 12, 30, 5],
      flavors: ['少辣', '不要葱'],
      otherNumbers: [1, 2, 3, 4, 5]
    }]
  }
};
const adapted = legacyDateFields(response);
assert.equal(adapted.data.records[0].orderTime, '2026-09-17 12:30:00');
assert.equal(adapted.data.records[0].updateTime, '2026-09-17 12:30:05');
assert.deepEqual(adapted.data.records[0].flavors, ['少辣', '不要葱']);
assert.deepEqual(adapted.data.records[0].otherNumbers, [1, 2, 3, 4, 5]);

const script = 'new WebSocket("ws://localhost/ws/" + clientId)';
assert.equal(legacySocketUrl(script),
  'new WebSocket("ws://127.0.0.1:8080/ws/" + clientId)');
assert.equal(legacySocketUrl('ws://example.com/ws/'), 'ws://example.com/ws/');
assert.equal(legacyBrand('<title>瑞吉外卖</title>'), '<title>禾巷餐饮</title>');
assert.equal(legacyBrand('苍穹外卖'), '禾巷餐饮');

const oldAssets = process.env.SKY_LEGACY_ADMIN_ASSETS;
const bundlePath = oldAssets && fs.readdirSync(path.join(oldAssets, 'js'))
  .find(name => /^shopTable\..*\.js$/.test(name));
const orderBundle = bundlePath
  ? fs.readFileSync(path.join(oldAssets, 'js', bundlePath), 'utf8')
  : 't._v("菜品小计") (t.diaForm.amount-6-t.diaForm.packAmount).toFixed(2) a("span",{staticClass:"amount-price"},[t._v("￥"+t._s(6))]) t._v("￥"+t._s(t.diaForm.packAmount?100*t.diaForm.packAmount.toFixed(2)/100:""))';
assert.ok(orderBundle.includes('(t.diaForm.amount-6-t.diaForm.packAmount).toFixed(2)'));
assert.ok(orderBundle.includes('t._v("￥"+t._s(6))'));
const patchedOrderBundle = legacyOrderPage(orderBundle);
assert.ok(!patchedOrderBundle.includes('(t.diaForm.amount-6-t.diaForm.packAmount).toFixed(2)'));
assert.ok(!patchedOrderBundle.includes('t._v("￥"+t._s(6))'));
assert.ok(patchedOrderBundle.includes('未单独记录'));
assert.ok(patchedOrderBundle.includes('未记录'));
assert.ok(fs.readFileSync(path.join(__dirname, '..', 'frontend', 'admin-theme.css'), 'utf8').includes('.login-box::before'));
console.log('Legacy admin date, socket and order amount adaptation OK');
