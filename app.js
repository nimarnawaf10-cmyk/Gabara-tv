const DB="https://gabara-tv-default-rtdb.europe-west1.firebasedatabase.app";
let cats={},chs={},curCat="all",curCh=null,hls=null,lowNet=true,tries=0;
const $=id=>document.getElementById(id),vid=$("videoPlayer"),ph=$("placeholder"),npT=$("npTitle"),npS=$("npStatus"),dot=$("liveDot");
const esc=s=>String(s||"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

const qBar=document.createElement("div");
qBar.style.cssText="display:flex;gap:6px;flex-wrap:wrap;padding:8px 12px;background:#0d1322;border-top:1px solid #25324d;align-items:center";
document.querySelector(".player-card").appendChild(qBar);

function drawQ(levels){
  let h=`<button data-low="1" style="padding:5px 10px;border-radius:8px;border:1px solid #25324d;font-size:11px;font-weight:700;cursor:pointer;background:${lowNet?"#10b981":"#1a2336"};color:${lowNet?"#070b14":"#f8fafc"}">📉 نت ضعيف ${lowNet?"(مفعّل)":""}</button>`;
  if(levels&&levels.length>1){
    h+=`<button data-lv="-1" style="padding:5px 9px;border-radius:8px;border:1px solid #25324d;font-size:11px;background:#1a2336;color:#94a3b8;cursor:pointer">تلقائي</button>`;
    levels.forEach((l,i)=>{
      const lb=l.height?l.height+"p":("دقة "+(i+1));
      const act=hls&&hls.currentLevel===i;
      h+=`<button data-lv="${i}" style="padding:5px 9px;border-radius:8px;border:1px solid #25324d;font-size:11px;font-weight:700;cursor:pointer;background:${act?"#f59e0b":"#1a2336"};color:${act?"#070b14":"#f8fafc"}">${lb}</button>`;
    });
  }
  qBar.innerHTML=h;
}
drawQ();

qBar.onclick=e=>{
  const b=e.target.closest("button");if(!b)return;
  if(b.dataset.low){
    lowNet=!lowNet;
    if(hls){hls.currentLevel=lowNet?0:-1;hls.autoLevelCapping=lowNet?0:-1;}
    drawQ(hls?hls.levels:null);return;
  }
  if(b.dataset.lv!==undefined&&hls){
    lowNet=false;
    hls.autoLevelCapping=-1;hls.currentLevel=Number(b.dataset.lv);
    drawQ(hls.levels);
  }
};

async function load(){
  npS.textContent="جاري التحديث...";
  try{
    const[a,b]=await Promise.all([fetch(DB+"/categories.json"),fetch(DB+"/channels.json")]);
    if(!a.ok||!b.ok)throw 0;
    cats=await a.json()||{};chs=await b.json()||{};
    drawCats();drawChs();
    if(!curCh)npS.textContent="اختر قسماً أو قناة";
  }catch{
    $("channelsGrid").innerHTML='<div class="empty-box">تعذر الاتصال، اضغط تحديث 🔄</div>';
    npS.textContent="خطأ بالاتصال";
  }
}

function drawCats(){
  let h=`<button class="cat-pill ${curCat==="all"?"active":""}" data-cat="all">📺 الكل (${Object.keys(chs).length})</button>`;
  for(const[id,c]of Object.entries(cats)){
    const n=Object.values(chs).filter(x=>x.categoryId===id).length;
    h+=`<button class="cat-pill ${curCat===id?"active":""}" data-cat="${esc(id)}">${esc(c.icon||"📺")} ${esc(c.name)} (${n})</button>`;
  }
  $("catsBar").innerHTML=h;
}

function drawChs(){
  const q=$("searchInput").value.trim().toLowerCase(),c=cats[curCat];
  $("listLabel").textContent=curCat==="all"?"كل القنوات":(c?`${c.icon||"📺"} ${c.name}`:"القنوات");
  const list=Object.entries(chs).filter(([,x])=>(curCat==="all"||x.categoryId===curCat)&&(!q||(x.name||"").toLowerCase().includes(q)));
  $("chCounter").textContent=list.length+" قناة";
  if(!list.length){$("channelsGrid").innerHTML='<div class="empty-box">لا توجد قنوات حالياً</div>';return;}
  $("channelsGrid").innerHTML=list.map(([id,x])=>{
    const ct=cats[x.categoryId],lg=x.logoUrl?`<img src="${esc(x.logoUrl)}" alt="" onerror="this.parentElement.textContent='📡'">`:"📡";
    return `<div class="ch-card ${id===curCh?"playing":""}" data-ch="${esc(id)}"><div class="ch-logo">${lg}</div><div class="ch-name">${esc(x.name)}</div><div class="ch-cat">${esc(ct?(ct.icon||"📺")+" "+ct.name:"عام")}</div></div>`;
  }).join("");
}

function play(id){
  const ch=chs[id];if(!ch||!ch.streamUrl)return;
  curCh=id;drawChs();ph.style.display="none";npT.textContent=ch.name;npS.textContent="جاري التشغيل بأقل دقة...";dot.classList.remove("active");
  if(hls){hls.destroy();hls=null;}
  vid.pause();vid.removeAttribute("src");vid.load();drawQ();
  const u=ch.streamUrl.trim();
  if(u.startsWith("http://")){npS.textContent="الرابط http قديم وغير مدعوم ⚠️";return;}
  if(u.toLowerCase().includes(".m3u8")&&!window.Hls&&tries<8){tries++;setTimeout(()=>play(id),400);return;}
  tries=0;
  if(u.toLowerCase().includes(".m3u8")&&window.Hls&&Hls.isSupported()){
    hls=new Hls({enableWorker:true,startLevel:0,autoLevelCapping:lowNet?0:-1,abrEwmaDefaultEstimate:150000,maxBufferLength:20,manifestLoadingTimeOut:15000});
    hls.loadSource(u);hls.attachMedia(vid);
    hls.on(Hls.Events.MANIFEST_PARSED,(_,d)=>{
      if(lowNet&&d.levels&&d.levels.length)hls.currentLevel=0;
      drawQ(hls.levels);
      npS.textContent="البث يعمل الآن";dot.classList.add("active");
      vid.play().catch(()=>{npS.textContent="اضغط ▶ لتشغيل الفيديو";});
    });
    hls.on(Hls.Events.ERROR,(_,d)=>{
      if(d.fatal){
        if(d.type===Hls.ErrorTypes.MEDIA_ERROR)hls.recoverMediaError();
        else{npS.textContent="القناة متوقفة من المصدر أو محمية ❌";dot.classList.remove("active");}
      }
    });
  }else{
    vid.src=u;vid.play().then(()=>{npS.textContent="البث يعمل الآن";dot.classList.add("active");}).catch(()=>{npS.textContent="تعذر التشغيل أو اضغط ▶";});
  }
  window.scrollTo({top:0,behavior:"smooth"});
}

$("catsBar").onclick=e=>{const b=e.target.closest("button[data-cat]");if(b){curCat=b.dataset.cat;drawCats();drawChs();}};
$("channelsGrid").onclick=e=>{const d=e.target.closest(".ch-card[data-ch]");if(d)play(d.dataset.ch);};
$("searchInput").oninput=drawChs;$("btnRefresh").onclick=load;load();
if("serviceWorker"in navigator)navigator.serviceWorker.register("./sw.js").catch(()=>{});
