'use strict';
const $=id=>document.getElementById(id);
let models=[], source=null, taskId=null, count=0, stepNum=0;
const safe=(s)=>{const e=document.createElement('span');e.textContent=s;return e;};
function log(type,message){
  $('events').querySelector('.empty')?.remove();
  const div=document.createElement('div');div.className='log '+type;
  div.textContent='['+new Date().toLocaleTimeString()+'] '+message;
  $('events').appendChild(div);count++;$('eventCount').textContent=count+' events';
  $('events').scrollTop=$('events').scrollHeight;
}
function token(t){$('events').querySelector('.empty')?.remove();let last=$('events').lastElementChild;
  if(!last||!last.classList.contains('stream')){last=document.createElement('div');last.className='stream';$('events').appendChild(last)}
  last.appendChild(safe(t));$('events').scrollTop=$('events').scrollHeight;
}
const sizeText=n=>n>=1024**3?(n/1024**3).toFixed(2)+' GB':(n/1024**2).toFixed(0)+' MB';
function sortedModels(){const key=$('sort').value;return [...models].sort((a,b)=>{
 if(key==='size-asc')return (a.size||0)-(b.size||0);
 if(key==='size-desc')return (b.size||0)-(a.size||0);
 return key==='name-desc'?b.name.localeCompare(a.name):a.name.localeCompare(b.name);
});}
function renderModels(){const old=$('model').value;$('model').replaceChildren(new Option('Choose a model…',''));
 sortedModels().forEach(m=>$('model').add(new Option(m.name+' · '+sizeText(m.size||0),m.name)));
 if(models.some(m=>m.name===old))$('model').value=old;modelDetails();}
function modelDetails(){const m=models.find(m=>m.name===$('model').value);
 $('modelInfo').textContent=m?'Selected: '+m.name+' · '+sizeText(m.size||0)+' · Model runs only when you submit a task.':'No model selected. Nothing is loaded automatically.';}
async function request(url,options){const r=await fetch(url,options);const data=await r.json();if(!r.ok)throw Error(data.error||'HTTP '+r.status);return data;}
async function refresh(){const btn=$('refresh');btn.disabled=true;$('connection').textContent='Connecting…';try{
 models=await request('/api/models');renderModels();$('connection').textContent='● Ollama online';$('connection').style.color='#80d6ad';
}catch(e){$('connection').textContent='● Ollama offline';$('connection').style.color='#ff9393';log('error','Could not fetch Ollama models: '+e.message)}finally{btn.disabled=false;}}
function busy(on){$('start').disabled=on;$('stop').disabled=!on;$('model').disabled=on;$('taskStatus').textContent=on?'Running':'Idle';}
function showPlan(raw){let plan;try{plan=JSON.parse(raw)}catch(e){log('error','Unable to show plan: '+e.message);return}
 $('steps').replaceChildren();stepNum=0;$('progress').textContent='0 / '+plan.length+' complete';
 plan.forEach((s,i)=>{let li=document.createElement('li');li.id='step-'+(i+1);li.textContent=s.title+' — '+s.instruction;$('steps').appendChild(li)});}
function listen(id){source?.close();source=new EventSource('/api/tasks/'+encodeURIComponent(id)+'/events');
 const receive=(type,fn)=>source.addEventListener(type,e=>fn(e.data));
 receive('token',token);receive('status',s=>log('status',s));receive('plan',showPlan);
 receive('step',s=>{stepNum++;document.querySelectorAll('#steps li').forEach(li=>li.classList.remove('active'));$('step-'+stepNum)?.classList.add('active');log('step',s)});
 receive('stepDone',s=>{const item=$('step-'+stepNum);item?.classList.remove('active');item?.classList.add('done');$('progress').textContent=stepNum+' steps complete';log('file','Step completed: '+s)});
 receive('file',s=>log('file',s));receive('complete',s=>{log('file',s);finish()});
 receive('error',s=>{log('error',s);finish()});receive('stopped',s=>{log('error',s);finish()});receive('end',()=>finish());
 source.onerror=async()=>{if(!source)return;try{const s=await request('/api/tasks/'+id);if(s.done)finish()}catch{} };
}
function finish(){source?.close();source=null;busy(false);taskId=null;}
async function start(){if(!$('model').value){log('error','Choose an Ollama model before starting');return}
 if(!$('prompt').value.trim()){log('error','Enter a task prompt');return}
 busy(true);$('steps').innerHTML='<li class="placeholder">Planning…</li>';$('progress').textContent='Planning';
 try{const out=await request('/api/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:$('model').value,prompt:$('prompt').value,contextSize:Number($('ctx').value),approveWrites:$('writes').checked})});
 taskId=out.id;log('status','Task started: '+taskId);listen(taskId);
 }catch(e){log('error',e.message);finish()}}
async function stop(){if(!taskId)return;$('stop').disabled=true;try{await request('/api/tasks/'+taskId+'/stop',{method:'POST'});}catch(e){log('error',e.message)}}
function clearOutput(){$('events').innerHTML='<div class="empty">Waiting for the next update…</div>';count=0;$('eventCount').textContent='0 events'}
$('refresh').onclick=refresh;$('sort').onchange=renderModels;$('model').onchange=modelDetails;
$('ctx').oninput=()=>$('ctxValue').textContent=$('ctx').value;$('start').onclick=start;$('stop').onclick=stop;
$('clearOutput').onclick=clearOutput;$('clear').onclick=()=>{clearOutput();$('steps').innerHTML='<li class="placeholder">Your plan appears here.</li>';};
request('/api/workspace').then(v=>$('workspace').textContent=v.path).catch(e=>$('workspace').textContent=e.message);
refresh();
function resizer(id,leftIndex,rightIndex){const bar=$(id);bar.addEventListener('pointerdown',e=>{if(innerWidth<=900)return;
 bar.setPointerCapture(e.pointerId);const startX=e.clientX;const parts=getComputedStyle($('layout')).gridTemplateColumns.split(' ').map(parseFloat);
 const initialLeft=parts[leftIndex], initialRight=parts[rightIndex];
 function move(ev){const dx=ev.clientX-startX;const left=Math.max(220,Math.min(initialLeft+initialRight-320,initialLeft+dx));const right=initialLeft+initialRight-left;
 const updated=[...parts];updated[leftIndex]=left;updated[rightIndex]=right;$('layout').style.gridTemplateColumns=updated.map((v,i)=>i===1||i===3?'7px':v+'px').join(' ');}
 bar.addEventListener('pointermove',move);bar.addEventListener('pointerup',()=>bar.removeEventListener('pointermove',move),{once:true});});}
resizer('r1',0,2);resizer('r2',2,4);
