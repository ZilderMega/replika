"""Build an offline animated presentation from the same reviewed slide copy."""
import base64
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/presentation/replika-defense.html'

def esc(s):
    return html.escape(s).replace('\n', '<br>')

def main():
    deck = json.loads((ROOT / 'docs/presentation/deck.json').read_text('utf-8'))
    font = base64.b64encode((ROOT / 'app/src/main/res/font/manrope.ttf').read_bytes()).decode()
    sections = []
    for i, d in enumerate(deck['slides']):
        kind = d['kind']
        chunks = [f'<header><b>РЕПЛИКА</b><span>{i+1:02}</span></header>']
        if kind == 'cover':
            chunks += [f'<div class="cover"><h1>{esc(d["title"])}</h1><p>{esc(d["subtitle"])}</p></div>', f'<footer>{esc(d["foot"])}</footer>']
        else:
            chunks += [f'<h2>{esc(d["title"])}</h2>']
            if kind in ('statement', 'product'):
                chunks += [f'<div class="lead">{esc(d["lead"])}</div>']
                if kind == 'product':
                    image = base64.b64encode((ROOT / d['image']).read_bytes()).decode()
                    chunks += [f'<img class="screen" src="data:image/png;base64,{image}" alt="{esc(d["caption"])}">', f'<footer>{esc(d["caption"])}</footer>']
                else:
                    chunks += [f'<p class="body">{esc(d["body"])}</p>']
            elif kind == 'architecture':
                chunks += ['<div class="flow">' + ''.join(f'<div class="step"><em>{n}</em><b>{esc(t)}</b><span>{esc(b)}</span></div>' for n,t,b in d['steps']) + '</div>', f'<footer>{esc(d["body"])}</footer>']
            elif kind == 'models':
                chunks += ['<div class="models">' + ''.join(f'<div class="step"><em>{esc(r)}</em><section><b>{esc(m)}</b><p>{esc(b)}</p></section></div>' for r,m,b in d['items']) + '</div>']
            elif kind in ('metric', 'limits'):
                chunks += [f'<div class="metric">{esc(d["metric"])}</div><div class="label">{esc(d["label"])}</div><p class="body">{esc(d["body"])}</p><footer>{esc(d["caption"])}</footer>']
            elif kind == 'evidence':
                chunks += ['<div class="evidence">' + ''.join(f'<section><em>{esc(n)}</em><p>{esc(b)}</p></section>' for n,b in d['items']) + '</div>', f'<p class="body">{esc(d["body"])}</p>']
        chunks += [f'<aside class="notes">{esc(d["notes"])}</aside>']
        sections += [f'<article class="slide {kind} {"dark" if d.get("dark") else ""}" aria-label="Слайд {i+1}">' + ''.join(chunks) + '</article>']
    css = '''
*{box-sizing:border-box} :root{--paper:#f5f0e8;--ink:#252321;--accent:#9b3f2e;--muted:#625c56}
body{margin:0;background:#171615;color:var(--ink);font-family:Manrope,Arial,sans-serif;overflow:hidden}
#stage{width:1280px;height:720px;position:absolute;left:50%;top:50%;transform:translate(-50%,-50%) scale(var(--scale,1));transform-origin:center}
.slide{position:absolute;inset:0;background:var(--paper);padding:30px 64px;visibility:hidden;opacity:0;overflow:hidden;transition:opacity .65s ease,transform .85s cubic-bezier(.16,1,.3,1);transform:translateX(70px) scale(.985)}
.slide.active{visibility:visible;opacity:1;transform:none;z-index:2}.slide.leaving{visibility:visible;transform:translateX(-90px) scale(.97);opacity:0;z-index:1}
.dark{--paper:#252321;--ink:#f5f0e8;--muted:#d6c8b9;--accent:#e3a48c}
header{display:flex;justify-content:space-between;font-size:17px;color:var(--accent);letter-spacing:1.5px}header span{color:var(--muted)}
h1,h2,.metric,.evidence em{font-family:Georgia,'Times New Roman',serif;font-weight:400;margin:0;letter-spacing:-2px}
h1{font-size:118px;line-height:1.1}.cover .cover{position:absolute;left:64px;top:170px}.cover p{font-size:38px;line-height:1.35;color:var(--muted);margin-top:45px}
h2{font-size:60px;line-height:1.08;position:absolute;left:64px;top:104px;width:1150px}.product h2{font-size:52px;width:790px}
.lead{position:absolute;left:70px;top:290px;font-size:38px;line-height:1.5;color:var(--accent)}.product .lead{font-size:32px;line-height:1.7;top:294px}
.body{position:absolute;left:70px;top:540px;width:1090px;font-size:26px;line-height:1.4;color:var(--muted);margin:0}
footer{position:absolute;bottom:40px;left:70px;right:70px;font-size:19px;line-height:1.35;color:var(--muted)}.cover footer{color:var(--accent)}
.screen{position:absolute;left:900px;top:100px;width:250px;height:550px;object-fit:contain}
.flow,.models{position:absolute;top:260px;left:70px;right:70px}.step{display:flex;align-items:start;gap:26px;min-height:79px;font-size:25px}.step em{font-style:normal;color:var(--accent);width:75px;flex-shrink:0}.step>b{width:350px;font-size:28px}.step>span{color:var(--muted);padding-top:3px}.models .step{min-height:88px}.models em{width:230px;font-size:24px}.models b{font-size:29px}.models p{font-size:24px;margin:8px 0 0;color:var(--muted)}
.metric{position:absolute;top:275px;left:70px;font-size:108px;color:var(--accent)}.limits .metric{font-size:72px}.label{position:absolute;top:415px;left:75px;font-size:34px}.metric~.body{top:510px;font-size:27px}.metric~footer{bottom:32px}
.evidence{position:absolute;left:70px;top:265px;display:flex;gap:70px}.evidence section{width:320px}.evidence em{font-size:98px;font-style:normal;color:var(--accent)}.evidence p{font-size:27px;line-height:1.35;margin:28px 0 0}.evidence~.body{top:580px}
.notes{display:none;position:absolute;left:50px;right:50px;bottom:35px;background:var(--ink);color:var(--paper);padding:25px;font-size:22px;line-height:1.5;z-index:9;box-shadow:0 5px 35px #0005}.show-notes .active .notes{display:block}
.active h1,.active h2{animation:reveal 1.05s cubic-bezier(.16,1,.3,1) both}.active .lead,.active .metric,.active .flow,.active .models,.active .evidence{animation:reveal 1.05s .12s cubic-bezier(.16,1,.3,1) both}.active .screen{animation:screen 1.2s .15s cubic-bezier(.16,1,.3,1) both}.active .body,.active footer,.active .cover p,.active .label{animation:reveal .95s .3s cubic-bezier(.16,1,.3,1) both}.active .step:nth-child(2){animation:reveal 1s .22s both}.active .step:nth-child(3){animation:reveal 1s .32s both}.active .step:nth-child(4){animation:reveal 1s .42s both}
@keyframes reveal{from{opacity:0;transform:translateY(34px);filter:blur(6px)}to{opacity:1;transform:none;filter:blur(0)}}@keyframes screen{from{opacity:0;transform:translateY(50px) rotate(3deg) scale(.94)}to{opacity:1;transform:none}}
#controls{position:fixed;bottom:8px;left:50%;transform:translateX(-50%);display:flex;gap:10px;align-items:center;z-index:20;opacity:.25;transition:opacity .2s;color:#fff;font:13px Arial}#controls:hover,#controls:focus-within{opacity:1}button{border:1px solid #ffffff40;background:#252321;color:#f5f0e8;padding:7px 12px;cursor:pointer;font:inherit}button:focus-visible{outline:2px solid #e3a48c}#progress{position:fixed;bottom:0;left:0;height:3px;background:#9b3f2e;width:0;transition:width .8s}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
@media print{body{overflow:visible;background:#fff}#stage{position:static;transform:none;width:auto;height:auto}.slide{position:relative;visibility:visible;opacity:1;transform:none;width:1280px;height:720px;break-after:page}#controls,#progress{display:none}.notes{display:none!important}}
'''
    js = '''
const slides=[...document.querySelectorAll('.slide')];let current=0;
function resize(){document.documentElement.style.setProperty('--scale',Math.min(innerWidth/1280,innerHeight/720))}addEventListener('resize',resize);resize();
function go(n){n=Math.max(0,Math.min(slides.length-1,n));if(n===current&&slides[n].classList.contains('active'))return;const old=slides[current];old.classList.remove('active');old.classList.add('leaving');setTimeout(()=>old.classList.remove('leaving'),900);current=n;slides[n].classList.remove('leaving');slides[n].classList.add('active');document.getElementById('count').textContent=`${n+1} / ${slides.length}`;document.getElementById('progress').style.width=`${100*(n+1)/slides.length}%`;history.replaceState(null,'','#'+(n+1))}
document.getElementById('next').onclick=()=>go(current+1);document.getElementById('prev').onclick=()=>go(current-1);document.getElementById('full').onclick=()=>document.fullscreenElement?document.exitFullscreen():document.documentElement.requestFullscreen();document.getElementById('notes').onclick=()=>document.body.classList.toggle('show-notes');
addEventListener('keydown',e=>{if(['ArrowRight','PageDown',' ','ArrowLeft','PageUp','Home','End'].includes(e.key))e.preventDefault();if(['ArrowRight','PageDown',' '].includes(e.key))go(current+1);if(['ArrowLeft','PageUp'].includes(e.key))go(current-1);if(e.key==='Home')go(0);if(e.key==='End')go(slides.length-1);if(e.key.toLowerCase()==='f')document.getElementById('full').click();if(e.key.toLowerCase()==='n')document.getElementById('notes').click()});
let startX=0;addEventListener('touchstart',e=>{startX=e.changedTouches[0].screenX},{passive:true});addEventListener('touchend',e=>{const dx=e.changedTouches[0].screenX-startX;if(Math.abs(dx)>70)go(current+(dx<0?1:-1))},{passive:true});go((parseInt(location.hash.slice(1))||1)-1);
'''
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Реплика · Защита проекта</title><style>@font-face{font-family:Manrope;src:url(data:font/ttf;base64,'+font+')}'+css+'</style><main id="stage">'+''.join(sections)+'</main><nav id="controls" aria-label="Управление презентацией"><button id="prev" aria-label="Предыдущий слайд">Назад</button><span id="count"></span><button id="next">Далее</button><button id="notes">Заметки (N)</button><button id="full">Полный экран (F)</button></nav><div id="progress"></div><script>'+js+'</script></html>',encoding='utf-8')
    print(OUT)

if __name__ == '__main__':
    main()
