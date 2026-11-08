// Shared editable layouts for the live presentation and local exports.
import fs from 'node:fs/promises';
import path from 'node:path';
export async function author(presentation, root) {
  const deck = JSON.parse(await fs.readFile(path.join(root, 'docs/presentation/deck.json'), 'utf8'));
  presentation.theme.defaultFont = 'Arial';
  for (const [i, d] of deck.slides.entries()) {
    const slide = presentation.slides.add();
    slide.setViewportSize(1280,720);
    const ink=d.dark?'#F5F0E8':'#252321', muted=d.dark?'#D6C8B9':'#625C56', accent=d.dark?'#E3A48C':'#9B3F2E';
    slide.background.fill=d.dark?'#252321':'#F5F0E8';
    function text(value,x,y,w,h,size=28,color=ink,serif=false,bold=false) {
      const s=slide.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
      s.text=value; s.text.style={fontSize:size,typeface:serif?'Georgia':'Arial',color,bold,insets:0,autoFit:'none',wrap:'square'}; return s;
    }
    text('РЕПЛИКА',64,30,250,28,17,accent,false,true);
    text(String(i+1).padStart(2,'0'),1150,30,66,28,17,muted);
    if(d.kind==='cover') {
      text(d.title,64,170,1110,150,118,ink,true);
      text(d.subtitle,70,370,1080,150,38,muted);
      text(d.foot,70,625,1100,35,18,accent);
    } else {
      const product=d.kind==='product';
      text(d.title,64,104,product?790:1150,150,product?52:60,ink,true);
      if(d.kind==='statement') {
        text(d.lead,70,288,1080,225,38,accent);
        text(d.body,70,548,1080,110,26,muted);
      } else if(product) {
        text(d.lead,70,300,740,250,32,accent);
        slide.images.add({path:path.join(root,d.image),alt:d.caption,fit:'contain',position:{left:900,top:100,width:250,height:550}});
        text(d.caption,70,610,750,55,21,muted);
      } else if(d.kind==='architecture') {
        d.steps.forEach(([n,t,b],j)=>{const y=260+j*78;text(n,70,y,85,44,30,accent);text(t,170,y,350,45,28,ink,false,true);text(b,545,y+3,650,50,25,muted);});
        text(d.body,70,610,1100,65,24,accent);
      } else if(d.kind==='models') {
        d.items.forEach(([r,m,b],j)=>{const y=263+j*88;text(r,70,y,245,70,24,accent);text(m,325,y,865,42,29,ink,false,true);text(b,325,y+40,865,45,24,muted);});
      } else if(d.kind==='metric'||d.kind==='limits') {
        text(d.metric,70,275,1120,125,d.kind==='limits'?72:108,accent,true);
        text(d.label,75,413,1110,68,34,ink);
        text(d.body,75,510,1110,95,27,muted);
        text(d.caption,75,635,1110,52,19,muted);
      } else if(d.kind==='evidence') {
        d.items.forEach(([n,b],j)=>{text(n,70+j*390,270,340,120,98,accent,true);text(b,75+j*390,410,315,110,27,ink);});
        text(d.body,75,580,1100,100,25,muted);
      }
    }
    slide.speakerNotes.text=d.notes;
  }
  return deck;
}
