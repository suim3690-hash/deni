(() => {
  const section=document.createElement('section');
  section.className='panel'; section.style.marginTop='20px';
  section.innerHTML=`<h2>위험 물체 모니터링</h2><div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px"><label for="aiMode">인식 모드</label><select id="aiMode" style="padding:10px;background:#1f3041;color:white;border-radius:8px"><option value="object">Object · 배터리 / 동전 / 구슬</option><option value="hazard">Hazard · 콘센트 / 전선</option></select><span id="aiModeStatus" class="small"></span></div><p class="small">선택한 커스텀 모델에 ByteTrack 추적을 적용합니다. COCO는 사용하지 않습니다. 현재 모델에는 사람·칼·가위 클래스가 없습니다.</p><div id="aiLevel" role="status" style="font-size:23px;font-weight:700">분석 준비 중</div><p id="aiStatus" class="small"></p><p id="aiObjects"></p><p class="small">선택한 모델의 클래스만 탐지합니다. 미검출은 안전을 뜻하지 않습니다.</p><h2>발견 이력 · 사진으로 위치 확인</h2><div id="aiHistory" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:14px"></div><p id="aiHistoryStatus" class="small"></p><button id="aiMore">이전 사진 더 보기</button>`;
  document.querySelector('main').after(section);
  const el=id=>document.getElementById(id);
  const labels={battery:'배터리',coin:'동전',marble:'구슬',socket:'콘센트',wire:'전선',knife:'칼',scissors:'가위',person:'사람'};
  const levels=['위험 물체 미검출','주의','경고','위험'];
  const statuses={switching:'모델 전환 중',loading:'모델 로딩 중',waiting:'분석할 프레임 대기 중',disabled:'객체 인식 꺼짐',blur:'흐림으로 분석 건너뜀',error:'객체 인식 오류',decode_error:'영상 해석 오류',ok:'분석 중',partial:'일부 모델만 분석 중'};
  async function get(url){const c=new AbortController(), t=setTimeout(()=>c.abort(),1500);try{const r=await fetch(url,{signal:c.signal,cache:'no-store'});const d=await r.json();if(!r.ok)throw Error(d.error||'조회 실패');return d}finally{clearTimeout(t)}}
  let selecting=false;
  el('aiMode').onchange=async()=>{
    const mode=el('aiMode').value;
    selecting=true;el('aiMode').disabled=true;el('aiModeStatus').textContent='전환 요청 중';
    try{await post('/detections/mode',{owner:'detection-mode-ui',mode},1500);el('aiModeStatus').textContent='전환 요청 완료'}
    catch(e){el('aiModeStatus').textContent=e.message}
    finally{selecting=false;el('aiMode').disabled=false}
  };
  async function status(){try{
    const s=await get('/detections/state');
    if(!selecting&&s.mode)el('aiMode').value=s.mode;
    const current=s.current_level;
    el('aiLevel').textContent=current==null?(s.camera_unavailable?'카메라 수신 대기':s.stale&&s.frame_stamp?'분석 결과 오래됨':statuses[s.status]||'분석 대기'):levels[current];
    el('aiLevel').style.color=current===3?'#ff737b':current===2?'#ffb66e':current===1?'#ffe397':'#b7c9d9';
    const parts=[statuses[s.status]||s.status];
    if(s.inference_ms!=null)parts.push('추론 '+s.inference_ms+' ms');
    if(s.result_age!=null)parts.push('분석 프레임 '+s.result_age+'초 전');
    if(s.blur_score!=null)parts.push('선명도 '+s.blur_score);
    if(s.error)parts.push(s.error);
    for(const [name,error] of Object.entries(s.model_errors||{}))parts.push(name+': '+error);
    if(s.storage_error)parts.push('사진 저장 오류: '+s.storage_error);
    el('aiStatus').textContent=parts.join(' · ');
    el('aiObjects').textContent=current==null?'':(s.hazards||[]).map(d=>(labels[d.label]||d.label)+' ['+d.model+' #'+(d.track_id==null?'추적 대기':d.track_id)+'] '+Math.round(d.confidence*100)+'%'+(d.stable?'':' (확인 중)')).join(' / ')+(s.person_detected?' · 사람 함께 검출':'');
  }catch(e){el('aiLevel').textContent='분석 상태 조회 실패';el('aiLevel').style.color='#b7c9d9';el('aiObjects').textContent='';el('aiStatus').textContent=e.message}finally{setTimeout(status,500)}}
  const ids=new Set(); let oldest=null, historyBusy=false;
  function add(event, older){if(ids.has(event.id))return;ids.add(event.id);oldest=oldest==null?event.timestamp:Math.min(oldest,event.timestamp);
    const card=document.createElement('article'), link=document.createElement('a'), img=document.createElement('img'), text=document.createElement('p');
    link.href=event.photo;link.target='_blank';link.rel='noopener';img.src=event.photo;img.loading='lazy';img.alt='발견 당시 사진';img.style.cssText='width:100%;aspect-ratio:4/3;object-fit:contain;border-radius:8px;background:#070b10';link.append(img);
    text.className='small';text.textContent=(event.mode?event.mode+' · ':'')+levels[event.level]+' · '+new Date(event.time).toLocaleString()+' · '+event.detections.map(d=>labels[d.label]||d.label).join(', ');
    card.append(link,text);if(older)el('aiHistory').append(card);else el('aiHistory').prepend(card);
    // Bound live DOM work so history cannot grow without limit beside controls.
    while(el('aiHistory').children.length>120){const edge=older?el('aiHistory').firstElementChild:el('aiHistory').lastElementChild;edge.remove()}
  }
  async function history(older=false){if(historyBusy)return;historyBusy=true;try{const url='/detections/history'+(older&&oldest!=null?'?before='+oldest:'');const data=await get(url);const events=data.events||[];for(const e of (older?events:[...events].reverse()))add(e,older);el('aiHistoryStatus').textContent=ids.size?'최근 사진을 표시합니다.':'아직 저장된 발견 사진이 없습니다.';if(older&&events.length===0)el('aiHistoryStatus').textContent='이전 사진이 없습니다.';}catch(e){el('aiHistoryStatus').textContent='이력 조회 실패: '+e.message}finally{historyBusy=false}}
  el('aiMore').onclick=()=>history(true);status();history();setInterval(()=>history(),2500);
})();
