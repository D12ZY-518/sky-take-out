// Native WeChat page added alongside the legacy compiled UniApp pages.
// It uses only the local dev/demo identity. Do not treat this as a real login integration.
const API_URL = 'http://localhost:8080/user/service/chat-python';
const SESSION_KEY = 'sky_agent_demo_session';
const PENDING_KEY = 'sky_agent_demo_pending';

function makeId() {
  return 'sa_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 14);
}

Page({
  data: {
    draft: '', busy: false, pending: false, status: '就绪', lastMessageId: '',
    messages: [{id:'welcome', role:'assistant', text:'你好，我是禾巷餐饮售后助手。先试试“我的订单有哪些？”。', tools:[], toolsText:''}]
  },
  onLoad(options = {}) {
    this.sessionId = wx.getStorageSync(SESSION_KEY) || makeId();
    wx.setStorageSync(SESSION_KEY, this.sessionId);
    this.pendingRequest = wx.getStorageSync(PENDING_KEY) || null;
    if (this.pendingRequest) this.setData({pending:true, status:'上次结果未确认，可用同一请求 ID 重试。'});
    const orderNumber = typeof options.orderNumber === 'string' ? options.orderNumber.trim() : '';
    if (/^[0-9A-Za-z_-]{1,64}$/.test(orderNumber)) {
      this.setData({draft:`请帮我查订单 ${orderNumber} 的情况`});
    }
  },
  onInput(event) { this.setData({draft:event.detail.value}); },
  useExample(event) { this.setData({draft:event.currentTarget.dataset.text}); },
  addMessage(role, text, tools) {
    const item = {id:makeId(), role, text, tools:tools || [], toolsText:(tools || []).join(' → ')};
    this.setData({messages:this.data.messages.concat(item), lastMessageId:item.id});
  },
  setPending(request) {
    this.pendingRequest = request;
    if (request) wx.setStorageSync(PENDING_KEY, request);
    else wx.removeStorageSync(PENDING_KEY);
    this.setData({pending:!!request});
  },
  send() {
    const message = this.data.draft.trim();
    if (!message || this.data.busy) return;
    const proceed = () => {
      const request = {message, sessionId:this.sessionId, requestId:makeId()};
      this.setData({draft:''});
      this.callAgent(request, false);
    };
    if (this.pendingRequest) wx.showModal({title:'上次结果未确认', content:'发送新消息会放弃上一条的重试 ID，仍要继续吗？', success:r => {if (r.confirm) proceed();}});
    else proceed();
  },
  retry() { if (this.pendingRequest && !this.data.busy) this.callAgent(this.pendingRequest, true); },
  callAgent(request, isRetry) {
    this.setPending(request);
    this.setData({busy:true, status:'Agent 正在处理，请勿重复发送。'});
    if (!isRetry) this.addMessage('user', request.message);
    const submit = () => wx.request({
      url:API_URL, method:'POST', data:request, timeout:70000,
      header:{'content-type':'application/json'},
      success:res => {
        const body = res.data || {};
        if (res.statusCode !== 200 || body.code !== 1 || !body.data) {
          this.addMessage('assistant', '接口返回错误，无法确认操作结果。请先检查服务状态，再用同一请求 ID 重试。');
          this.setData({status:'结果未确认；保留请求 ID。'});
          return;
        }
        const reply = body.data.reply || 'Agent 未返回文字。';
        const tools = Array.isArray(body.data.toolCalls) ? body.data.toolCalls : [];
        this.addMessage('assistant', reply, tools);
        if (body.data.outcome !== 'completed') {
          this.setData({status:'处理结果未确认；已保留请求 ID，可先核对原工单。'});
        } else {
          this.setPending(null);
          this.setData({status:'就绪'});
        }
      },
      fail:error => {
        this.addMessage('assistant', '未收到明确结果：' + (error.errMsg || '网络错误') + '。超时不代表操作失败；可用同一请求 ID 重试。');
        this.setData({status:'结果未确认；保留请求 ID。'});
      },
      complete:() => this.setData({busy:false})
    });
    if (!isRetry) { submit(); return; }
    // 重试先核对数据库的已提交结果，避免重新跑模型后改写原工单内容。
    wx.request({
      url:'http://localhost:8080/user/service/ticket-result', method:'GET',
      data:{requestId:request.requestId}, timeout:10000,
      success:res => {
        const body = res.data || {};
        if (res.statusCode !== 200 || body.code !== 1 || !body.data
            || !['completed','unknown'].includes(body.data.outcome)) {
          this.setData({busy:false,status:'原工单核对失败，已保留请求 ID。'});
          return;
        }
        if (body.data.outcome === 'completed') {
          this.addMessage('assistant',body.data.reply,[]);
          this.setPending(null);
          this.setData({busy:false,status:'已取回原工单，本次未重复提交。'});
          return;
        }
        submit(); // 暂无记录仍可能在处理中；沿用原 ID，由数据库唯一约束去重。
      },
      fail:() => this.setData({busy:false,status:'原工单核对失败，已保留请求 ID。'})
    });
  },
  newSession() {
    if (this.data.busy) return;
    wx.showModal({title:'开始新会话？', content:'这不会删除已经创建的工单。', success:res => {
      if (!res.confirm) return;
      this.sessionId = makeId();
      wx.setStorageSync(SESSION_KEY, this.sessionId);
      this.setPending(null);
      this.setData({messages:[], status:'新会话已开始', draft:''});
      this.addMessage('assistant', '新会话已开始。你可以先问“我的订单有哪些？”。');
    }});
  }
});
