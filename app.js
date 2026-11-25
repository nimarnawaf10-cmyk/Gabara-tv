const DB="https://gabara-tv-default-rtdb.europe-west1.firebasedatabase.app";
let cats={},chs={},curCat="all",curCh=null,hls=null,lowNet=true,tries=0,usedNative=false;
const $=id=>document.getElementById(id),vid=$("videoPlayer"),ph=$("placeholder"),npT=$("npTitle"),npS=$("npStatus"),dot=$("liveDot"),vw=document.querySelector(".video-wrap");
const esc=s=>String(s||"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

const wm=document.createElement("div");
wm.style.cssText="position:absolute;top:10px;right:10px;z-index:20;pointer-events:none;display:none;align-items:center;gap:6px;background:rgba(7,11,20,0.68);border:1px solid rgba(245,158,11,0.75);padding:4px 8px;border-radius:10px;backdrop-filter:blur(4px)";
wm.innerHTML=`<img src="file_00000000b57c8210bbc5324d422d131f.png" style="width:26px;height:26px;border-radius:6px;object-fit:cover"><span style="font-size:11px;font-weight:700;color:#f59e0b">Gabara TV</span>`;
vw.appendChild(wm);

const qBar=document.createElement("div");
qBar.style.cssText="display:flex;gap:6px;flex-wrap:wrap;padding:8px 12px;background:#0d1322;border-top:1px solid #25324d;align-items:center";
document.querySelector(".player-card").appendChild(qBar);

function getYtEmbed(u){
  try{
    const url=new URL(u),h=url.hostname.replace(/^www\./,"");
    if(h==="youtu.be"){const id=url.pathname.slice(1).split("/")[0];return id?`https://www.youtube.com/embed/${id}?autoplay=1&rel=0`:null;}
    if(h==="youtube.com"||h==="m.youtube.com"||h==="music.youtube.com"){
      if(url.pathname.startsWith("/embed/"))return u+(u.includes("?")?"&":"?")+"autoplay=1&rel=0";
      if(url.pathname==="/playlist"&&url.searchParams.get("list"))return `https://www.youtube.com/embed/videoseries?list=${url.searchParams.get("list")}&autoplay=1&rel=0`;
      const v=url.searchParams.get("v");if(v)return `https://www.youtube.com/embed/${v}?autoplay=1&rel=0`;
      const m=url.pathname.match(/^\/(live|shorts)\/([^/?]+)/);if(m)return `https://www.youtube.com/embed/${m[2]}?autoplay=1&rel=0`;
    }
  }catch{}
  return null;
}

function getLowIdx(levels){
  if(!levels||!levels.length)return 0;
  let best=0;
  for(let i=1;i<levels.length;i++){
    const a=levels[i].height||levels[i].bitrate||99999999,b=levels[best].height||levels[best].bitrate||99999999;
    if(a<b)best=i;
  }
  return best;
}

function drawQ(levels){
  let h=`<button data-low="1" style="padding:5px 10px;border-radius:8px;border:1px solid #25324d;font-size:11px;font-weight:700;cursor:pointer;background:${lowNet?"#10b981":"#1a2336"};color:${lowNet?"#070b14":"#f8fafc"}">📉 أخف جودة ${lowNet?"(مفعّل)":""}</button>`;
  if(levels&&levels.length>1){
    const sorted=levels.map((l,i)=>({i,h:l.height||0,b:l.bitrate||0})).sort((a,b)=>(a.h-b.h)||(a.b-b.b));
    h+=`<button data-lv="-1" style="padding:5px 9px;border-radius:8px;border:1px solid #25324d;font-size:11px;background:${!lowNet&&hls&&hls.currentLevel===-1?"#38bdf8":"#1a2336"};color:${!lowNet&&hls&&hls.currentLevel===-1?"#070b14":"#94a3b8"};cursor:pointer">تلقائي</button>`;
    sorted.forEach((x,n)=>{
      const lb=x.h?x.h+"p":("دقة "+(n+1)),act=hls&&hls.currentLevel===x.i;
      h+=`<button data-lv="${x.i}" style="padding:5px 9px;border-radius:8px;border:1px solid #25324d;font-size:11px;font-weight:700;cursor:pointer;background:${act?"#f59e0b":"#1a2336"};color:${act?"#070b14":"#f8fafc"}">${lb}</button>`;
    });
  }else if(levels&&levels.length===1){
    h+=`<span style="font-size:11px;color:#94a3b8">المصدر بدقة واحدة (${levels[0].height?levels[0].height+"p":"واحدة"})</span>`;
  }
  qBar.innerHTML=h;
}
drawQ();

qBar.onclick=e=>{
  const b=e.target.closest("button");if(!b)return;
  if(b.dataset.low){lowNet=!lowNet;if(hls){const li=getLowIdx(hls.levels);hls.autoLevelCapping=lowNet?li:-1;hls.currentLevel=lowNet?li:-1;}drawQ(hls?hls.levels:null);return;}
  if(b.dataset.lv!==undefined&&hls){lowNet=false;hls.autoLevelCapping=-1;hls.currentLevel=Number(b.dataset.lv);drawQ(hls.levels);}
};

async function load(){
  npS.textContent="جاري التحديث...";
  try{
    const[a,b]=await Promise.all([fetch(DB+"/categories.json"),fetch(DB+"/channels.json")]);
    if(!a.ok||!b.ok)throw 0;
    cats=await a.json()||{};chs=await b.json()||{};
    drawCats();drawChs();
    if(!curCh)npS.textContent="اختر قسماً أو قناة";
  }catch{$("channelsGrid").innerHTML='<div class="empty-box">تعذر الاتصال، اضغط تحديث 🔄</div>';npS.textContent="خطأ بالاتصال";}
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
    const ct=cats[x.categoryId],lg=x.logoUrl?`<img src="${esc(x.logoUrl)}" loading="lazy" decoding="async" alt="" onerror="this.parentElement.textContent='📡'">`:"📡";
    return `<div class="ch-card ${id===curCh?"playing":""}" data-ch="${esc(id)}"><div class="ch-logo">${lg}</div><div class="ch-name">${esc(x.name)}</div><div class="ch-cat">${esc(ct?(ct.icon||"📺")+" "+ct.name:"عام")}</div></div>`;
  }).join("");
}

