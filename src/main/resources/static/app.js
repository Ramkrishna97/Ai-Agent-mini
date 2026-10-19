'use strict';
const $=id=>document.getElementById(id);
let models=[], source=null, taskId=null, count=0, stepNum=0, currentStream=null, refreshTimer=null, attachmentIds=[], attachmentItems=[], lastTaskId=null, conversationId=sessionStorage.getItem('localAgentConversationId'), chatDraft='', chatRenderPending=false, chatHistory=[], activeChatArticle=null, pendingChatPrompt='', selectedModel=sessionStorage.getItem('localAgentSelectedModel');
const safe=(s)=>{const e=document.createElement('span');e.textContent=s;return e;};
function log(type,message){
  $('events').querySelector('.empty')?.remove();
  const div=document.createElement('div');div.className='log '+type;
  div.textContent='['+new Date().toLocaleTimeString()+'] '+message;
  $('events').appendChild(div);count++;$('eventCount').textContent=count+' events';
  $('events').scrollTop=$('events').scrollHeight;
}
function token(t){$('events').querySelector('.empty')?.remove();
  if(!currentStream){currentStream=document.createElement('pre');currentStream.className='stream';$('events').appendChild(currentStream)}
  currentStream.appendChild(safe(t));$('events').scrollTop=$('events').scrollHeight;
}
const sizeText=n=>n>=1024**3?(n/1024**3).toFixed(2)+' GB':(n/1024**2).toFixed(0)+' MB';
function sortKey(){return $('sort').value}
function sortedModels(){const key=sortKey();return [...models].sort((a,b)=>{
 if(key==='size-asc')return (a.size||0)-(b.size||0);
 if(key==='size-desc')return (b.size||0)-(a.size||0);
 return key==='name-desc'?b.name.localeCompare(a.name):a.name.localeCompare(b.name);
});}
function renderModels(){const old=$('model').value;$('model').replaceChildren(new Option('Choose a model…',''));
 sortedModels().forEach(m=>$('model').add(new Option(m.name+' · '+sizeText(m.size||0),m.name)));
 const choice=models.some(m=>m.name===old)?old:(models.some(m=>m.name===selectedModel)?selectedModel:'');$('model').value=choice;modelDetails();}
function modelDetails(){const m=models.find(m=>m.name===$('model').value);
 $('modelInfo').textContent=m?'Selected: '+m.name+' · '+sizeText(m.size||0)+' · Kept loaded until offloaded or another model is chosen.':'No model selected.';
 $('thinkControl').hidden=!(m?.capabilities||[]).includes('thinking'); if($('thinkControl').hidden)$('think').checked=false;}
async function request(url,options){const r=await fetch(url,options);const data=await r.json();if(!r.ok)throw Error(data.error||'HTTP '+r.status);return data;}
async function upload(file){const data=new FormData();data.append('file',file);const r=await fetch('/api/attachments',{method:'POST',body:data});const value=await r.json();if(!r.ok)throw Error(value.error||'Upload failed');return value;}
function renderAttachments(items){$('attachmentList').textContent=items.length?items.map(a=>a.name+' ('+a.kind+')').join(', '):'No files included.';}
async function refresh(quiet=false){const btn=$('refresh');btn.disabled=true;if(!quiet)$('connection').textContent='Connecting to Ollama…';try{
 models=await request('/api/models');renderModels();$('connection').textContent='● Ollama online';$('connection').style.color='#80d6ad';
 try{const loaded=await request('/api/models/loaded');$('loadedModels').textContent=loaded.length?('Loaded: '+loaded.map(m=>m.name).join(', ')):'No model loaded';}catch{$('loadedModels').textContent='Loaded model status unavailable'}
}catch(e){$('connection').textContent='● Ollama offline · retrying';$('connection').style.color='#ff9393';if(!quiet)log('status','Waiting for Ollama. The model list will refresh automatically: '+e.message)}finally{btn.disabled=false;}}
function busy(on){$('start').disabled=on;$('stop').disabled=!on;$('model').disabled=on;$('mode').disabled=on;$('taskStatus').textContent=on?'Running':'Idle';}
function showPlan(raw){let plan;try{plan=JSON.parse(raw)}catch(e){log('error','Unable to show plan: '+e.message);return}
 $('steps').replaceChildren();stepNum=0;$('progress').textContent='0 / '+plan.length+' complete';currentStream=null;
 plan.forEach((s,i)=>{let li=document.createElement('li');li.id='step-'+(i+1);li.textContent=s.title+' — '+s.instruction;$('steps').appendChild(li)});}
