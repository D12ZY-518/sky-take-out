// No-network contract check for the native mini-program Agent page.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const storage = new Map();
const calls = [];
let page;
let failFirst = true;
const wx = {
  getStorageSync:key => storage.get(key),
  setStorageSync:(key,value) => storage.set(key,value),
  removeStorageSync:key => storage.delete(key),
  showModal:options => options.success({confirm:true}),
  request:options => {
    calls.push(options);
    if (options.method === 'GET') options.success({statusCode:200,data:{code:1,data:{reply:'暂未查到工单',toolCalls:[],outcome:'unknown'}}});
    else if (failFirst) { failFirst = false; options.fail({errMsg:'mock network loss'}); }
    else options.success({statusCode:200,data:{code:1,data:{reply:'查到订单',toolCalls:['query_orders'],outcome:'completed'}}});
    if (options.complete) options.complete();
  }
};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'mp-weixin/pages/service-agent/index.js'),'utf8'), {
  Page:options => {page=options;}, wx, Date, Math, Array
});
page.setData = patch => Object.assign(page.data, patch);
page.onLoad();
page.setData({draft:'我的订单有哪些？'});
page.send();
assert.equal(calls.length,1);
assert.equal(calls[0].url,'http://localhost:8080/user/service/chat-python');
assert.equal(page.data.pending,true);
const first = calls[0].data;
page.retry();
assert.equal(calls.length,3);
assert.equal(calls[1].method,'GET');
assert.equal(calls[2].data.requestId,first.requestId);
assert.equal(calls[2].data.sessionId,first.sessionId);
assert.equal(page.data.pending,false);
assert.equal(page.data.messages.at(-1).toolsText,'query_orders');
page.onLoad({orderNumber:'202609270001'});
assert.equal(page.data.draft,'请帮我查订单 202609270001 的情况');
page.onLoad({orderNumber:'<invalid>'});
assert.equal(page.data.draft,'请帮我查订单 202609270001 的情况');

const miniRoot = path.join(__dirname,'mp-weixin');
const app = JSON.parse(fs.readFileSync(path.join(miniRoot,'app.json'),'utf8'));
assert.equal(app.pages[0],'pages/index/index');
for (const pagePath of app.pages) {
  for (const ext of ['js','json','wxml','wxss']) {
    assert.ok(fs.existsSync(path.join(miniRoot,pagePath+'.'+ext)), `${pagePath}.${ext} missing`);
  }
}
assert.match(fs.readFileSync(path.join(miniRoot,'pages/my/my.wxml'),'utf8'), /url="\/pages\/help\/index"/);
assert.match(fs.readFileSync(path.join(miniRoot,'pages/details/index.wxml'),'utf8'), /orderNumber=\{\{orderDetailsData\.number\}\}/);
assert.match(fs.readFileSync(path.join(miniRoot,'pages/help/index.wxml'),'utf8'), /url="\/pages\/service-agent\/index"/);
console.log('Mini app navigation and Agent retry contract OK');