function clearYt(){const y=$("ytPlayer");if(y)y.remove();}

function tryNative(u){
  usedNative=true;if(hls){hls.destroy();hls=null;}
  npS.textContent="جاري التجربة بالمشغل البديل...";vid.src=u;
  vid.play().then(()=>{npS.textContent="البث يعمل الآن";dot.classList.add("active");}).catch(()=>{npS.textContent="اضغط ▶ أو القناة متوقفة من المصدر";});
}

vid.onplaying=()=>{npS.textContent="البث يعمل الآن";dot.classList.add("active");};
vid.onerror=()=>{if(usedNative){npS.textContent="القناة متوقفة من المصدر أو محمية ❌";dot.classList.remove("active");}};

function play(id){
  const ch=chs[id];if(!ch||!ch.streamUrl)return;
  document.querySelectorAll(".ch-card[data-ch]").forEach(el=>el.classList.toggle("playing",el.dataset.ch===id));
  curCh=id;ph.style.display="none";npT.textContent=ch.name;dot.classList.remove("active");
  const nm=(ch.name||"").toLowerCase();
  wm.style.display=(nm.includes("gabara")||nm.includes("جبارة")||nm.includes("گابارا"))?"flex":"none";
  usedNative=false;if(hls){hls.destroy();hls=null;}
  vid.pause();vid.removeAttribute("src");vid.load();clearYt();
  const u=ch.streamUrl.trim(),yt=getYtEmbed(u);
  if(yt){
    vid.style.display="none";qBar.style.display="none";
    const f=document.createElement("iframe");
    f.id="ytPlayer";f.src=yt;f.allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share";
    f.referrerPolicy="strict-origin-when-cross-origin";f.allowFullscreen=true;
    f.style.cssText="width:100%;height:100%;border:0;display:block";
    vw.appendChild(f);
    npS.textContent="البث يعمل الآن (من يوتيوب)";dot.classList.add("active");
    window.scrollTo({top:0,behavior:"smooth"});return;
  }
  vid.style.display="block";qBar.style.display="flex";drawQ();npS.textContent="جاري الاتصال بالبث...";
  if(u.toLowerCase().includes(".m3u8")&&!window.Hls&&tries<8){tries++;setTimeout(()=>play(id),400);return;}
  tries=0;
  if(u.toLowerCase().includes(".m3u8")&&window.Hls&&Hls.isSupported()){
    hls=new Hls({enableWorker:true,startLevel:0,abrEwmaDefaultEstimate:120000,maxBufferLength:45,maxMaxBufferLength:90,backBufferLength:10,fragLoadingTimeOut:25000,fragLoadingMaxRetry:5,manifestLoadingMaxRetry:3});
    hls.loadSource(u);hls.attachMedia(vid);
    hls.on(Hls.Events.MANIFEST_PARSED,(_,d)=>{
      const li=getLowIdx(d.levels);
      if(lowNet&&d.levels&&d.levels.length){hls.autoLevelCapping=li;hls.currentLevel=li;}
      drawQ(hls.levels);vid.play().catch(()=>{npS.textContent="اضغط ▶ لتشغيل الفيديو";});
    });
    hls.on(Hls.Events.ERROR,(_,d)=>{if(d.fatal){if(d.type===Hls.ErrorTypes.MEDIA_ERROR)hls.recoverMediaError();else tryNative(u);}});
  }else{tryNative(u);}
  window.scrollTo({top:0,behavior:"smooth"});
}

$("catsBar").onclick=e=>{const b=e.target.closest("button[data-cat]");if(b){curCat=b.dataset.cat;drawCats();drawChs();}};
$("channelsGrid").onclick=e=>{const d=e.target.closest(".ch-card[data-ch]");if(d)play(d.dataset.ch);};
$("searchInput").oninput=drawChs;$("btnRefresh").onclick=load;load();
if("serviceWorker"in navigator)navigator.serviceWorker.register("./sw.js").catch(()=>{});
