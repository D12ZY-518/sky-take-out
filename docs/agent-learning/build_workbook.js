// Regenerate the standalone offline workbook from the actual tools.py source.
// Usage: node docs/agent-learning/build_workbook.js
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const folder = __dirname;
const sourcePath = path.resolve(folder, '../../python-agent/tools.py');
const agentPath = path.resolve(folder, '../../python-agent/agent.py');
const templatePath = path.join(folder, '01-tools离线学习工作台.template.html');
const outputPath = path.join(folder, '01-tools离线学习工作台.html');

const source = fs.readFileSync(sourcePath, 'utf8');
const agentSource = fs.readFileSync(agentPath, 'utf8');
const runStart = agentSource.indexOf('def run(');
if (runStart < 0) throw new Error('agent.py run() not found');
const agentRun = agentSource.slice(runStart).trimEnd();
const mainSource = fs.readFileSync(path.resolve(folder, '../../python-agent/main.py'), 'utf8');
const javaSource = fs.readFileSync(path.resolve(folder, '../../sky-server/src/main/java/com/sky/controller/user/ServiceAgentController.java'), 'utf8');
const webSource = fs.readFileSync(path.resolve(folder, '../../sky-server/src/main/resources/static/service-agent.html'), 'utf8');
const serviceSource = fs.readFileSync(path.resolve(folder, '../../sky-server/src/main/java/com/sky/service/impl/InternalApiServiceImpl.java'), 'utf8').replace(/\r\n/g, '\n');
function between(text, start, end) {
  const begin = text.indexOf(start);
  const finish = text.indexOf(end, begin);
  if (begin < 0 || finish < begin) throw new Error(`Source markers missing: ${start}`);
  return text.slice(begin, finish).trimEnd();
}
const chatHandler = between(mainSource, 'class ChatResp(BaseModel):', '@app.get("/health")');
const ticketLookup = between(javaSource, '    @GetMapping("/ticket-result")', '\n    }') + '\n    }';
const webRetry = between(webSource, '  async function run(', '\n  document.getElementById(\'chat-form\')');
const ticketCreate = between(serviceSource, '    @Override\n    public String createTicket(', '    /**\n     * Orders 实体');
const template = fs.readFileSync(templatePath, 'utf8');
const hash = crypto.createHash('sha256').update(source).digest('hex').slice(0, 12);
const agentHash = crypto.createHash('sha256').update(agentRun).digest('hex').slice(0, 12);
const escapeHtml = text => text
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;');

if (!template.includes('__TOOLS_SOURCE_HTML_ESCAPED__') ||
    !template.includes('__AGENT_RUN_HTML_ESCAPED__') ||
    !template.includes('__SOURCE_SHA__') || !template.includes('__AGENT_RUN_SHA__') ||
    !template.includes('__CHAT_HANDLER_HTML_ESCAPED__') ||
    !template.includes('__TICKET_LOOKUP_HTML_ESCAPED__') ||
    !template.includes('__TICKET_CREATE_HTML_ESCAPED__') ||
    !template.includes('__WEB_RETRY_HTML_ESCAPED__')) {
  throw new Error('Workbook template markers are missing');
}

const html = template
  .replace('__TOOLS_SOURCE_HTML_ESCAPED__', escapeHtml(source))
  .replace('__AGENT_RUN_HTML_ESCAPED__', escapeHtml(agentRun))
  .replace('__CHAT_HANDLER_HTML_ESCAPED__', escapeHtml(chatHandler))
  .replace('__TICKET_LOOKUP_HTML_ESCAPED__', escapeHtml(ticketLookup))
  .replace('__TICKET_CREATE_HTML_ESCAPED__', escapeHtml(ticketCreate))
  .replace('__WEB_RETRY_HTML_ESCAPED__', escapeHtml(webRetry))
  .replace('__SOURCE_SHA__', hash)
  .replace('__AGENT_RUN_SHA__', agentHash);
if (/__(?:CHAT_HANDLER|TICKET_LOOKUP|TICKET_CREATE|WEB_RETRY)_HTML_ESCAPED__/.test(html)) {
  throw new Error('Retry workbook markers not replaced');
}
fs.writeFileSync(outputPath, html, 'utf8');
console.log(`Generated ${outputPath} (tools.py: ${hash}, agent.run: ${agentHash})`);
