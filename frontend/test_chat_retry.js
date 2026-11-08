// Execute the real browser/mini-program scripts without network or a paid model.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const browserSource = fs.readFileSync(path.join(root, 'sky-server/src/main/resources/static/service-agent.html'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

async function browserScenario(reply, outcome, receiptFound = true, receiptFails = false) {
  const stored = new Map(), elements = new Map(), calls = [];
  function element() { return {textContent:'',value:'',disabled:false,listeners:{},appendChild(){},replaceChildren(){},focus(){},addEventListener(k,v){this.listeners[k]=v;}}; }
  const document = {getElementById(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);},createElement:element,querySelectorAll(){return [];}};
  const sessionStorage = {getItem:k=>stored.get(k)||null,setItem:(k,v)=>stored.set(k,v),removeItem:k=>stored.delete(k)};
  vm.runInNewContext(browserSource, {document,sessionStorage,crypto:{randomUUID:()=> 'send-browser-0001'},confirm:()=>true,
    fetch:async(url,options)=>{
      calls.push({url,options});
      const data = options.method === 'GET'
        ? {reply:receiptFound?'原工单 TKoriginal':'暂无记录',toolCalls:[],outcome:receiptFound?'completed':'unknown'}
        : {reply,toolCalls:[],outcome};
      return {ok:!(options.method==='GET' && receiptFails),status:receiptFails?503:200,json:async()=>({code:1,data})};
    }});
  elements.get('message').value = '登记人工工单';
  elements.get('chat-form').listeners.submit({preventDefault(){}});
  await new Promise(setImmediate);
  return {stored,elements,calls};
}

function miniScenario(reply, outcome, receiptFound = true, receiptFails = false) {
  const stored = new Map(), calls = []; let page;
  const wx = {getStorageSync:k=>stored.get(k),setStorageSync:(k,v)=>stored.set(k,v),removeStorageSync:k=>stored.delete(k),
    request:options=>{
      calls.push(options);
      const data = options.method==='GET'
        ? {reply:'原工单 TKoriginal',toolCalls:[],outcome:receiptFound?'completed':'unknown'}
        : {reply,toolCalls:[],outcome};
      options.success({statusCode:options.method==='GET' && receiptFails?503:200,data:{code:1,data}});
      if(options.complete) options.complete();
    }};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'mp-weixin/pages/service-agent/index.js'),'utf8'),{Page:p=>page=p,wx,Date,Math,Array});
  page.setData = patch=>Object.assign(page.data,patch);
  page.onLoad(); page.setData({draft:'登记人工工单'}); page.send();
  return {page,stored,calls};
}

(async()=>{
  for(const [reply,outcome] of [['写入结果未知','unknown'],['AI 服务暂时开小差了','unknown'],['旧服务未提供处理状态',undefined]]) {
    const web = await browserScenario(reply,outcome);
    assert.equal(web.stored.has('sky-service-agent-demo-pending'),true,`browser must keep ID: ${reply}`);
    assert.equal(web.elements.get('retry').disabled,false);
    const mini = miniScenario(reply,outcome);
    assert.equal(mini.page.data.pending,true,`mini must keep ID: ${reply}`);
  }
  assert.equal((await browserScenario('处理已结束','completed')).stored.has('sky-service-agent-demo-pending'),false);
  assert.equal(miniScenario('处理已结束','completed').page.data.pending,false);
  const recovered = await browserScenario('未知','unknown');
  recovered.elements.get('retry').listeners.click();
  await new Promise(setImmediate);
  assert.equal(recovered.calls.filter(c=>c.options.method==='POST').length,1,'receipt recovery must not rerun model');
  assert.equal(recovered.calls.filter(c=>c.options.method==='GET').length,1);
  assert.equal(recovered.stored.has('sky-service-agent-demo-pending'),false);
  const absent = await browserScenario('未知','unknown',false);
  absent.elements.get('retry').listeners.click();
  await new Promise(setImmediate);
  const posts = absent.calls.filter(c=>c.options.method==='POST');
  assert.equal(posts.length,2);
  assert.equal(JSON.parse(posts[0].options.body).requestId,JSON.parse(posts[1].options.body).requestId);
  const unavailable = await browserScenario('未知','unknown',true,true);
  unavailable.elements.get('retry').listeners.click();
  await new Promise(setImmediate);
  assert.equal(unavailable.calls.filter(c=>c.options.method==='POST').length,1);
  assert.equal(unavailable.stored.has('sky-service-agent-demo-pending'),true);
  for (const [found,failed] of [[true,false],[false,false],[false,true]]) {
    const mini = miniScenario('未知','unknown',found,failed);
    const original = mini.calls[0].data;
    mini.page.retry();
    const miniPosts = mini.calls.filter(c=>c.method==='POST');
    assert.equal(miniPosts.length, !found && !failed?2:1);
    assert.equal(mini.page.data.pending, !found || failed);
    assert.equal(mini.page.data.busy,false);
    assert.equal(mini.calls[1].data.requestId,original.requestId);
    if(miniPosts.length===2) assert.equal(miniPosts[1].data.requestId,original.requestId);
  }
  console.log('Structured unknown outcomes preserve retry IDs in both frontends');
})().catch(error=>{console.error(error);process.exitCode=1;});
