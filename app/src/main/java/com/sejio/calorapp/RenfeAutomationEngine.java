package com.sejio.calorapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class RenfeAutomationEngine {
    private RenfeAutomationEngine() {
    }

    static String buildScript(
            List<String> dates,
            TicketPlan.Trip trip,
            boolean findAdjacentPartner,
            JSONObject requiredSeat
    ) {
        JSONArray displayDates = new JSONArray();
        for (String date : dates) {
            displayDates.put(toDisplayDate(date));
        }

        String originCode = trip.outbound ? "31412" : "31400";
        String originName = trip.outbound
                ? "A CORUÑA"
                : "SANTIAGO DE COMPOSTELA-DANIEL CASTELAO";
        String destinationCode = trip.outbound ? "31400" : "31412";
        String destinationName = trip.outbound
                ? "SANTIAGO DE COMPOSTELA-DANIEL CASTELAO"
                : "A CORUÑA";

        return "(function(){\n"
                + "const desired={dates:" + displayDates
                + ",departure:" + JSONObject.quote(trip.train.departure)
                + ",arrival:" + JSONObject.quote(trip.train.arrival)
                + ",trainType:" + JSONObject.quote(trip.train.service)
                + ",originCode:" + JSONObject.quote(originCode)
                + ",originName:" + JSONObject.quote(originName)
                + ",destinationCode:" + JSONObject.quote(destinationCode)
                + ",destinationName:" + JSONObject.quote(destinationName)
                + ",findAdjacentPartner:" + findAdjacentPartner
                + ",requiredSeat:" + (requiredSeat == null ? "null" : requiredSeat.toString())
                + "};\n"
                + PROTOCOL_SCRIPT
                + "})();";
    }

    private static String toDisplayDate(String storageDate) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(storageDate);
            return new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(date);
        } catch (Exception ignored) {
            return storageDate;
        }
    }

    /*
     * This script deliberately uses Renfe's own named form fields and DWR functions.
     * It does not depend on screen coordinates, translated button text or visual layout.
     */
    private static final String PROTOCOL_SCRIPT =
            "const result=(status,message,extra)=>JSON.stringify(Object.assign({status:status,message:message||''},extra||{}));\n"
                    + "const path=location.pathname;\n"
                    + "const norm=v=>(v||'').toString().normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').replace(/\\s+/g,' ').trim().toUpperCase();\n"
                    + "const timeKey=v=>(v||'').toString().replace(':','.').replace(/\\s/g,'');\n"
                    + "const body=norm(document.body&&document.body.innerText);\n"
                    + "const setValue=(selector,value)=>{const e=document.querySelector(selector);if(!e)return false;e.value=value;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery)jQuery(e).trigger('change');return true;};\n"
                    + "const submitForm=form=>{if(!form)return false;HTMLFormElement.prototype.submit.call(form);return true;};\n"
                    + "const isLogin=()=>!!document.querySelector('input[type=password]')||/login/i.test(path);\n"

                    + "if(path.endsWith('/buyFormalization.do'))return result('success','Formalización realizada correctamente.');\n"
                    + "if(body.includes('VERIFICACION EN DOS PASOS')||body.includes('CODIGO DE VERIFICACION'))return result('needs_login','Introduce el código de verificación de Renfe y pulsa Continuar.');\n"
                    + "if(isLogin())return result('needs_login','Inicia sesión en Renfe. Cuando termine la verificación, pulsa Continuar.');\n"

                    + "if(path.endsWith('/myPassesCard.do')){\n"
                    + " const controls=Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit],[onclick]'));\n"
                    + " const controlText=e=>norm((e.innerText||e.value||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('aria-label')||''));\n"
                    + " const controlCode=e=>(e.getAttribute('onclick')||'')+' '+(e.getAttribute('href')||'')+' '+(e.getAttribute('data-action')||'');\n"
                    + " const toDate=v=>{const m=(v||'').toString().match(/^(\\d{1,2})[\\/.-](\\d{1,2})[\\/.-](\\d{4})$/);if(!m)return null;const d=Number(m[1]),mo=Number(m[2]),y=Number(m[3]);const value=Date.UTC(y,mo-1,d);const check=new Date(value);return check.getUTCFullYear()===y&&check.getUTCMonth()===mo-1&&check.getUTCDate()===d?value:null;};\n"
                    + " const datesIn=v=>{const found=[];for(const m of (v||'').toString().matchAll(/(?:^|\\D)(\\d{1,2}[\\/.-]\\d{1,2}[\\/.-]\\d{4})(?=\\D|$)/g)){const value=toDate(m[1]);if(value!==null&&!found.includes(value))found.push(value);}return found;};\n"
                    + " const looksLikeOpener=e=>controlText(e).includes('NUEVA FORMALIZACION')||/NEW/i.test(controlCode(e));\n"
                    + " const passBox=e=>{let box=e,first=null;for(let level=0;level<10&&box;level++,box=box.parentElement){if(norm(box.innerText).includes('ABONO UNICO')){if(!first)first=box;const openers=Array.from(box.querySelectorAll('a,button,input[type=button],input[type=submit],[onclick]')).filter(looksLikeOpener);if(datesIn(box.innerText).length>=1&&openers.length===1)return box;}}return first;};\n"
                    + " const validityIn=v=>{const text=norm(v);const date='(\\\\d{1,2}[\\\\/.-]\\\\d{1,2}[\\\\/.-]\\\\d{4})';const range=new RegExp('(?:VIGENCIA|VALID[OA]|DESDE|INICIO)[^0-9]{0,50}'+date+'[\\\\s\\\\S]{0,100}?(?:HASTA|FIN|CADUC(?:A|IDAD)?)[^0-9]{0,50}'+date,'i').exec(text);if(range){const start=toDate(range[1]),end=toDate(range[2]);if(start!==null&&end!==null)return {start:Math.min(start,end),end:Math.max(start,end)};}const expiry=new RegExp('(?:FECHA[^0-9]{0,30})?CADUC(?:A|IDAD)?(?:[^0-9]{0,30}ABONO)?[^0-9]{0,50}'+date,'i').exec(text);if(expiry){const end=toDate(expiry[1]);if(end!==null)return {start:null,end:end};}const dates=datesIn(text);return dates.length===2?{start:Math.min(...dates),end:Math.max(...dates)}:null;};\n"
                    + " let candidates=controls.filter(e=>looksLikeOpener(e)&&(/384/.test(controlCode(e))||!!passBox(e)));\n"
                    + " if(!candidates.length){const labels=Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,td,th,strong,span,p,div')).filter(e=>{const t=norm(e.innerText);return t.includes('ABONO UNICO')&&t.length<180;});for(const label of labels){let box=label;for(let level=0;level<8&&box;level++,box=box.parentElement){const found=Array.from(box.querySelectorAll('a,button,input[type=button],input[type=submit],[onclick]')).filter(looksLikeOpener);if(found.length){candidates.push(...found);break;}}}}\n"
                    + " const uniqueCandidates=[];candidates=candidates.filter(e=>{const box=passBox(e),code=controlCode(e);if(uniqueCandidates.some(other=>passBox(other)===box&&controlCode(other)===code))return false;uniqueCandidates.push(e);return true;});\n"
                    + " let opener=null;\n"
                    + " if(candidates.length===1)opener=candidates[0];\n"
                    + " else if(candidates.length>1){\n"
                    + "  const target=toDate(desired.dates[0]);if(target===null)return result('needs_user','No puedo interpretar la fecha del billete para elegir entre los abonos disponibles.');\n"
                    + "  const matching=candidates.map(e=>({e:e,validity:validityIn((passBox(e)||e).innerText)})).filter(item=>item.validity&&target<=item.validity.end&&(item.validity.start===null||target>=item.validity.start));\n"
                    + "  if(matching.length===1)opener=matching[0].e;\n"
                    + "  else if(matching.length&&matching.every(item=>item.validity.start===null)){const earliest=Math.min(...matching.map(item=>item.validity.end));const best=matching.filter(item=>item.validity.end===earliest);if(best.length===1)opener=best[0].e;else return result('needs_user','Más de un Abono Único parece válido para el billete del '+desired.dates[0]+'. Elige el bono manualmente.');}\n"
                    + "  else if(matching.length)return result('needs_user','Más de un Abono Único parece válido para el billete del '+desired.dates[0]+'. Elige el bono manualmente.');\n"
                    + "  else return result('needs_user','Hay varios Abonos Únicos, pero ninguno muestra una vigencia compatible con el billete del '+desired.dates[0]+'. Elige el bono manualmente.');\n"
                    + " }\n"
                    + " if(opener){\n"
                    + "  let source=controlCode(opener);const decoder=document.createElement('textarea');decoder.innerHTML=source;source=decoder.value;\n"
                    + "  const quoted=Array.from(source.matchAll(/['\"]([^'\"]+)['\"]/g)).map(m=>m[1]);const packed=quoted.find(v=>v.startsWith('NEW&'));\n"
                    + "  if(packed&&typeof window.submitNew==='function')window.submitNew(packed);else opener.click();\n"
                    + "  return result('acted','Abriendo la nueva formalización con Renfe.');\n"
                    + " }\n"
                    + " const hasPass=body.includes('ABONO UNICO');\n"
                    + " return result('needs_user',hasPass?'Renfe muestra el Abono Único, pero no encuentro su botón Nueva formalización.':'No aparece ningún Abono Único en esta cuenta de Renfe.');\n"
                    + "}\n"

                    + "if(path.endsWith('/journeyFormalization.do')){\n"
                    + " const form=document.querySelector('#formBean')||document.forms.formBean;\n"
                    + " if(!form)return result('needs_user','Renfe no ha cargado el formulario de trayecto.');\n"
                    + " const choose=(selector,code)=>{const e=document.querySelector(selector);if(!e)return false;const option=Array.from(e.options||[]).find(o=>o.value===code||(o.value||'').includes(code));if(!option)return false;e.value=option.value;e.dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery)jQuery(e).trigger('change');return true;};\n"
                    + " if(!choose('#originJourneySelect',desired.originCode)||!choose('#destinJourneySelect',desired.destinationCode))return result('needs_user','No encuentro las estaciones del trayecto en el Abono Único.');\n"
                    + " setValue('[name=\"featuresDataPassesCard.originStation.cdgoEstacion\"]',desired.originCode);\n"
                    + " setValue('[name=\"featuresDataPassesCard.originStation.descEstacion\"]',desired.originName);\n"
                    + " setValue('[name=\"featuresDataPassesCard.destinStation.cdgoEstacion\"]',desired.destinationCode);\n"
                    + " setValue('[name=\"featuresDataPassesCard.destinStation.descEstacion\"]',desired.destinationName);\n"
                    + " const multi=desired.dates.length>1;\n"
                    + " const one=document.querySelector('#formalizationOne');const many=document.querySelector('#formalizationMulti');\n"
                    + " const check=(e,value)=>{if(!e)return;e.checked=value;e.dispatchEvent(new Event('change',{bubbles:true}));if(window.jQuery)jQuery(e).trigger('change');};\n"
                    + " check(one,!multi);check(many,multi);\n"
                    + " const holder=form.querySelector('[name=\"holderes[0].selected\"]');check(holder,true);\n"
                    + " setValue('#fecha1',multi?'':desired.dates[0]);setValue('#fecha2',multi?desired.dates.join(','):'');\n"
                    + " if(!setValue('#datesFormalization',desired.dates.join(',')))return result('needs_user','Falta el campo de fechas de Renfe.');\n"
                    + " if(!window.__sejioJourneyPreparedAt){window.__sejioJourneyPreparedAt=Date.now();return result('waiting','Preparando las fechas en Renfe.');}\n"
                    + " if(Date.now()-window.__sejioJourneyPreparedAt<250)return result('waiting','Preparando las fechas en Renfe.');\n"
                    + " if(window.__sejioJourneySubmitted)return result('waiting','Esperando que Renfe termine de enviar las fechas.');\n"
                    + " window.__sejioJourneySubmitted=true;\n"
                    + " if(!submitForm(form))return result('needs_user','No se ha podido enviar el formulario de fechas a Renfe.');\n"
                    + " return result('acted','Enviando '+desired.dates.length+' fecha(s) a Renfe.');\n"
                    + "}\n"

                    + "if(path.endsWith('/trainFormalization.do')){\n"
                    + " if(!Array.isArray(window.trainsList)||!window.trainsList.length){\n"
                    + "  if(typeof window.getTrainList==='function'&&!window.__sejioTrainRequested){window.__sejioTrainRequested=true;window.getTrainList();}\n"
                    + "  return result('waiting','Esperando la lista de trenes de Renfe.');\n"
                    + " }\n"
                    + " const train=window.trainsList.find(t=>t&&t.salida&&t.llegada&&timeKey(t.salida.horario)===timeKey(desired.departure)&&timeKey(t.llegada.horario)===timeKey(desired.arrival)&&(!desired.trainType||norm(t.tipoTren)===norm(desired.trainType)));\n"
                    + " if(!train)return result('train_unavailable','No aparece el tren '+desired.departure+' → '+desired.arrival+' ('+desired.trainType+') para estas fechas.');\n"
                    + " if(typeof window.continuar!=='function')return result('waiting','Esperando el gestor de formalización de Renfe.');\n"
                    + " const updated=window.continuar(train.cdgoTren);\n"
                    + " if(!updated)return result('needs_user','Renfe no ha aceptado el tren seleccionado. Revisa el aviso de la página.');\n"
                    + " const form=document.querySelector('#formBean')||document.forms.formBean;\n"
                    + " if(!submitForm(form))return result('needs_user','No encuentro el formulario de trenes de Renfe.');\n"
                    + " return result('acted','Tren aplicado al lote mediante DWR.');\n"
                    + "}\n"

                    + "if(path.endsWith('/detailsFormalization.do')){\n"
                    + " const form=document.querySelector('#formBean')||document.forms.formBean;\n"
                    + " if(!form)return result('needs_user','Renfe no ha cargado el formulario final.');\n"
                    + " const agreement=document.querySelector('#agreement');if(agreement)agreement.checked=true;\n"
                    + " const seat=form.querySelector('[name=seatPreference][value=\"SG\"]')||form.querySelector('[name=seatPreference]');if(seat){if(seat.type==='radio'||seat.type==='checkbox'){seat.checked=true;seat.dispatchEvent(new Event('change',{bubbles:true}));}else seat.value='SG';}\n"
                    + " if(!window.__sejioDetailsLoadedAt){window.__sejioDetailsLoadedAt=Date.now();return result('waiting','Esperando la validación del abono.');}\n"
                    + " if(Date.now()-window.__sejioDetailsLoadedAt<1600)return result('waiting','Esperando la validación del abono.');\n"
                    + " if(!window.__sejioDetailsSubmittedAt){\n"
                    + "  window.__sejioDetailsSubmittedAt=Date.now();const next=document.querySelector('#submitSiguiente');\n"
                    + "  if(next){next.click();return result('acted','Confirmando el lote en Renfe.');}\n"
                    + "  submitForm(form);return result('acted','Confirmando el lote en Renfe.');\n"
                    + " }\n"
                    + " if(Date.now()-window.__sejioDetailsSubmittedAt>20000)return result('needs_user','Renfe no ha completado la confirmación. Revisa los avisos de esta pantalla.');\n"
                    + " return result('waiting','Renfe está confirmando las formalizaciones.');\n"
                    + "}\n"

                    + "if(path.endsWith('/selectSeatFormalization.do')){\n"
                    + " if(typeof window.reservarPlazas!=='function'||typeof window.datosCochePlaza!=='function'||!Array.isArray(window.arrayPlazasSel))return result('waiting','Esperando el plano de asientos de Renfe.');\n"
                    + " if(window.enReserva||window.__sejioSeatSubmitted)return result('waiting','Renfe está reservando la plaza elegida.');\n"
                    + " if(!window.__sejioSeatLoadedAt){window.__sejioSeatLoadedAt=Date.now();return result('waiting','Cargando la disponibilidad de asientos.');}\n"
                    + " if(Date.now()-window.__sejioSeatLoadedAt<1200)return result('waiting','Cargando la disponibilidad de asientos.');\n"
                    + " if(Number(window.maxPlazas&&window.maxPlazas[0])!==1)return result('needs_user','La selección automática de asiento necesita procesar una fecha cada vez.');\n"
                    + " const allowedTypes=new Set(['V','X','P','C']);\n"
                    + " const seatInfo=s=>{try{const i=window.datosCochePlaza(s,s.getAttribute('pd'),0);return{seat:s.getAttribute('tp')||'',coach:(i[0]||'').toString(),seatClass:(i[2]||'').toString(),type:s.getAttribute('ti')||'',group:(i[4]||'').toString(),forward:s.getAttribute('rrp')!=='d'};}catch(e){return null;}};\n"
                    + " const sameSeat=(a,b)=>!!a&&!!b&&a.seat===b.seat&&a.coach===b.coach&&(!b.type||a.type===b.type);\n"
                    + " const radios=()=>Array.from(document.querySelectorAll('[name^=\"numeroVagon_\"]'));\n"
                    + " const coachPriority=info=>{const value=Number.parseInt((info&&info.coach||'').toString(),10);if(Number.isFinite(value))return -value*1000000000;const index=radios().findIndex(r=>(r.getAttribute('cdgoCoche')||'').toString()===(info&&info.coach||''));return -(index<0?9999:index+1)*1000000000;};\n"
                    + " const currentCoach=()=>{const r=radios().find(x=>x.checked);return r?(r.getAttribute('cdgoCoche')||'').toString():'';};\n"
                    + " const switchCoach=code=>{const r=radios().find(x=>(x.getAttribute('cdgoCoche')||'').toString()===code);if(!r)return false;if(window.enCambioCoche)return true;r.checked=true;r.dispatchEvent(new Event('change',{bubbles:true}));return true;};\n"
                    + " const visibleSeat=s=>{const car=s.parentElement;if(!car)return false;const style=getComputedStyle(car);return style.visibility!=='hidden'&&style.display!=='none';};\n"
                    + " const freeSeats=()=>Array.from(document.querySelectorAll('input[tp][ti][ez=\"0\"]')).filter(s=>allowedTypes.has(s.getAttribute('ti'))&&visibleSeat(s));\n"
                    + " const labelFor=s=>s.nextElementSibling&&s.nextElementSibling.tagName==='LABEL'?s.nextElementSibling:s;\n"
                    + " const rectFor=s=>{const r=labelFor(s).getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height};};\n"
                    + " const seatToken=s=>{const m=(s.getAttribute('tp')||'').toUpperCase().replace(/\\s+/g,'').match(/^(\\d+)([A-Z])?$/);return m?{row:Number(m[1]),letter:m[2]||''}:null;};\n"
                    + " const validPairNames=(a,b)=>{const ta=seatToken(a),tb=seatToken(b);if(!ta||!tb)return false;if(ta.letter||tb.letter){if(!ta.letter||!tb.letter||ta.row!==tb.row)return false;const letters=[ta.letter,tb.letter].sort().join('');return['AB','CD','DE'].includes(letters);}return Math.abs(ta.row-tb.row)===1&&Math.max(ta.row,tb.row)%2===0;};\n"
                    + " const elementMeta=e=>norm(Array.from(e&&e.attributes||[]).map(a=>a.name+' '+a.value).join(' ')+' '+(e&&e.children&&e.children.length===0?e.textContent||'':''));\n"
                    + " const hasTableWord=e=>{const meta=elementMeta(e);return meta.includes('MESA')||/(^|[^A-Z])TABLE([^A-Z]|$)/.test(meta);};\n"
                    + " const tableCache=new WeakMap();\n"
                    + " const tableFacing=s=>{if(tableCache.has(s))return tableCache.get(s);const label=labelFor(s);let found=hasTableWord(s)||hasTableWord(label);const car=s.parentElement,seatRect=rectFor(s);if(!found&&car){found=Array.from(car.querySelectorAll('*')).some(e=>{if(e===s||e===label||e.contains(s)||!hasTableWord(e))return false;const style=getComputedStyle(e);if(style.visibility==='hidden'||style.display==='none')return false;const r=e.getBoundingClientRect();if(r.width<=0||r.height<=0||r.width>180||r.height>180)return false;return Math.hypot(seatRect.x-(r.left+r.width/2),seatRect.y-(r.top+r.height/2))<=120;});}tableCache.set(s,found);return found;};\n"
                    + " const adjacentScore=(a,b)=>{if(!validPairNames(a,b))return null;const ia=seatInfo(a),ib=seatInfo(b);if(!ia||!ib||ia.coach!==ib.coach||ia.forward!==ib.forward)return null;const ra=rectFor(a),rb=rectFor(b),dx=Math.abs(ra.x-rb.x),dy=Math.abs(ra.y-rb.y),distance=Math.hypot(dx,dy);const aligned=(dx<=Math.max(14,Math.max(ra.w,rb.w)*0.7)||dy<=Math.max(14,Math.max(ra.h,rb.h)*0.7))&&distance<=90;const tablePenalty=tableFacing(a)||tableFacing(b)?1000000:0;return coachPriority(ia)-tablePenalty+(ia.forward?100000:0)+(aligned?10000:0)-distance;};\n"
                    + " const bestPair=seats=>{let best=null;for(let i=0;i<seats.length;i++){for(let j=i+1;j<seats.length;j++){const score=adjacentScore(seats[i],seats[j]);if(score!==null&&(!best||score>best.score))best={first:seats[i],second:seats[j],score:score};}}return best;};\n"
                    + " const bestSingle=seats=>{let best=null;for(const seat of seats){const info=seatInfo(seat);if(!info)continue;const score=coachPriority(info)+(tableFacing(seat)?-1000000:0)+(info.forward?100000:0);if(!best||score>best.score)best={first:seat,second:null,score:score};}return best;};\n"
                    + " const chooseAndSubmit=(seat,partner)=>{const chosen=seatInfo(seat);const partnerInfo=partner?seatInfo(partner):null;if(!chosen)return result('needs_user','No puedo leer los datos de la plaza seleccionada.');seat.click();if(seat.getAttribute('ez')!=='1')seat.dispatchEvent(new Event('change',{bubbles:true}));if(seat.getAttribute('ez')!=='1'||!window.arrayPlazasSel[0].includes(seat))return result('needs_user','Renfe no ha aceptado la plaza '+chosen.seat+'.');window.__sejioSeatSubmitted=true;setTimeout(()=>window.reservarPlazas(),150);const direction=chosen.forward?' a favor de la marcha':' en sentido contrario';const table=tableFacing(seat)?' junto a una mesa':'';const extra=partnerInfo?{partnerSeat:partnerInfo}:{};return result('acted','Plaza '+chosen.seat+' del coche '+chosen.coach+direction+table+'. Reservándola en Renfe.',extra);};\n"
                    + " const current=currentCoach();if(window.enCambioCoche)return result('waiting','Cargando otro coche para buscar asientos.');\n"
                    + " const available=freeSeats();\n"
                    + " if(desired.requiredSeat){\n"
                    + "  if(current!==desired.requiredSeat.coach){if(!switchCoach(desired.requiredSeat.coach))return result('needs_user','No encuentro el coche '+desired.requiredSeat.coach+' de la plaza contigua.');return result('waiting','Abriendo el coche '+desired.requiredSeat.coach+' para reservar la plaza contigua.');}\n"
                    + "  const exact=available.find(s=>sameSeat(seatInfo(s),desired.requiredSeat));if(!exact)return result('needs_user','La plaza contigua '+desired.requiredSeat.seat+' del coche '+desired.requiredSeat.coach+' ya no está libre. He detenido el proceso para no separaros.');\n"
                    + "  return chooseAndSubmit(exact,null);\n"
                    + " }\n"
                    + " window.__sejioVisitedCoaches=window.__sejioVisitedCoaches||{};if(current)window.__sejioVisitedCoaches[current]=true;\n"
                    + " const candidate=desired.findAdjacentPartner?bestPair(available):bestSingle(available);\n"
                    + " if(candidate&&(!window.__sejioBestSeatCandidate||candidate.score>window.__sejioBestSeatCandidate.score))window.__sejioBestSeatCandidate={first:seatInfo(candidate.first),second:candidate.second?seatInfo(candidate.second):null,score:candidate.score};\n"
                    + " const nextCoach=radios().find(r=>!window.__sejioVisitedCoaches[(r.getAttribute('cdgoCoche')||'').toString()]);\n"
                    + " if(nextCoach){const code=(nextCoach.getAttribute('cdgoCoche')||'').toString();switchCoach(code);return result('waiting','Comparando plazas disponibles en el coche '+code+'.');}\n"
                    + " const best=window.__sejioBestSeatCandidate;if(!best)return result('needs_user',desired.findAdjacentPartner?'No hay una pareja válida disponible en este tren.':'No hay plazas ordinarias disponibles en este tren.');\n"
                    + " if(current!==best.first.coach){switchCoach(best.first.coach);return result('waiting','Abriendo el coche con la mejor alternativa disponible.');}\n"
                    + " const first=available.find(s=>sameSeat(seatInfo(s),best.first));const second=best.second?available.find(s=>sameSeat(seatInfo(s),best.second)):null;\n"
                    + " if(!first||(best.second&&!second))return result('needs_user','La mejor alternativa de asiento ha dejado de estar disponible. Revisa el plano antes de continuar.');\n"
                    + " return chooseAndSubmit(first,second);\n"
                    + "}\n"

                    + "if(path.endsWith('/home.do')||path.endsWith('/privateArea.do')||path.endsWith('/loginSuccess.do')){location.href='/vol/myPassesCard.do';return result('acted','Abriendo Mis abonos.');}\n"
                    + "const directPass=Array.from(document.querySelectorAll('a')).find(a=>(a.getAttribute('href')||'').includes('myPassesCard.do'));if(directPass){location.href=directPass.href;return result('acted','Abriendo Mis abonos.');}\n"
                    + "if(location.hostname==='venta.renfe.com'||location.hostname.endsWith('.renfe.com')){location.href='/vol/myPassesCard.do';return result('acted','Abriendo Mis abonos automáticamente.');}\n"
                    + "return result('needs_user','No reconozco esta pantalla de Renfe.');\n";
}