function inlineMarkdown(text){
 const fragment=document.createDocumentFragment();
 const pattern=/(\`[^\`]+\`|\*\*[^*]+\*\*|\*[^*\n]+\*|\[[^\]]+\]\(https?:\/\/[^)]+\))/g;
 let last=0, match;
 while((match=pattern.exec(text))){
  fragment.append(document.createTextNode(text.slice(last,match.index)));
  const token=match[0];let element;
  if(token.startsWith('`')){element=document.createElement('code');element.textContent=token.slice(1,-1)}
  else if(token.startsWith('**')){element=document.createElement('strong');element.textContent=token.slice(2,-2)}
  else if(token.startsWith('*')){element=document.createElement('em');element.textContent=token.slice(1,-1)}
  else{element=document.createElement('a');const end=token.indexOf('](');element.textContent=token.slice(1,end);element.href=token.slice(end+2,-1);element.target='_blank';element.rel='noopener noreferrer'}
  fragment.append(element);last=pattern.lastIndex;
 }
 fragment.append(document.createTextNode(text.slice(last)));return fragment;
}
function codeCard(source,language){
 const box=document.createElement('section');box.className='code-card';
 const head=document.createElement('div');head.className='code-head';
 const label=document.createElement('span');label.textContent=language||'code';
 const copy=document.createElement('button');copy.textContent='Copy';
 const open=document.createElement('button');open.textContent='Open code';
 const pre=document.createElement('pre');const code=document.createElement('code');
 code.innerHTML=highlightCode(source,language);pre.append(code);
 copy.onclick=()=>navigator.clipboard.writeText(source);
 open.onclick=()=>showCodeWindow(language||'code',source);
 head.append(label,copy,open);box.append(head,pre);return box;
}
function formatAnswer(markdown){
 const root=document.createElement('div'),lines=markdown.split(/\r?\n/);
 let paragraph=[],list=null;
 const flush=()=>{if(paragraph.length){const p=document.createElement('p');p.append(inlineMarkdown(paragraph.join(' ')));root.append(p);paragraph=[]}list=null};
 for(let i=0;i<lines.length;i++){
  const line=lines[i],fence=line.match(/^\s*```([\w#+.-]*)\s*$/);
  if(fence){flush();const code=[];while(++i<lines.length&&!/^\s*```\s*$/.test(lines[i]))code.push(lines[i]);root.append(codeCard(code.join('\n'),fence[1]));continue}
  const heading=line.match(/^\s*(#{1,6})\s+(.+)$/);
  if(heading){flush();const h=document.createElement('h'+heading[1].length);h.append(inlineMarkdown(heading[2]));root.append(h);continue}
  if(line.includes('|')&&i+1<lines.length&&/^\s*\|?\s*:?-{3,}/.test(lines[i+1])){
   flush();const table=document.createElement('table');let header=true;
   const row=value=>{let cells=value.trim().replace(/^\|/,'').replace(/\|$/,'').split('|');const tr=document.createElement('tr');for(const part of cells){const cell=document.createElement(header?'th':'td');cell.append(inlineMarkdown(part.trim()));tr.append(cell)}table.append(tr);header=false};
   row(line);i+=2;while(i<lines.length&&lines[i].includes('|'))row(lines[i++]);i--;root.append(table);continue;
  }
  const item=line.match(/^\s*([-*+\u2022] |\d+\. )(.*)$/);
  if(item){if(paragraph.length){const p=document.createElement('p');p.append(inlineMarkdown(paragraph.join(' ')));root.append(p);paragraph=[]}
   const ordered=/\d/.test(item[1][0]);if(!list||list.tagName!==(ordered?'OL':'UL')){list=document.createElement(ordered?'ol':'ul');root.append(list)}
   const li=document.createElement('li');li.append(inlineMarkdown(item[2]));list.append(li);continue}
  if(!line.trim()){flush();continue}
  if(list)list=null;
  paragraph.push(line.trim());
 }
 flush();return root;
}
function highlightCode(source,language){const escaped=source.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));const keywords='class public private protected static void int long double boolean if else for while return new import package const let var function async await def from as print try catch throw extends implements';return escaped.replace(/("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_$][\w$]*\b)/g,m=>{if(/^['"]/.test(m))return '<span class="tok-string">'+m+'</span>';if(/^\d/.test(m))return '<span class="tok-number">'+m+'</span>';if(keywords.split(' ').includes(m))return '<span class="tok-keyword">'+m+'</span>';return m})}
function showCodeWindow(title,content){$('codeTitle').textContent=title;$('codeWindowContent').innerHTML=highlightCode(content,title.split('.').pop());$('codeWindow').showModal()}
function addChatTurn(prompt,answer=''){
 const turn=document.createElement('section');turn.className='chat-turn';
 const question=document.createElement('div');question.className='chat-question';question.textContent=prompt;
 const article=document.createElement('article');article.className='formatted';article.replaceChildren(formatAnswer(answer));
 turn.append(question,article);$('chatTurns').append(turn);$('chatResult').hidden=false;return article;
}
function showChatAnswer(raw){
 chatDraft=raw;$('chatResult').hidden=false;
 if(chatRenderPending)return;
 chatRenderPending=true;
 requestAnimationFrame(()=>{const pane=$('chatResult'),follow=pane.scrollHeight-pane.scrollTop-pane.clientHeight<100;chatRenderPending=false;if(activeChatArticle)activeChatArticle.replaceChildren(formatAnswer(chatDraft));if(follow)pane.scrollTop=pane.scrollHeight});
}
function clearChatState(){conversationId=null;chatDraft='';chatHistory=[];activeChatArticle=null;pendingChatPrompt='';sessionStorage.removeItem('localAgentConversationId');sessionStorage.removeItem('localAgentChatHistory');$('chatTurns').replaceChildren();$('chatResult').hidden=true}
function setMode(){
 const chat=$('mode').value==='chat';
 $('layout').classList.toggle('chat-mode',chat);$('chatContext').hidden=!chat;
 $('outputTitle').textContent=chat?'Conversation':'Live output';
 $('chatResult').hidden=!chat||!$('chatTurns').childElementCount;
 $('start').textContent=chat?'Chat':'Plan & execute';
 $('steps').hidden=chat;$('progress').hidden=chat;
}
function listen(id){source?.close();source=new EventSource('/api/tasks/'+encodeURIComponent(id)+'/events');
 const receive=(type,fn)=>source.addEventListener(type,e=>fn(e.data));
 receive('token',s=>{if($('mode').value==='chat'){chatDraft+=s;showChatAnswer(chatDraft)}else token(s)});receive('status',s=>{currentStream=null;log('status',s)});receive('plan',showPlan);receive('chatResult',raw=>{showChatAnswer(raw);if(pendingChatPrompt){chatHistory.push({prompt:pendingChatPrompt,answer:raw});pendingChatPrompt='';sessionStorage.setItem('localAgentChatHistory',JSON.stringify(chatHistory))}});
 receive('step',s=>{stepNum++;currentStream=null;const section=document.createElement('section');section.className='step-output';const heading=document.createElement('h3');heading.textContent=s;section.appendChild(heading);const stream=document.createElement('pre');stream.className='stream';section.appendChild(stream);$('events').appendChild(section);currentStream=stream;document.querySelectorAll('#steps li').forEach(li=>li.classList.remove('active'));$('step-'+stepNum)?.classList.add('active');$('events').scrollTop=$('events').scrollHeight;log('step',s)});
 receive('stepDone',s=>{const item=$('step-'+stepNum);item?.classList.remove('active');item?.classList.add('done');$('progress').textContent=stepNum+' steps complete';log('file','Step completed: '+s)});
 receive('file',s=>log('file',s));receive('complete',s=>{log('file',s);finish()});
 receive('error',s=>{log('error',s);if(activeChatArticle){const note=document.createElement('p');note.className='chat-error';note.textContent=s;activeChatArticle.append(note)}finish()});receive('stopped',s=>{log('error',s);if(activeChatArticle){const note=document.createElement('p');note.className='chat-error';note.textContent=s;activeChatArticle.append(note)}finish()});receive('end',()=>finish());
 source.onerror=async()=>{if(!source)return;try{const s=await request('/api/tasks/'+id);if(s.done)finish()}catch{} };
}
function download(name,content,type='text/plain;charset=utf-8'){const link=document.createElement('a');link.href=URL.createObjectURL(new Blob([content],{type}));link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),500)}
function exportTask(id,format){location.href=format==='zip'?'/api/tasks/'+id+'/export.zip':'/api/tasks/'+id+'/response?format='+encodeURIComponent(format)}
function safeHtml(value){return value.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
async function finish(){const finishedId=taskId;source?.close();source=null;busy(false);taskId=null;lastTaskId=finishedId;if(finishedId){try{const [files,metrics]=await Promise.all([request('/api/tasks/'+finishedId+'/files'),request('/api/tasks/'+finishedId+'/metrics')]);const entries=Object.entries(files);$('finalCode').hidden=!entries.length;$('codeFiles').replaceChildren();entries.forEach(([name,content])=>{const article=document.createElement('article');const head=document.createElement('div');head.className='file-head';const title=document.createElement('h3');title.textContent=name;const save=document.createElement('button');save.textContent='Save to workspace';save.onclick=async()=>{try{const out=await request('/api/tasks/'+finishedId+'/files/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});log('file','Saved '+out.path)}catch(e){log('error',e.message)}};const dl=document.createElement('button');dl.textContent='Download';dl.onclick=()=>download(name,content);head.append(title,save,dl);const code=document.createElement('pre');code.textContent=content;article.append(head,code);$('codeFiles').appendChild(article)});$('export').disabled=!entries.length&&$('mode').value!=='chat';$('export').onclick=()=>exportTask(finishedId,$('exportFormat').value);$('exportFiles').onclick=()=>exportTask(finishedId,'zip');$('metrics').innerHTML='<span>Prompt: '+metrics.promptTokens+' tokens · '+metrics.promptTokensPerSecond+' tok/s</span><span>Generated: '+metrics.generatedTokens+' tokens · '+metrics.generatedTokensPerSecond+' tok/s</span><span>Total: '+metrics.totalSeconds+' s · Load: '+metrics.loadSeconds+' s</span>';}catch(e){log('error','Could not load final results: '+e.message)}}}
async function start(){if(!$('model').value){log('error','Choose an Ollama model before starting');return}
 if(!$('prompt').value.trim()){log('error','Enter a task prompt');return}
 busy(true);chatDraft='';activeChatArticle=null;if($('mode').value==='chat'){pendingChatPrompt=$('prompt').value.trim();activeChatArticle=addChatTurn(pendingChatPrompt)}$('finalCode').hidden=true;$('export').disabled=true;$('codeFiles').replaceChildren();$('steps').hidden=$('mode').value==='chat';$('progress').hidden=$('mode').value==='chat';$('steps').innerHTML='<li class="placeholder">Planning…</li>';$('progress').textContent='Planning';
 try{const out=await request('/api/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:$('model').value,prompt:$('prompt').value,contextSize:Number($('ctx').value),approveWrites:$('writes').checked,chatOnly:$('mode').value==='chat',think:$('think').checked,attachmentIds,conversationId})});
 taskId=out.id;if(out.conversationId){conversationId=out.conversationId;sessionStorage.setItem('localAgentConversationId',conversationId);$('prompt').value=''}log('status','Task started: '+taskId);listen(taskId);
 }catch(e){if(pendingChatPrompt){activeChatArticle?.closest('.chat-turn')?.remove();pendingChatPrompt='';activeChatArticle=null}log('error',e.message);finish()}}
async function stop(){if(!taskId)return;$('stop').disabled=true;try{await request('/api/tasks/'+taskId+'/stop',{method:'POST'});}catch(e){log('error',e.message)}}
function clearOutput(){$('events').innerHTML='<div class="empty">Waiting for the next update…</div>';count=0;$('eventCount').textContent='0 events'}
 $('refresh').onclick=()=>refresh();$('model').onchange=async()=>{
 const model=$('model').value, previous=selectedModel, oldChat=conversationId;
 modelDetails();if(!model||model===previous)return;
 clearChatState();selectedModel=model;sessionStorage.setItem('localAgentSelectedModel',model);
 $('loadedModels').textContent='Loading '+model+'…';$('model').disabled=true;
 try{
  if(oldChat)await request('/api/chats/'+encodeURIComponent(oldChat),{method:'DELETE'});
  if(previous)await request('/api/models/offload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:previous})});
  await request('/api/models/load',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model})});
  await refresh(true);
 }catch(e){$('loadedModels').textContent='Load failed';log('error','Could not load model: '+e.message)}finally{$('model').disabled=false}
};
$('sortToggle').onclick=()=>{$('sort').hidden=!$('sort').hidden};$('sort').onchange=()=>{renderModels();$('sort').hidden=true};
$('mode').onchange=setMode;
$('offload').onclick=async()=>{const model=$('model').value;if(!model||model.startsWith('__sort:')){log('error','Choose a model to offload');return}try{await request('/api/models/offload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model})});log('status','Offloaded '+model);refresh(true)}catch(e){log('error','Offload failed: '+e.message)}};
$('attach').onclick=async()=>{const files=[...$('attachments').files];if(!files.length)return;try{const results=[];for(const file of files)results.push(await upload(file));attachmentIds.push(...results.map(x=>x.id));attachmentItems.push(...results);renderAttachments(attachmentItems);$('attachments').value='';log('status','Included '+results.length+' attachment(s)')}catch(e){log('error',e.message)}};
$('copyCode').onclick=()=>navigator.clipboard.writeText($('codeWindowContent').textContent);$('closeCode').onclick=()=>$('codeWindow').close();
$('saveWorkspace').onclick=async()=>{try{const result=await request('/api/workspace',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:$('workspaceInput').value})});$('workspace').textContent=result.path;$('workspaceInput').value=result.path;log('status','Workspace updated')}catch(e){log('error',e.message)}};
$('ctx').oninput=()=>$('ctxValue').textContent=$('ctx').value;$('start').onclick=start;$('stop').onclick=stop;
$('newChat').onclick=async()=>{if(taskId)return;if(conversationId){try{await request('/api/chats/'+encodeURIComponent(conversationId),{method:'DELETE'})}catch(e){log('error',e.message);return}}clearChatState();$('prompt').value='';log('status','Started a new conversation')};
$('clearOutput').onclick=clearOutput;$('clear').onclick=()=>{clearOutput();$('steps').innerHTML='<li class="placeholder">Your plan appears here.</li>';};
request('/api/workspace').then(v=>{$('workspace').textContent=v.path;$('workspaceInput').value=v.path}).catch(e=>$('workspace').textContent=e.message);
try{chatHistory=JSON.parse(sessionStorage.getItem('localAgentChatHistory')||'[]');if(!Array.isArray(chatHistory))chatHistory=[];chatHistory.forEach(turn=>addChatTurn(turn.prompt,turn.answer))}catch{chatHistory=[]}setMode();
refresh();
refreshTimer=setInterval(()=>refresh(true),3500);
function resizer(id,leftIndex,rightIndex){const bar=$(id);bar.addEventListener('pointerdown',e=>{if(innerWidth<=1200)return;
 bar.setPointerCapture(e.pointerId);const startX=e.clientX;const parts=getComputedStyle($('layout')).gridTemplateColumns.split(' ').map(parseFloat);
 const initialLeft=parts[leftIndex], initialRight=parts[rightIndex];
 function move(ev){const dx=ev.clientX-startX;const left=Math.max(220,Math.min(initialLeft+initialRight-320,initialLeft+dx));const right=initialLeft+initialRight-left;
 const updated=[...parts];updated[leftIndex]=left;updated[rightIndex]=right;$('layout').style.gridTemplateColumns=updated.map((v,i)=>i===1||i===3?'7px':v+'px').join(' ');}
 bar.addEventListener('pointermove',move);bar.addEventListener('pointerup',()=>bar.removeEventListener('pointermove',move),{once:true});});}
resizer('r1',0,2);resizer('r2',2,4);
