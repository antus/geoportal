// SPDX-FileCopyrightText: 2026 Massimo Antonini

/* Geoportal mockup: the pages of the four extensions (catalog, maps, dashboards, processes), the
   public catalog, the geodata sections of the configuration, and their dialogs. Every screen is a
   function of the state S that returns HTML. */

/* Which resource is open, and whether whoever looks may edit it. A User edits only what is theirs
   or was given to them for editing; manager and administrator edit everything. The view is always
   the entry point: who may edit finds the Modifica/Vista switch in the band. */
const USER_EDITS = { map: ['m1', 'm3', 'm4'], dash: ['b2'], proc: ['p1', 'p3'] };
/* The «Consultazione e modifica» profile sees only what is shared with it. */
const COLLAB_SEES = { cat: ['d1', 'd2', 'd4', 'd6'], maps: ['m1', 'm2'], dash: ['b1'], proc: ['p1'] };
const COLLAB_EDITS = { map: ['m1'], dash: [], proc: [] };
function vis(S, key, list) { return S.role === 'collab' ? list.filter(x => COLLAB_SEES[key].includes(x.id)) : list; }
function curRes(S, kind) {
  const list = kind === 'map' ? MAPS : kind === 'dash' ? DASHES : PROCS;
  const id = S.resId && S.resId[kind];
  return list.find(x => x.id === id) || list[0];
}
function canEdit(S, kind) {
  if (S.role === 'collab') return COLLAB_EDITS[kind].includes(curRes(S, kind).id);
  return S.role !== 'user' || USER_EDITS[kind].includes(curRes(S, kind).id);
}
/* The trash is a page of Mosaikit: the button leads there. */
function trashBtn(S) { return S.role === 'collab' ? '' : `<button type="button" class="btn" data-go="trash" aria-label="Cestino" title="Cestino">${ic('trash', 15)}<span>Cestino</span></button>`; }
function favBtn(S, m) { const on = (S.fav || []).includes(m.id); return `<button type="button" class="ib fav ${on ? 'on' : ''}" data-act="fav" data-v="${m.id}" aria-pressed="${on}" aria-label="${on ? 'Togli dai preferiti' : 'Aggiungi ai preferiti'}: ${m.t}" title="${on ? 'Nei preferiti' : 'Aggiungi ai preferiti'}">${ic('star', 15)}</button>`; }
function modeSwitch(S, kind, editing) {
  const r = { map: ['map-edit', 'map-view'], dash: ['dash-edit', 'dash-view'], proc: ['process-edit', 'process-view'] }[kind];
  const id = curRes(S, kind).id, fav = (S.fav || []).includes(id);
  const extra = `<button type="button" class="ib fav ${fav ? 'on' : ''}" data-act="fav" data-v="${id}" aria-pressed="${fav}" aria-label="${fav ? 'Togli dai preferiti' : 'Aggiungi ai preferiti'}" title="${fav ? 'Nei preferiti' : 'Aggiungi ai preferiti'}">${ic('star', 16)}</button>${kind === 'proc' ? '' : `<button type="button" class="ib" data-act="versions" aria-label="Versioni" title="Versioni">${ic('history', 16)}</button>`}`;
  if (!canEdit(S, kind)) return extra + `<span class="pill ro" title="Non hai il permesso di modificare">${ic('lock', 12)}Sola lettura</span>`;
  return extra + `<div class="seg" role="group" aria-label="Modalità">${btn('Modifica', { kind: editing ? 'on' : '', go: r[0] }).replace('<button', `<button aria-pressed="${!!editing}"`)}${btn('Vista', { kind: editing ? '' : 'on', go: r[1] }).replace('<button', `<button aria-pressed="${!editing}"`)}</div>`;
}
function lhead(S, o) {
  const create = o.create ? `<button type="button" class="btn pri" data-act="${o.create.act}">${ic('plus', 15)}<span>${o.create.label}</span></button>` : '';
  return `<header class="lhead ${o.compact ? 'lh-compact' : ''}"><div class="lh-r1"><div class="lh-t"><h1>${o.title}</h1><span class="lh-n num">${o.total}</span></div><div class="lh-a">${create}</div></div></header>`;
}
function stPill(st, txt) { return pill(txt, st === 'ok' ? 'ok' : st === 'bad' ? 'bad' : 'info'); }

/* ── Panoramica: what Geoportal brings to the launcher of Mosaikit ─────
   The tiles of the four apps, the work to pick up again and the alerts. The other panels of the
   Overview (notifications, storage) belong to the Mosaikit mockup. */
function secHome(S) {
  setSub(null);
  const R = ROLES[S.role], first = R.who.split(' ')[0], collab = S.role === 'collab';
  const recent = collab ? [['map', 'Rete di monitoraggio, fiume Lete', 'Mappa · in scrittura', 'map-view'], ['dash', 'Sala operativa traffico', 'Cruscotto · in lettura', 'dash-view']]
    : [['map', 'Rete di monitoraggio, fiume Lete', 'Mappa · Elena sta modificando', 'map-view'], ['database', 'Flussi di traffico, rete principale', 'Dato in tempo reale · 420 msg/min', 'dataset'], ['dash', 'Monitoraggio ZTL', 'Cruscotto · condiviso con 12', 'dash-view'], ['flow', 'Isocrone TPL, 30 minuti', 'Processo · riuscito stanotte alle 02:04', 'process-view']];
  const alerts0 = [['bad', 'Congestione anomala sulla tangenziale, carreggiata esterna', 'Sala operativa traffico · 08:42'], ['warn', 'Idrometro di Ponte Nord sopra la soglia di attenzione', 'Livelli del fiume Lete · 08:10'], ['info', 'Ritardo della linea 12 oltre 12 minuti', 'Trasporto pubblico · 07:55']];
  const alerts = collab ? alerts0.filter(a => /Sala operativa/.test(a[2])) : alerts0;
  const apps = [['database', 'Catalogo', 'Dati, servizi, flussi e connessioni', collab ? '4 condivisi con te' : '214 dati', 'catalog', 'c1'], ['map', 'Mappe', 'Mappe 2D e 3D con GeoLibre', collab ? '2 condivise con te' : '6 mappe · 1 aperta da altri', 'maps', 'c2'], ['dash', 'Cruscotti', 'Indicatori, grafici e tempo reale', collab ? '1 condiviso con te' : '4 cruscotti', 'dashboards', 'c3'], ['flow', 'Processi', 'GeoFlow: a mano, a orario, su evento', collab ? '1 condiviso con te' : '5 processi · 1 fallito', 'processes', 'c4']];
  return `<div class="page">
    ${lhead(S, { compact: true, title: `Buongiorno, ${first}`, total: `Benvenuti nel geoportale del Comune di Esempio · ${R.unit}`, create: collab ? null : { label: 'Nuovo', act: 'new' } })}
    <div class="banner info">${ic('overview', 18)}<div>La Panoramica è del mockup Mosaikit (<span class="mono">mosaikit:home</span>): qui ci sono solo i riquadri che Geoportal le aggiunge. Notifiche e spazio sono nel mockup Mosaikit.</div></div>
    <section class="launch mt"><div class="panel-h"><h2>Le tue app</h2><span class="muted small">Le quattro di Geoportal</span></div>
      <div class="tiles">${apps.map(a => `<button type="button" class="tile ${a[5]}" data-go="${a[4]}">${ic(a[0], 22)}<b>${a[1]}</b><span>${a[2]}</span><span class="small">${a[3]}</span></button>`).join('')}</div></section>
    <div class="grid-home">
      <section class="panel span2"><div class="panel-h"><h2>Riprendi da qui</h2><a data-go="maps">Tutto il mio lavoro</a></div>
        <ul class="rows">${recent.map(x => `<li><span class="ri">${ic(x[0], 16)}</span><div class="rt"><b>${x[1]}</b><span>${x[2]}</span></div>${btn('Apri', { go: x[3] })}</li>`).join('')}</ul></section>
      <section class="panel"><div class="panel-h"><h2>Allerte attive</h2><span class="num muted">${alerts.length}</span></div>
        <ul class="alerts">${alerts.map(a => `<li class="sev-${a[0]}"><div><b>${a[1]}</b><span>${a[2]}</span></div>${btn('Prendi in carico', { act: 'toast', v: 'Presa in carico: gli altri vedono che ci stai lavorando tu.' })}</li>`).join('')}</ul></section>
    </div></div>`;
}

/* ── Catalog ─────────────────────────────────────────────────────────── */

function dsRow(d, sel) {
  // A click opens the detail of the item, a second click on the same item closes it. The box sits
  // outside the button: a control inside a button cannot be used.
  return `<div class="ds-w ${selOf(S, 'cat').includes(d.id) ? 'picked' : ''}">${selBox(S, 'cat', d.id, d.t)}<button type="button" class="ds ${sel ? 'sel' : ''}" data-dsel="${d.id}" aria-pressed="${!!sel}" aria-controls="ds-preview">
    <div class="ds-h">${kindIcon(d.k)}<b>${d.t}</b><span class="muted small num">${d.upd}</span></div>
    <div class="ds-m"><span class="muted">${d.theme} · ${d.f} · ${d.size}</span></div>
    <div class="ds-b">${hostPill(d.host)}${accPill(d.acc)}${d.rules ? pill(`${d.rules} ${d.rules > 1 ? 'regole' : 'regola'} sul dato`, 'rule', 'filter') : ''}</div></button></div>`;
}
function dsPreview(d) {
  const enf = d.host === 'geoserver' ? 'GeoFence su GeoServer, anche per chi apre il dato da QGIS' : d.host === 'oggetti' ? 'Geoportal, che firma l\'URL dopo il controllo' : 'Gateway di Geoportal (proxy)';
  return `<aside class="preview" id="ds-preview" aria-label="Dettaglio di ${d.t}">
    <button type="button" class="btn pv-close" data-act="closeds" aria-label="Chiudi il dettaglio" title="Chiudi il dettaglio">${ic('x', 15)}</button>
    <div class="pv-map">${mapArt(+d.id.slice(1), { points: 9, poly: d.k === 'db', heat: d.k === 'stream' })}</div>
    <h2>${d.t}</h2><p>${d.d}</p>
    <dl class="kv"><dt>Tipo</dt><dd>${KINDS[d.k].label} · ${d.f}</dd><dt>Proprietario</dt><dd>${d.own}</dd><dt>Dove sta</dt><dd>${HOSTS[d.host].label}</dd><dt>Chi lo vede</dt><dd>${ACCESS[d.acc].label}</dd><dt>Chi applica i permessi</dt><dd>${enf}</dd></dl>
    <div class="pv-a">${S.role === 'collab' ? btn('Guarda sulla mappa', { kind: 'pri', icon: 'map', act: 'dsmap', v: d.id }) : btn('Apri in una mappa nuova', { kind: 'pri', icon: 'map', act: 'dsmap', v: d.id }) + btn('Crea cruscotto', { icon: 'dash', go: 'dash-edit' })}<button type="button" class="btn" data-ds="${d.id}" data-go="dataset"><span>Scheda completa</span></button></div>
  </aside>`;
}
function connRow(S, c) {
  return `<div class="ds-w ${selOf(S, 'conn').includes(c.id) ? 'picked' : ''}">${selBox(S, 'conn', c.id, c.t)}<button type="button" class="ds" data-res="conn:${c.id}" data-go="conn">
    <div class="ds-h">${kindIcon(c.k)}<b>${c.t}</b><span class="muted small num">${c.upd}</span></div>
    <div class="ds-m"><span class="muted">${CONN_KIND[c.k][1]} · ${c.proto} · di ${c.own}</span></div>
    <div class="ds-b">${c.st === 'ok' ? pill('In salute', 'ok', 'check') : pill('Non risponde', 'bad', 'alert')}${pill(`${c.derived} dati ne derivano`, '', 'layers')}${accPill(c.acc)}</div></button></div>`;
}
function secCatalog(S) {
  // «Mostra» chooses between data and the connections they come from. The «Consultazione e modifica»
  // profile creates no data, so it does not see connections.
  const conn = S.role !== 'collab' && tabOf(S, 'catk', 'dati') === 'conn';
  setSub(conn ? 'connections' : 'data');
  const own = ['Di chi', S.role === 'collab' ? [['Condivisi con me', 4, true], ['In scrittura', 1]] : [['Tutti', conn ? 6 : 214, true], ['I miei', conn ? 2 : 23], ['Condivisi con me', conn ? 4 : 41], ['Preferiti', conn ? 1 : 6]], 'one'];
  const scope = S.role === 'collab' ? null : [['Dati', 214, 'dati'], ['Connessioni', CONNS.length, 'conn']];
  if (conn) {
    return resPage(S,
      { title: 'Catalogo', total: `${CONNS.length} connessioni · 1 non risponde`, create: { label: 'Nuova connessione', act: 'connect' },
        bulk: { key: 'conn', ids: CONNS.map(c => c.id), acts: [['refresh', 'Verifica'], ['share', 'Condividi'], ['trash', 'Elimina', 1]] } },
      filterCol(S, 'conn', [own, ['Tipo', [['Database', 2], ['Tempo reale', 2], ['Elaborazione', 1], ['Catalogo', 1]]], ['Stato', [['In salute', 5], ['Non risponde', 1]]], ['Chi la può usare', [['Tutto il tenant', 4], ['Gruppi', 2]]]], { search: 'Cerca una connessione', scopeKey: 'catk', scope }),
      `<div class="dslist">${CONNS.map(c => connRow(S, c)).join('')}</div>`);
  }
  // No detail until an item is chosen: the list takes the whole width.
  const sel = S.catSel ? DATASETS.find(d => d.id === S.catSel) : null;
  return resPage(S,
    { title: 'Catalogo', total: S.role === 'collab' ? '4 dati condivisi con te' : '214 dati', create: { label: 'Nuovo dato', act: 'new' },
      bulk: { key: 'cat', ids: vis(S, 'cat', DATASETS).map(d => d.id), acts: [['share', 'Condividi'], ['map', 'Apri in una mappa'], ['download', 'Scarica'], ['trash', 'Elimina', 1]] } },
    filterCol(S, 'cat', [
      ['Tipo', [['File', 171], ['Servizi OGC', 18], ['Tempo reale', 9], ['Database', 7], ['Elaborazione', 5], ['Cataloghi', 4]]],
      ['Dove sta', [['GeoServer', 148], ['Geoportal', 21], ['Archivio oggetti', 23], ['Remoto', 22]]],
      ['Metadati RNDT', [['Completi, nel CSW', 161], ['Completi, non nel CSW', 7], ['Da completare', 46]]],
      ['Chi lo vede', [['Pubblico', 64], ['Tutto il tenant', 92], ['Gruppi', 47], ['Solo io', 11]]],
      ['Tema', [['Mobilità', 58, true], ['Ambiente', 48], ['Territorio', 62], ['Protezione civile', 21]]],
      ['Formato', [['GeoPackage', 41], ['Shapefile', 37], ['COG', 19], ['CSV', 22], ['GTFS', 3], ['3D Tiles', 4]]],
      ['Con regole sul dato', [['Sì', 9]]],
      MODIFIED], { search: 'Cerca nel catalogo', scopeKey: scope ? 'catk' : null, scope: scope || [['Condivisi con me', 4], ['In scrittura', 1]], pre: scope ? [own] : null }),
    `<div class="dslist">${vis(S, 'cat', DATASETS).map(d => dsRow(d, sel && d.id === sel.id)).join('')}</div>`,
    sel ? dsPreview(sel) : '');
}

/* ── The page of a data: permissions and rules, enforced by GeoFence ──
   RNDT metadata (ISO 19115/19139): Geoportal derives what it knows from the data and the body; the
   person fills the rest. Complete, the metadata go to the CSW catalog (pycsw), where the national
   repository harvests them. Publishing the metadata does not open the data. */
const META_MISSING = { d4: ['lineage', 'res'], d12: ['lineage', 'res', 'kw', 'inspire'], d2: ['res'] };
function rndtMeta(S, d) {
  const mine = d.own === ROLES[S.role].who, edit = mine || S.role === 'manager';
  const miss = (S.metaDone && S.metaDone[d.id]) ? [] : (META_MISSING[d.id] || []);
  const req = '<span class="req">obbligatorio</span>';
  const M = k => miss.includes(k);
  const ta = (k, label, val, hint) => `<label class="lbl span2 ${M(k) ? 'miss' : ''}"><span class="lbl-h">${label}${M(k) ? req : ''}</span><textarea rows="3" placeholder="${hint || ''}">${M(k) ? '' : val}</textarea></label>`;
  const inp = (k, label, val, o) => `<label class="lbl ${o && o.wide ? 'span2' : ''} ${M(k) ? 'miss' : ''}"><span class="lbl-h">${label}${M(k) ? req : ''}</span><input type="text" value="${M(k) ? '' : val}" placeholder="${(o && o.ph) || ''}"></label>`;
  const sel = (k, label, opts, cur) => `<label class="lbl ${M(k) ? 'miss' : ''}"><span class="lbl-h">${label}${M(k) ? req : ''}</span><select>${M(k) ? '<option>Scegli…</option>' : ''}${opts.map(o => `<option ${!M(k) && o === cur ? 'selected' : ''}>${o}</option>`).join('')}</select></label>`;
  const vec = !['COG', '3D Tiles'].includes(d.f);
  const auto = [['Identificatore del file', `c_x000:geoportal:${d.id}`], ['Identificatore della risorsa', `c_x000:${d.id}-2026`], ['Data dei metadati', '08/10/2026, a ogni modifica'], ['Lingua e set di caratteri', 'Italiano · UTF-8'], ['Standard', 'ISO 19115/19139, profilo RNDT 2.0'], ['Livello gerarchico', 'Dataset'], ['Sistema di riferimento', 'EPSG:6707 · RDN2008 / UTM zone 32N'], ['Estensione geografica', '11,30 – 11,60 E · 43,60 – 43,85 N'], ['Tipo di rappresentazione', vec ? 'Vettore' : 'Raster'], ['Formato di distribuzione', d.f], ['Risorsa online', d.k === 'stream' ? 'https://esempio.geoportal.example/sse/' + d.id : 'https://esempio.geoportal.example/ows?layer=esempio:' + d.id], ['Distributore', 'Comune di Esempio · Ufficio SIT']];
  const total = 12, done = total - miss.length, ok = !miss.length;
  const inCsw = ok && !(S.metaOff && S.metaOff[d.id]);
  const pubSt = d.acc === 'pubblico' || (S.pubOk || {})[d.id] ? 'pub' : (S.pubReq || {})[d.id] ? 'wait' : 'no';
  const pubRow = `<li><div class="rt grow"><b>Nel catalogo pubblico</b><span>${pubSt === 'pub' ? 'Il dato si vede e si scarica senza account.' : pubSt === 'wait' ? 'Chiesto: aspetta l’approvazione di un gestore.' : 'Il dato resta del tenant. Per aprirlo a tutti si chiede, e un gestore approva.'}</span></div>${pubSt === 'pub' ? `${pill('Pubblico', 'ok', 'globe')}${btn('Vedi la scheda pubblica', { act: 'pubopen', v: d.id })}` : pubSt === 'wait' ? (S.role === 'manager' ? btn('Approva', { kind: 'pri', act: 'puback', v: d.id }) : pill('In attesa', 'warn', 'clock')) : mine ? btn('Chiedi la pubblicazione', { icon: 'globe', act: 'pubask', v: d.id }) : ''}</li>`;
  const kw = d.theme === 'Urbanistica' ? ['catasto', 'proprietà fondiaria', 'pianificazione urbana'] : ['traffico', 'mobilità urbana', 'monitoraggio'];
  return `${ok ? `<div class="banner info">${ic('check', 18)}<div><b>Metadati completi.</b> ${inCsw ? 'Sono nel catalogo CSW del tenant: il Repertorio nazionale li raccoglie al prossimo giro.' : 'Non sono nel catalogo CSW: li ha tolti il proprietario.'}</div></div>`
      : `<div class="banner warn">${ic('alert', 18)}<div><b>Mancano ${miss.length} ${miss.length === 1 ? 'campo obbligatorio' : 'campi obbligatori'} del profilo RNDT.</b> Finché mancano, il dato non va nel catalogo CSW; nel resto di Geoportal funziona come sempre.</div>${edit ? btn('Riempi con valori d’esempio', { act: 'metadone', v: d.id }) : ''}</div>`}
    ${edit ? '' : `<p class="muted small mt">${ic('lock', 12)} I metadati li compila il proprietario, ${d.own}, o un gestore del tenant: qui li leggi.</p>`}<section class="panel mt ${edit ? '' : 'ro'}"><div class="panel-h"><div><h2>${edit ? 'Da compilare' : 'Metadati'}</h2><p class="muted small">${done} di ${total} campi compilati. Quelli con ${req} mancano.</p></div><div class="row">${btn('Scarica l’XML ISO 19139', { icon: 'download', act: 'toast', v: 'Scaricamento dell’XML ISO 19139 avviato.' })}</div></div>
      <h3 class="h3">Identificazione</h3><div class="fgrid">${inp('title', 'Titolo', d.t, { wide: true })}${ta('abs', 'Descrizione (abstract)', d.d)}${sel('dtype', 'Tipo di data di riferimento', ['Creazione', 'Pubblicazione', 'Revisione'], 'Revisione')}${inp('date', 'Data di riferimento', /\d{2}\/\d{2}\/\d{4}/.test(d.upd) ? d.upd : '08/10/2026')}${sel('lang', 'Lingua della risorsa', ['Italiano', 'Inglese', 'Tedesco'], 'Italiano')}</div>
      <h3 class="h3">Classificazione</h3><div class="fgrid">${sel('inspire', 'Tema INSPIRE', ['Parcelle catastali', 'Reti di trasporto', 'Idrografia', 'Ortoimmagini', 'Edifici', 'Impianti di monitoraggio ambientale'], d.theme === 'Urbanistica' ? 'Parcelle catastali' : d.theme === 'Mobilità' ? 'Reti di trasporto' : d.theme === 'Territorio' ? 'Ortoimmagini' : 'Impianti di monitoraggio ambientale')}${sel('topic', 'Categoria tematica ISO', ['planningCadastre', 'transportation', 'environment', 'imageryBaseMapsEarthCover', 'inlandWaters', 'structure'], d.theme === 'Urbanistica' ? 'planningCadastre' : d.theme === 'Mobilità' ? 'transportation' : 'environment')}
        <div class="lbl span2 ${M('kw') ? 'miss' : ''}"><span class="lbl-h">Parole chiave · thesaurus GEMET${M('kw') ? req : ''}</span><div class="gchips">${M('kw') ? '' : kw.map(k => `<span class="gchip">${k}<button type="button" aria-label="Togli ${k}">${ic('x', 12)}</button></span>`).join('')}<button type="button" class="gchip add">${ic('plus', 13)}Dal thesaurus</button></div></div></div>
      <h3 class="h3">Responsabili</h3><div class="fgrid">${inp('resp', 'Responsabile della risorsa', 'Comune di Esempio · ' + (d.own === 'Ufficio SIT' ? 'Ufficio SIT' : 'Dipartimento ' + d.theme))}${inp('respm', 'Email del responsabile', 'sit@comune.esempio.example')}${sel('role', 'Ruolo', ['Punto di contatto', 'Proprietario', 'Custode', 'Autore'], 'Punto di contatto')}${inp('metac', 'Responsabile dei metadati', 'Comune di Esempio · Ufficio SIT · sit@comune.esempio.example')}</div>
      <h3 class="h3">Vincoli</h3><div class="fgrid">${sel('acc', 'Limitazioni d’accesso pubblico', ['Nessuna limitazione', 'Riservatezza (art. 13 dir. INSPIRE)', 'Dati personali'], d.acc === 'pubblico' ? 'Nessuna limitazione' : d.id === 'd4' ? 'Dati personali' : 'Riservatezza (art. 13 dir. INSPIRE)')}${sel('lic', 'Condizioni d’uso · licenza', ['CC-BY 4.0', 'IODL 2.0', 'Uso interno'], 'CC-BY 4.0')}</div>
      <h3 class="h3">Qualità</h3><div class="fgrid">${ta('lineage', 'Genealogia', 'Da ' + d.f + ', caricato in Geoportal e pubblicato su GeoServer. ' + d.d, 'Da dove viene il dato e come è stato prodotto')}${inp('res', vec ? 'Risoluzione · scala equivalente' : 'Risoluzione · distanza al suolo', vec ? '1:2.000' : '20 cm', { ph: vec ? '1:5.000' : '50 cm' })}${inp('conf', 'Conformità', 'Regolamento (UE) 1089/2010 · non valutata')}</div></section>
    <section class="panel mt"><div class="panel-h"><div><h2>Ricavati da Geoportal</h2><p class="muted small">Li sa già dal dato, dal tenant e dalla pubblicazione su GeoServer: si aggiornano da soli.</p></div></div>
      <dl class="kv">${auto.map(a => `<dt>${a[0]}</dt><dd class="${/^(https|c_x000)/.test(a[1]) ? 'mono small' : ''}">${a[1]}</dd>`).join('')}</dl></section>
    <section class="panel mt"><ul class="rows"><li><div class="rt grow"><b>Metadati nel catalogo CSW</b><span>Lo decide il proprietario. Pubblicare i metadati non apre il dato: chi lo trova nel catalogo nazionale vede la scheda, e per i dati servono comunque i permessi.</span></div><button type="button" class="tgl" role="switch" aria-label="Metadati nel CSW" aria-checked="${inCsw}" data-act="metacsw" data-v="${d.id}" ${ok && mine ? '' : 'disabled'}><i></i></button>${mine ? '' : `<span class="muted small">Lo decide ${d.own}</span>`}</li>${pubRow}</ul></section>`;
}
function secDataset(S) {
  const d = DATASETS.find(x => x.id === S.ds) || DATASETS[3];
  const t0 = tabOf(S, 'ds', 'perm');
  const t = ['over', 'meta', 'perm', 'rules', 'used', 'ver'].includes(t0) ? t0 : 'perm';
  setSub({ over: 'overview', meta: 'metadata', perm: 'permissions', rules: 'rules', used: 'used', ver: 'versions' }[t]);
  const isGn = d.host === 'geoserver';
  const gov = d.own === ROLES[S.role].who || S.role === 'manager';
  let body = '';
  if (t === 'over') {
    body = `<div class="two"><section class="panel"><h2>Descrizione</h2><p>${d.d}</p>
      <dl class="kv"><dt>Dove sta</dt><dd>${HOSTS[d.host].label}</dd><dt>Chi lo vede</dt><dd>${ACCESS[d.acc].label}</dd><dt>Tema DCAT</dt><dd>${d.theme}</dd><dt>Licenza</dt><dd>CC-BY 4.0</dd><dt>Frequenza</dt><dd>${d.k === 'stream' ? 'Continua' : 'Su modifica'}</dd><dt>Sistema di riferimento</dt><dd>EPSG:6707 → servito in EPSG:4326</dd><dt>Dimensione</dt><dd>${d.size}</dd></dl></section>
      <section class="panel"><h2>Distribuzioni</h2><ul class="dist">${(d.k === 'stream' ? [['SSE', 'Flusso per mappe e cruscotti', 'Endpoint'], ['GeoJSON', 'Istantanea ogni 15 min (processo)', 'Scarica']] : [['WMS / WFS', 'Pubblicato da GeoServer', 'Endpoint'], ['GeoPackage', 'File completo', 'Scarica'], ['Tasselli', 'Per mappe veloci anche con molti elementi', 'Link']]).map(x => `<li><b>${x[0]}</b><span>${x[1]}</span><a>${x[2]}</a></li>`).join('')}</ul></section></div>`;
  } else if (t === 'meta') {
    body = rndtMeta(S, d);
  } else if (t === 'perm') {
    const subj = [
      ['user', d.own, 'Proprietario', [1, 1, 1, 1, 1], 'owner'],
      ['users', 'Gruppo Urbanistica', '17 persone', [1, 1, 1, 1, 0]],
      ['users', 'Gruppo Mobilità', '23 persone', [1, 1, 0, 0, 0]],
      ['building', 'Tutto il tenant · Comune di Esempio', '128 persone', d.acc === 'gruppi' || d.acc === 'privato' ? [0, 0, 0, 0, 0] : [1, 0, 0, 0, 0]],
      ['link', 'Link con scadenza', 'Lettura · scade il 31/10', [1, 0, 0, 0, 0]],
      ['globe', 'Chiunque, anche senza accesso', 'Pubblico', [0, 0, 0, 0, 0]]
    ];
    const cols = ['Vedere', 'Scaricare', 'Modificare', 'Modificare i dati', 'Gestire'];
    body = `<section class="panel"><div class="panel-h"><div><h2>Chi può fare che cosa</h2><p class="muted small">Chi lo vede: <b>${ACCESS[d.acc].label}</b> · Dove sta: <b>${HOSTS[d.host].label}</b></p></div>${gov ? btn('Gestisci', { kind: 'pri', icon: 'share', act: 'share', v: 'ds:' + d.id }) : `<span class="muted small">Li gestisce il proprietario, ${d.own}</span>`}</div>
      <div class="tbl-w"><table class="tbl perm"><thead><tr><th>Chi</th>${cols.map(c => `<th class="c">${c}</th>`).join('')}</tr></thead><tbody>
      ${subj.map(s => `<tr><td><div class="cell-t"><span class="ri">${ic(s[0], 15)}</span><div><b>${s[1]}</b><span class="muted small">${s[2]}</span></div></div></td>${s[3].map(v => `<td class="c"><span class="chk ${v ? 'on' : ''} ${s[4] ? 'lock' : ''}">${v ? ic('check', 13) : ''}</span></td>`).join('')}</tr>`).join('')}
      </tbody></table></div><p class="muted small mt">I soggetti e la finestra Condividi sono di Mosaikit; Geoportal aggiunge i due livelli propri del dato: <b>Scaricare</b> e <b>Modificare i dati</b>.</p></section>`;
  } else if (t === 'rules') {
    const rules = DATA_RULES.filter(r => r.ds === d.id).map(r => [ruleWho(r), r.acc, r.area, r.rows, r.cols, 'GeoFence']);
    body = `<section class="panel"><div class="panel-h"><h2>Regole sul dato</h2><div class="row">${gov ? btn('Nuova regola', { icon: 'plus', kind: 'pri', act: 'rulenew', v: d.id }) : `<span class="muted small">Le decidono il proprietario, ${d.own}, e i gestori</span>`}</div></div>
      <p class="muted">Il permesso dice <i>se</i> una persona vede il dato; la regola dice <i>quanta parte</i>: un'area, un filtro sulle righe, le colonne nascoste. ${isGn ? 'Per i dati su GeoServer le regole diventano regole GeoFence, quindi valgono anche per chi apre il WMS/WFS da QGIS.' : 'Per i dati fuori da GeoServer le applica il gateway di Geoportal sulle richieste e sui flussi.'}</p>
      <div class="tbl-w"><table class="tbl"><thead><tr><th>Chi</th><th>Accesso</th><th>Area</th><th>Righe</th><th>Colonne</th><th>Applicata da</th></tr></thead><tbody>
      ${rules.length ? '' : '<tr><td colspan="6" class="muted">Nessuna regola: chi vede il dato lo vede tutto.</td></tr>'}${rules.map(r => `<tr>${r.map((c, i) => `<td>${i === 5 ? pill(isGn ? c : 'Gateway', 'info') : i === 2 ? `${ic('pin', 13)} ${c}` : c}</td>`).join('')}</tr>`).join('')}</tbody></table></div></section>`;
  } else if (t === 'used') {
    body = `<div class="two"><section class="panel"><h2>Dove è usato</h2><ul class="rows">
      <li><span class="ri">${ic('map', 15)}</span><div class="rt"><b>Piano urbano della mobilità</b><span>Mappa · pubblica · il dato è più chiuso della mappa</span></div>${pill('Da verificare', 'warn')}</li>
      ${S.role === 'collab' ? `<li class="muted"><span class="ri">${ic('lock', 15)}</span><div class="rt"><b>2 risorse a cui non hai accesso</b><span>Le vede chi ha i permessi</span></div></li>` : `<li><span class="ri">${ic('dash', 15)}</span><div class="rt"><b>Monitoraggio ZTL</b><span>Cruscotto · Tutto il tenant</span></div>${pill('Coerente', 'ok')}</li>
      <li><span class="ri">${ic('flow', 15)}</span><div class="rt"><b>Catasto in GeoPackage</b><span>Processo · lo legge come ingresso</span></div>${pill('Coerente', 'ok')}</li>`}</ul></section>
      <section class="panel"><h2>Da dove viene</h2><ul class="rows"><li><span class="ri">${ic('database', 15)}</span><div class="rt"><b>PostGIS · catasto_prod</b><span>Connessione del tenant · credenziali cifrate</span></div></li><li><span class="ri">${ic('cpu', 15)}</span><div class="rt"><b>Pubblicato su GeoServer</b><span>da Geoportal, workspace esempio</span></div></li></ul></section></div>`;
  } else {
    const vs = [['Oggi 10:45', 'Sara Greco', 'Dati sostituiti · 1.214 righe', 1], ['02/10/2026', 'Sara Greco', 'Aggiunta la colonna stato_soglia', 0], ['20/09/2026', d.own, 'Primo caricamento', 0]];
    body = `<section class="panel"><div class="panel-h"><div><h2>Versioni</h2><p class="muted small">Ogni sostituzione dei dati è una versione. Ripristinare non cancella niente: la versione di oggi resta nella cronologia.</p></div></div><ul class="rows">${vs.map(v => `<li><div class="rt grow"><b>${v[2]}</b><span>${v[0]} · ${v[1]}</span></div>${v[3] ? pill('Attuale', 'ok') : gov ? btn('Ripristina', { act: 'toast', v: 'Versione del ' + v[0] + ' ripristinata: GeoServer serve di nuovo quella.' }) : btn('Scarica', { icon: 'download', act: 'toast', v: 'Scaricamento della versione del ' + v[0] + ' avviato.' })}</li>`).join('')}</ul></section>`;
  }
  return detailPage(S, {
    back: ['catalog', 'Torna al catalogo'], title: d.t, sub: `${KINDS[d.k].label} · ${d.f} · ${d.size}`,
    actions: S.role === 'manager' ? btn('Vedi nel registro', { icon: 'history', act: 'mk', v: 'activity' }) : '',
    tabs: tabs([['over', 'Panoramica'], ['meta', 'Metadati RNDT'], ['perm', 'Permessi'], ['rules', 'Regole sul dato'], ['used', 'Dove è usato'], ['ver', 'Versioni']], t, 'ds'), body });
}
function secConn(S) {
  const c = CONNS.find(x => x.id === (S.resId && S.resId.conn)) || CONNS[0];
  const t0 = tabOf(S, 'cn', 'det');
  const t = ['det', 'der', 'perm'].includes(t0) ? t0 : 'det';
  setSub({ det: 'details', der: 'derived', perm: 'permissions' }[t]);
  let body;
  if (t === 'der') {
    const der = [['Particelle catastali', 'catasto.particelle', 'gruppi'], ['Fabbricati', 'catasto.fabbricati', 'spazio'], ['Fogli catastali', 'catasto.fogli', 'spazio']];
    body = `<div class="panel-h"><p class="muted small">Ogni dato è una tabella, una vista o un topic di questa connessione, con permessi suoi: chi vede il dato non vede la connessione.</p>${btn('Crea un dato da qui', { icon: 'plus', kind: 'pri', act: 'new' })}</div>
      <div class="tbl-w mt"><table class="tbl"><thead><tr><th>Dato</th><th>Da</th><th>Chi lo vede</th></tr></thead><tbody>${der.map(r => `<tr class="clk" data-ds="d4" data-go="dataset"><td><b>${r[0]}</b></td><td class="mono small">${r[1]}</td><td>${accPill(r[2])}</td></tr>`).join('')}</tbody></table></div>
      <p class="muted small mt">${der.length} di ${c.derived} dati mostrati.</p>`;
  } else if (t === 'perm') {
    const subj = [['user', c.own, 'Proprietario', [1, 1], 1], ['users', 'Gruppo Urbanistica', '17 persone', [1, 0]], ['building', 'Tutto il tenant · Comune di Esempio', '128 persone', [c.acc === 'spazio' ? 1 : 0, 0]]];
    body = `<section class="panel"><div class="panel-h"><div><h2>Chi la può usare</h2><p class="muted small"><b>Usare</b>: crearne dati, senza vedere indirizzo e credenziali. <b>Gestire</b>: modificarla, cambiare le credenziali e ricondividerla.</p></div>${btn('Gestisci', { kind: 'pri', icon: 'share', act: 'share' })}</div>
      <div class="tbl-w"><table class="tbl perm"><thead><tr><th>Chi</th><th class="c">Usare</th><th class="c">Gestire</th></tr></thead><tbody>
      ${subj.map(s => `<tr><td><div class="cell-t"><span class="ri">${ic(s[0], 15)}</span><div><b>${s[1]}</b><span class="muted small">${s[2]}</span></div></div></td>${s[3].map(v => `<td class="c"><span class="chk ${v ? 'on' : ''} ${s[4] ? 'lock' : ''}">${v ? ic('check', 13) : ''}</span></td>`).join('')}</tr>`).join('')}</tbody></table></div></section>
      <section class="panel mt"><div class="panel-h"><div><h2>Proprietario</h2><p class="muted small">Le credenziali sono dell'ente. Se ${c.own} lascia il tenant, la connessione va passata a un altro: altrimenti i ${c.derived} dati che ne dipendono restano senza nessuno che la possa aggiornare.</p></div>${btn('Cambia proprietario', { icon: 'user', act: 'toast', v: 'Scegli la persona del tenant a cui passa la connessione.' })}</div></section>`;
  } else {
    body = `${c.st === 'bad' ? `<div class="banner bad">${ic('alert', 18)}<div><b>Non risponde da 14 minuti.</b> I ${c.derived} dati che ne derivano mostrano l'ultimo valore ricevuto; la Panoramica del tenant lo segnala.</div>${btn('Verifica adesso', { icon: 'refresh', act: 'toast', v: 'Ancora nessuna risposta dal broker: si riprova da solo ogni minuto.' })}</div>` : ''}
    <section class="panel"><h2>Collegamento</h2><div class="fgrid">${fld('Nome', c.t, { wide: true })}${fld('Tipo', CONN_KIND[c.k][1] + ' · ' + c.proto, { ro: true })}${fld('Indirizzo', c.host, { mono: true })}${fld(c.k === 'stream' ? 'Topic' : c.k === 'db' ? 'Database o schema' : 'Percorso', c.db, { mono: true })}${fld('Utente', c.user, { mono: true })}</div></section>
    <section class="panel mt"><div class="panel-h"><div><h2>Credenziali</h2><p class="muted small">Cifrate nel database di Geoportal, mai mandate al browser: chi usa la connessione non le vede.</p></div>${btn('Cambia credenziali', { icon: 'key' })}</div><dl class="kv"><dt>Password o token</dt><dd class="mono">••••••••••••</dd><dt>Ultimo cambio</dt><dd>12/06/2026, ${c.own}</dd></dl></section>`;
  }
  return detailPage(S, { back: ['catalog', 'Torna alle connessioni'], title: c.t, sub: `${CONN_KIND[c.k][1]} · ${c.proto} · ${c.st === 'ok' ? 'in salute' : 'non risponde'}`,
    actions: btn('Verifica', { icon: 'refresh', act: 'toast', v: 'Connessione verificata.' }) + btn('Annulla', { icon: 'undo' }) + btn('Salva', { kind: 'pri', icon: 'save' }),
    tabs: tabs([['det', 'Dettagli'], ['der', 'Dati che ne derivano'], ['perm', 'Permessi']], t, 'cn'), body });
}

/* ── Lists of maps, dashboards, processes ────────────────────────────── */

function secMaps(S) {
  setSub(null);
  return resPage(S,
    { title: 'Mappe', total: S.role === 'collab' ? '2 mappe condivise con te' : '6 mappe', actions: trashBtn(S), create: { label: 'Nuova mappa', go: 'map-edit' },
      bulk: { key: 'maps', ids: vis(S, 'maps', MAPS).map(m => m.id), acts: [['share', 'Condividi'], ['copy', 'Duplica'], ['download', 'Esporta'], ['trash', 'Elimina', 1]] } },
    filterCol(S, 'maps', [['Chi la vede', [['Privata', 1], ['Gruppi', 2], ['Tutto il tenant', 2], ['Pubblica', 1]]], ['Tema', [['Mobilità', 3], ['Ambiente', 1], ['Protezione civile', 1], ['Territorio', 1]]], ['Vista', [['2D', 5], ['3D', 1]]], ['Contiene', [['Dati in tempo reale', 2], ['Dati con regole', 1], ['Servizi esterni', 3]]], ['Collaborazione', [['Aperta da altri adesso', 1]]], MODIFIED],
      { search: 'Cerca nelle mappe', scope: S.role === 'collab' ? [['Condivise con me', 2], ['In scrittura', 1]] : [['Tutte', 6], ['Le mie', 2], ['Condivise con me', 4], ['Preferite', 2]] }),
    `<div class="cards">${vis(S, 'maps', MAPS).map(m => `<article class="card ${selOf(S, 'maps').includes(m.id) ? 'picked' : ''}">${selBox(S, 'maps', m.id, m.t)}
      <button type="button" class="card-img" data-res="map:${m.id}" data-go="map-view" aria-label="Apri ${m.t}">${mapArt(m.seed, { points: 8, poly: m.seed % 2 === 1 })}<span class="card-acc">${accPill(m.acc)}</span>${m.live ? `<span class="card-live">${ic('users', 12)} 3 adesso</span>` : ''}</button>
      <div class="card-b"><button type="button" class="card-t" data-res="map:${m.id}" data-go="map-view">${m.t}</button><span class="muted small clamp">${m.d}</span>
      <div class="card-f"><span class="avs">${m.people.map((p, i) => av(p, i + 2, 22)).join('')}</span><span class="muted small num">${m.upd}</span>
      <span class="grow"></span>${favBtn(S, m)}${m.own === ROLES[S.role].who || S.role === 'manager' ? btn('', { icon: 'share', act: 'share', v: 'map:' + m.id, al: 'Condividi ' + m.t }) : ''}</div></div></article>`).join('')}</div>`);
}
function secDashboards(S) {
  setSub(null);
  return resPage(S,
    { title: 'Cruscotti', total: S.role === 'collab' ? '1 cruscotto condiviso con te' : '4 cruscotti', actions: trashBtn(S), create: { label: 'Nuovo cruscotto', go: 'dash-edit' },
      bulk: { key: 'dash', ids: vis(S, 'dash', DASHES).map(m => m.id), acts: [['share', 'Condividi'], ['copy', 'Duplica'], ['trash', 'Elimina', 1]] } },
    filterCol(S, 'dash', [['Chi lo vede', [['Gruppi', 2], ['Tutto il tenant', 1], ['Pubblico', 1]]], ['Dominio', [['Mobilità', 2], ['Ambiente', 1], ['Protezione civile', 1]]], ['Contiene', [['Mappa', 4], ['Dati in tempo reale', 3], ['Allerte', 2]]], ['Uso', [['Grande schermo', 1], ['Pubblicato sul portale', 1]]], MODIFIED],
      { search: 'Cerca nei cruscotti', scope: S.role === 'collab' ? [['Condivisi con me', 1]] : [['Tutti', 4], ['I miei', 1], ['Condivisi con me', 3], ['Preferiti', 1]] }),
    `<div class="cards">${vis(S, 'dash', DASHES).map(m => `<article class="card ${selOf(S, 'dash').includes(m.id) ? 'picked' : ''}">${selBox(S, 'dash', m.id, m.t)}<button type="button" class="card-img dashthumb" data-res="dash:${m.id}" data-go="dash-view" aria-label="Apri ${m.t}"><div class="dt-g"><i></i><i></i><i class="m">${mapArt(m.seed, { points: 6, ring: false })}</i><i></i></div><span class="card-acc">${accPill(m.acc)}</span></button>
      <div class="card-b"><button type="button" class="card-t" data-res="dash:${m.id}" data-go="dash-view">${m.t}</button><span class="muted small clamp">${m.d}</span><div class="card-f"><span class="muted small">${m.w} widget</span><span class="muted small num">${m.upd}</span><span class="grow"></span>${favBtn(S, m)}${m.own === ROLES[S.role].who || S.role === 'manager' ? btn('', { icon: 'share', act: 'share', v: 'dash:' + m.id, al: 'Condividi ' + m.t }) : ''}</div></div></article>`).join('')}</div>`);
}
function secProcesses(S) {
  setSub(null);
  return resPage(S,
    { title: 'Processi', total: S.role === 'collab' ? '1 processo condiviso con te' : '5 processi', actions: trashBtn(S), create: { label: 'Nuovo processo', go: 'process-edit' },
      bulk: { key: 'proc', ids: vis(S, 'proc', PROCS).map(p => p.id), acts: [['play', 'Esegui'], ['pause', 'Sospendi la pianificazione'], ['share', 'Condividi'], ['trash', 'Elimina', 1]] } },
    filterCol(S, 'proc', [['Quando parte', [['A mano', 1], ['A orario', 3], ['Su evento', 1]]], ['Ultimo esito', [['Riuscito', 3], ['Fallito', 1], ['In corso', 1]]], ['Nodi usati', [['Sidecar GeoLibre', 2], ['Rete', 1], ['AI', 1], ['Servizi WPS / OGC API', 1]]], ['Chi lo vede', [['Privato', 1], ['Gruppi', 3], ['Tutto il tenant', 1]]], MODIFIED],
      { search: 'Cerca nei processi', scope: S.role === 'collab' ? [['Condivisi con me', 1]] : [['Tutti', 5], ['I miei', 2], ['Condivisi con me', 3], ['Preferiti', 1]] }),
    `<div class="tbl-w"><table class="tbl"><thead><tr><th class="cbx"><span class="sr">Seleziona</span></th><th>Processo</th><th>Quando parte</th><th>Ultima esecuzione</th><th>Nodi</th><th>Modificato</th><th></th></tr></thead><tbody>
    ${vis(S, 'proc', PROCS).map(p => `<tr class="clk ${selOf(S, 'proc').includes(p.id) ? 'picked' : ''}" data-res="proc:${p.id}" data-go="process-view">${selTd(S, 'proc', p.id, p.t)}<td><div class="cell-t"><span class="ri">${ic('flow', 15)}</span><b>${p.t}</b></div></td><td>${p.trig}</td><td>${stPill(p.st, p.last)}</td><td class="num">${p.nodes}</td><td class="num muted">${p.upd}</td><td>${S.role === 'collab' ? '' : btn('Esegui', { icon: 'play', act: 'toast', v: 'Esecuzione avviata: la segui in Esecuzioni.' })}</td></tr>`).join('')}
    </tbody></table></div>`);
}
function secRuns(S) {
  setSub(null);
  const runs = [['#4812', 'NDVI dei parchi', 'In corso', 'run', 'Oggi 09:00', '—', 'Sidecar GeoLibre · raster'], ['#4811', 'Allerta piena fiume Lete', 'Riuscito', 'ok', 'Oggi 08:41', '3 s', 'Evento: lettura sopra soglia'], ['#4809', 'Isocrone TPL, 30 minuti', 'Riuscito', 'ok', 'Oggi 02:00', '4 min 12 s', 'Orario'], ['#4801', 'Catasto in GeoPackage', 'Fallito', 'bad', 'Ieri 16:12', '30 min', 'A mano · Davide Conti'], ['#4799', 'Report settimanale ZTL', 'Riuscito', 'ok', 'Ven 18:00', '1 min 40 s', 'Orario']];
  const runsV = S.role === 'collab' ? runs.filter(r => vis(S, 'proc', PROCS).some(p => p.t === r[1])) : runs;
  return resPage(S, { title: 'Esecuzioni', total: S.role === 'collab' ? `${runsV.length} esecuzioni dei processi condivisi con te` : '30 esecuzioni negli ultimi 30 giorni', actions: `<button type="button" class="btn" data-go="processes">${ic('flow', 15)}<span>Processi</span></button>`,
      bulk: S.role === 'collab' ? null : { key: 'runs', ids: runs.map(r => r[0].slice(1)), acts: [['play', 'Riesegui'], ['x', 'Ferma'], ['download', 'Scarica i registri'], ['trash', 'Elimina', 1]] } },
    filterCol(S, 'runs', [['Esito', [['In corso', 1], ['Riuscite', 26], ['Fallite', 3]]], ['Perché è partito', [['A mano', 6], ['A orario', 21], ['Su evento', 3]]], ['Processo', PROCS.map(x => [x.t, null])], PERIOD], { search: 'Cerca per processo o persona', sort: SORT_TIME, scope: [['Tutte', 30], ['Le mie', 7]] }),
    `<div class="tbl-w"><table class="tbl"><thead><tr><th class="cbx"><span class="sr">Seleziona</span></th><th>N.</th><th>Processo</th><th>Esito</th><th>Partito</th><th>Durata</th><th>Perché</th><th></th></tr></thead><tbody>
    ${runsV.map(r => `<tr class="${selOf(S, 'runs').includes(r[0].slice(1)) ? 'picked' : ''}">${S.role === 'collab' ? '<td class="cbx"></td>' : selTd(S, 'runs', r[0].slice(1), r[1])}<td class="num mono">${r[0]}</td><td><b>${r[1]}</b></td><td>${stPill(r[3], r[2])}</td><td class="num">${r[4]}</td><td class="num">${r[5]}</td><td class="muted">${r[6]}</td><td>${btn('Registro', { act: 'toast', v: `Registro dell’esecuzione ${r[0]} aperto qui sotto.` })}</td></tr>`).join('')}</tbody></table></div>
    ${S.role === 'collab' ? '' : `<section class="panel mt"><div class="panel-h"><h2>#4801 · Catasto in GeoPackage · perché è fallito</h2>${btn('Riesegui', { icon: 'play', kind: 'pri', act: 'toast', v: 'Esecuzione #4813 avviata con gli stessi parametri.' })}</div>
    <pre class="log">16:12:04  input.dbQuery       61.204 righe da PostGIS catasto_prod
16:12:09  sidecar.conversion  POST /conversion/vector-to-geoparquet  job 9f3c…
16:42:09  sidecar.conversion  tempo massimo (30 min) superato, job annullato
          Suggerimento: dividi per foglio con «transform.filter» o alza il limite nella connessione del servizio</pre></section>`}`);
}

/* ── Map editor: the GeoLibre component inside the frame ─────────────── */

function secMapEdit(S, viewOnly) {
  setSub(null);
  const layers = [['Stazioni di monitoraggio', 14, 'ok', true], ['Area di allerta', 1, 'poly', true], ['Fiume Lete', 1, 'line', true], ['Flussi di traffico · in diretta', '∞', 'live', true], ['Ortofoto 2025', '', 'ras', false]];
  const base = [['Confini comunali', 15], ['Sfondo chiaro', 1]];
  const r = curRes(S, 'map');
  const menus = viewOnly ? '' : `<nav class="gl-menu">${['Progetto', 'Modifica', 'Vista', 'Aggiungi dati', 'Elaborazione', 'Controlli', 'Plugin', 'Aiuto'].map(m => `<button type="button" ${m === 'Aggiungi dati' ? 'data-act="adddata"' : ''}>${m}</button>`).join('')}</nav>`;
  return `<div class="editor ${viewOnly ? 'is-view' : ''}">
    <header class="ed-bar">
      <a class="ed-back" data-go="maps" aria-label="Torna alle mappe">${ic('back', 16)}</a>
      <div class="ed-title"><b>${r.t}</b><span class="muted small">${viewOnly ? `Vista · ${ACCESS[r.acc].label}` : `${ic('check', 12)} Salvato in Geoportal · 12:04`}</span></div>
      ${menus}<span class="grow"></span>${modeSwitch(S, 'map', !viewOnly)}
      <span class="avs live">${av('ER', 2, 26)}${av('SG', 4, 26)}</span>
      ${viewOnly ? btn('Commenti', { icon: 'message', act: 'toast', v: '2 commenti su questa mappa: si aprono accanto, ciascuno sul punto in cui è stato scritto.' }) : btn('Sessione condivisa', { icon: 'users', act: 'toast', v: 'Sessione condivisa aperta: chi ha accesso in scrittura può entrare e lavorare con te.' })}
      ${r.own === ROLES[S.role].who || S.role === 'manager' ? btn('Condividi', { kind: 'pri', icon: 'share', act: 'share', v: 'map:' + r.id }) : ''}
    </header>
    <div class="ed-body">
      ${viewOnly ? '' : `<aside class="ed-left"><div class="ed-tabs"><button type="button" aria-selected="false">Sfoglia</button><button type="button" aria-selected="true">Livelli</button></div>
        <div class="ed-sec"><span>Dati</span>${btn('', { icon: 'plus', act: 'adddata', al: 'Aggiungi dati' })}</div>
        <ul class="lyr">${layers.map((l, i) => `<li class="${i === 0 ? 'sel' : ''}"><span class="sw sw-${l[2]}"></span><span class="lyr-n">${l[0]}</span><span class="num muted small">${l[1]}</span>${ic(l[3] ? 'eye' : 'lock', 14, l[3] ? '' : 'muted')}${l[3] ? '' : `<button type="button" class="lnk small" data-act="access" data-v="${l[0]}">Chiedi l’accesso</button>`}</li>`).join('')}</ul>
        <div class="ed-sec"><span>Mappa di base</span></div>
        <ul class="lyr">${base.map(l => `<li><span class="sw sw-base"></span><span class="lyr-n">${l[0]}</span><span class="num muted small">${l[1]}</span>${ic('eye', 14)}</li>`).join('')}</ul>
        <div class="ed-sec"><span>Dal catalogo Geoportal</span></div><p class="muted small pad">Ogni livello porta con sé i permessi del dato: se un collega non può vederlo, nella sua sessione il livello compare come «riservato».</p></aside>`}
      <main class="ed-map">${mapArt(1, { points: 14, poly: true, big: true, flow: true })}
        <div class="cur c2" style="left:44%;top:42%">${ic('right', 12)}<span>Elena</span></div>
        <div class="cur c4" style="left:58%;top:64%">${ic('right', 12)}<span>Sara</span></div>
        <div class="ed-tools">${[['plus', 'Ingrandisci'], ['down', 'Riduci'], ['compass', 'Nord in alto'], ['ruler', 'Misura'], ['layers', 'Mappe di base'], ['expand', 'Schermo intero']].map(i => `<button type="button" aria-label="${i[1]}" title="${i[1]}">${ic(i[0], 16)}</button>`).join('')}</div>
        <div class="ed-search">${ic('search', 14)}<span>Cerca un luogo o una coordinata…</span></div>
        <div class="legend"><b>Legenda</b>${[['ok', 'Nella norma'], ['warn', 'Attenzione'], ['bad', 'Allerta'], ['poly', 'Area di allerta']].map(x => `<span><i class="lgd-${x[0]}"></i>${x[1]}</span>`).join('')}</div>
        ${viewOnly ? `<div class="cmt" style="left:62%;top:30%">${av('ER', 2, 24)}<div><b>Elena Ricci</b><span>@Davide la soglia qui va rivista dopo il nuovo argine</span></div></div>` : ''}
        <div class="engine">${S.engine3d ? 'CesiumJS · scena 3D' : 'MapLibre · 2D'} <button type="button" data-act="engine">${S.engine3d ? 'Passa al 2D' : 'Passa al 3D'}</button></div>
      </main>
      ${viewOnly ? '' : `<aside class="ed-right"><div class="ed-tabs"><button type="button" aria-selected="true">Stile</button><button type="button" aria-selected="false">Commenti 2</button></div>
        <div class="ed-sec"><span>Stazioni di monitoraggio</span></div>
        <div class="pad stack"><label class="lbl">Colore secondo<select><option>stato_soglia</option></select></label>
        <div class="ramp">${[['ok', 'norma'], ['warn', 'attenzione'], ['bad', 'allerta']].map(x => `<span><i class="lgd-${x[0]}"></i>${x[1]}</span>`).join('')}</div>
        <label class="lbl">Dimensione <input type="range" value="40" aria-label="Dimensione"></label>
        <label class="lbl">Etichetta<select><option>nome_stazione</option></select></label>
        <label class="ck"><input type="checkbox"> Raggruppa i punti vicini</label>
        <p class="muted small">Lo stile si salva con la mappa: la stessa mappa si apre identica nei cruscotti.</p></div></aside>`}
    </div>
    <footer class="ed-status num"><span>43,7120° N · 11,4560° E</span><span>Zoom 11,4</span><span>Inclinazione 0°</span><span class="grow"></span><span>${viewOnly ? 'Sola lettura' : `${ic('users', 12)} Sessione condivisa · 3 persone · relay di collaborazione`}</span></footer></div>`;
}

/* ── Dashboard: view and editor ──────────────────────────────────────── */

function dashGrid(edit) {
  const w = (cls, title, inner) => `<section class="w ${cls}"><div class="w-h"><b>${title}</b>${edit ? ic('more', 14) : ''}</div>${inner}</section>`;
  return `<div class="dgrid ${edit ? 'edit' : ''}">
    ${w('kpi', 'Veicoli/ora, rete principale', `<div class="kpi-v num">18.420</div><div class="kpi-d up">+6,2% rispetto a ieri</div>${spark([12, 14, 13, 15, 17, 16, 18, 19, 18.4])}`)}
    ${w('kpi', 'Velocità media', `<div class="kpi-v num">27 <small>km/h</small></div><div class="kpi-d down">−3 km/h rispetto a ieri</div>${spark([31, 30, 30, 29, 28, 29, 27, 27, 27], 'warn')}`)}
    ${w('kpi', 'Incidenti aperti', `<div class="kpi-v num">4</div><div class="kpi-d">2 sulla tangenziale</div>${spark([2, 3, 2, 5, 4, 3, 4, 4, 4], 'bad')}`)}
    ${w('map', 'Mappa · Flussi in diretta', `<div class="w-map">${mapArt(2, { points: 10, flow: true, heat: true })}<span class="w-tag">mappa GeoLibre incorporata · il riquadro filtra gli altri widget</span></div>`)}
    ${w('chart', 'Transiti per fascia oraria', bars([4, 3, 2, 6, 14, 18, 12, 11, 13, 16, 19, 15], ['0', '2', '4', '6', '8', '10', '12', '14', '16', '18', '20', '22']))}
    ${w('feed', 'Allerte', `<ul class="alerts sm">${[['bad', 'Congestione sulla tangenziale est', '08:42'], ['warn', 'Varco di corso Garibaldi guasto', '08:20'], ['info', 'Corteo in via Dante', '07:55']].map(a => `<li class="sev-${a[0]}"><div><b>${a[1]}</b><span>${a[2]}</span></div></li>`).join('')}</ul>`)}
    ${w('table', 'Tratti più lenti', `<table class="tbl sm"><thead><tr><th>Tratto</th><th class="r">km/h</th><th class="r">veic/h</th></tr></thead><tbody>${[['Tangenziale est, svincolo Nord', 14, 2310], ['Viale della Libertà', 19, 1980], ['Lungofiume Sud', 21, 1420], ['Statale 7, km 9', 23, 1260]].map(r => `<tr><td>${r[0]}</td><td class="r num">${r[1]}</td><td class="r num">${fmtN(r[2])}</td></tr>`).join('')}</tbody></table>`)}
  </div>`;
}
function secDashView(S) {
  setSub(null);
  const d = curRes(S, 'dash'), per = S.dashPer || 'Ultima ora';
  return `<div class="editor is-view"><header class="ed-bar"><a class="ed-back" data-go="dashboards" aria-label="Torna ai cruscotti">${ic('back', 16)}</a>
      <div class="ed-title"><b>${d.t}</b><span class="muted small">Vista · aggiornato ${d.upd}</span></div><span class="grow"></span>
      <div class="seg" role="group" aria-label="Periodo">${['Ultima ora', 'Oggi', '7 giorni'].map(p => btn(p, { kind: per === p ? 'on' : '', act: 'dashper', v: p }).replace('<button', `<button aria-pressed="${per === p}"`)).join('')}</div>
      ${modeSwitch(S, 'dash', false)}
      <span class="avs live">${av('SG', 4, 26)}</span>${btn('', { icon: 'expand', act: 'toast', v: 'Grande schermo: a tutto schermo, senza barre, con l’aggiornamento continuo.' })}${btn('Commenti', { icon: 'message', act: 'toast', v: 'Nessun commento su questo cruscotto.' })}${d.own === ROLES[S.role].who || S.role === 'manager' ? btn('Condividi', { kind: 'pri', icon: 'share', act: 'share', v: 'dash:' + d.id }) : ''}</header>
    <div class="ed-body"><main class="ed-canvas dash"><div class="filters">${pill('Municipio: tutti', '', 'filter')}${pill('Area: riquadro della mappa', 'info', 'map')}</div>${dashGrid(false)}</main></div></div>`;
}
function secDashEdit(S) {
  setSub(null);
  const pal = [['Indicatori', ['Numero', 'Variazione', 'Soglia']], ['Grafici', ['Barre', 'Linee', 'Torta', 'Classifica']], ['Mappa', ['Mappa GeoLibre', 'Mappa di una mappa salvata']], ['Tempo reale', ['Elenco allerte', 'Video', 'Ticker']], ['Testo', ['Titolo', 'Nota']]];
  return `<div class="editor"><header class="ed-bar"><a class="ed-back" data-go="dashboards" aria-label="Torna ai cruscotti">${ic('back', 16)}</a><div class="ed-title"><b>${curRes(S, 'dash').t}</b><span class="muted small">Bozza · modifiche non salvate</span></div><span class="grow"></span>
    ${modeSwitch(S, 'dash', true)}<span class="avs live">${av('SG', 4, 26)}</span>${btn('Annulla', { icon: 'history' })}${btn('Salva', { kind: 'pri', icon: 'check', act: 'toast', v: 'Cruscotto salvato: è una versione nuova.' })}</header>
    <div class="ed-body"><aside class="ed-left"><div class="ed-sec"><span>Componenti</span></div>${pal.map(p => `<div class="palg"><span class="muted small">${p[0]}</span>${p[1].map(x => `<button type="button" class="palw">${ic(p[0] === 'Mappa' ? 'map' : p[0] === 'Grafici' ? 'dash' : p[0] === 'Tempo reale' ? 'radio' : 'grid', 14)}${x}</button>`).join('')}</div>`).join('')}</aside>
    <main class="ed-canvas">${dashGrid(true)}</main>
    <aside class="ed-right"><div class="ed-sec"><span>Mappa · Flussi in diretta</span></div><div class="pad stack">
      <label class="lbl">Mappa<select><option>Rete di monitoraggio, fiume Lete</option><option selected>Piano urbano della mobilità</option></select></label>
      <label class="lbl">Aspetto<select><option>Solo mappa (layout=viewer)</option><option>Con legenda</option></select></label>
      <label class="ck"><input type="checkbox" checked> Il riquadro visibile filtra gli altri widget</label>
      <label class="ck"><input type="checkbox" checked> Un clic su un elemento filtra gli altri widget</label>
      <label class="ck"><input type="checkbox"> I filtri del cruscotto filtrano la mappa</label>
      <p class="muted small">La mappa e il cruscotto si parlano: spostando la mappa si filtrano i grafici, e scegliendo un elemento si filtrano gli altri widget.</p></div></aside></div></div>`;
}

/* ── Process editor (GeoFlow) ────────────────────────────────────────── */

function secProcessEdit(S, viewOnly) {
  setSub(null);
  const nodes = [[40, 50, 'c1', 'Ingresso', 'Particelle catastali', 'dbQuery · PostGIS'], [40, 190, 'c1', 'Ingresso', 'Disegna un\'area', 'userParam · poligono'], [290, 110, 'c2', 'Geometria', 'Ritaglia', 'geo.clip'], [520, 40, 'c5', 'Sidecar GeoLibre', 'In GeoParquet', 'conversion/vector-to-geoparquet'], [520, 190, 'c3', 'Analisi', 'Aggrega per foglio', 'analysis.aggregate'], [760, 110, 'c4', 'Uscita', 'Pubblica su GeoServer', 'output.publishLayer']];
  const links = [[0, 2], [1, 2], [2, 3], [2, 4], [4, 5], [3, 5]];
  const W = 190, H = 74;
  const path = (a, b) => { const x1 = nodes[a][0] + W, y1 = nodes[a][1] + H / 2, x2 = nodes[b][0], y2 = nodes[b][1] + H / 2; return `M${x1} ${y1} C ${x1 + 50} ${y1}, ${x2 - 50} ${y2}, ${x2} ${y2}`; };
  const cats = [['Ingresso', 23], ['Geometria', 6], ['Trasforma', 3], ['Analisi', 1], ['Rete', 4], ['AI', 7], ['Sidecar GeoLibre', 6], ['Servizi WPS / OGC API', 2], ['Uscita', 7]];
  const pr = curRes(S, 'proc');
  const bar = `<header class="ed-bar"><a class="ed-back" data-go="processes" aria-label="Torna ai processi">${ic('back', 16)}</a><div class="ed-title"><b>${pr.t}</b><span class="muted small">${viewOnly ? `Vista · ${pr.trig}` : 'GeoFlow · versione 7'}</span></div><span class="grow"></span>
    ${modeSwitch(S, 'proc', !viewOnly)}${viewOnly ? '' : btn('Prova', { icon: 'eye', act: 'toast', v: 'Prova a secco: nessun dato viene scritto.' }) + btn('Pianifica', { icon: 'clock', act: 'toast', v: 'Scegli quando parte: a orario o su evento.' })}${btn('Cronologia', { icon: 'history', go: 'runs' })}${S.role === 'collab' && !canEdit(S, 'proc') ? '' : btn('Esegui', { kind: 'pri', icon: 'play', act: 'toast', v: 'Esecuzione avviata: la segui in Esecuzioni.' })}</header>`;
  const canvas = `<main class="ed-canvas flow"><div class="flow-in"><svg class="links" viewBox="0 0 980 300" preserveAspectRatio="none" aria-hidden="true">${links.map(l => `<path d="${path(l[0], l[1])}"/>`).join('')}</svg>
      ${nodes.map((n, i) => `<div class="node ${n[2]} ${!viewOnly && i === 3 ? 'sel' : ''}" style="left:${n[0] / 9.8}%;top:${n[1] / 3}%"><i></i><span class="muted small">${n[3]}</span><b>${n[4]}</b><span class="mono small">${n[5]}</span></div>`).join('')}</div></main>`;
  if (viewOnly) {
    return `<div class="editor is-view">${bar}<div class="ed-body">${canvas}
      <aside class="ed-right"><div class="ed-sec"><span>Ultima esecuzione</span></div><div class="pad stack">
        <p>${stPill(pr.st, pr.last)}</p><dl class="kv"><dt>Quando parte</dt><dd>${pr.trig}</dd><dt>Nodi</dt><dd>${pr.nodes}</dd><dt>Modificato</dt><dd>${pr.upd}</dd></dl>
        <p class="muted small">In vista il grafo non si modifica: si esegue, si guarda la cronologia, e chi ha il permesso passa a Modifica dalla barra.</p></div></aside></div></div>`;
  }
  return `<div class="editor">${bar}
    <div class="ed-body"><aside class="ed-left"><div class="field sm m">${ic('search', 14)}<input type="text" placeholder="Cerca un nodo" aria-label="Cerca un nodo"></div>
      <ul class="cats">${cats.map(c => `<li class="${c[0] === 'Sidecar GeoLibre' ? 'new' : ''}"><span>${c[0]}</span><span class="num muted small">${c[1]}</span></li>`).join('')}</ul>
      <div class="pad"><div class="eyebrow">Conversioni ed elaborazioni pesanti</div>${['Vettore → GeoParquet', 'Vettore → FlatGeobuf', 'Vettore → PMTiles', 'Raster → COG', 'SQL su Sedona', 'Segmenta con SAM 3'].map(x => `<button type="button" class="palw">${ic('cpu', 14)}${x}</button>`).join('')}
      <div class="eyebrow mt">Da GeoLibre</div><button type="button" class="palw">${ic('upload', 14)}Importa un modello (.model.json)</button></div></aside>
    ${canvas}
    <aside class="ed-right"><div class="ed-sec"><span>In GeoParquet</span></div><div class="pad stack">
      <p class="muted small">Nodo eseguito dal sidecar GeoLibre (FastAPI, GDAL, DuckDB). Geoportal chiama il sidecar con un token di servizio; il sidecar non è raggiungibile da fuori.</p>
      <label class="lbl">Ordinamento<select><option>Curva di Hilbert</option></select></label><label class="lbl">Tempo massimo<select><option>30 minuti</option></select></label>
      <label class="ck"><input type="checkbox" checked> Scrivi il risultato nell'archivio oggetti</label></div></aside></div></div>`;
}

/* ── Configuration: the sections Geoportal adds to the settings of Mosaikit ──
   Keys of outside map services, at three levels: whoever has a key of their own uses it; otherwise
   the one of the body, if the manager shares it; otherwise the one of the platform, if shared. */
function keyUse(S, lvl, id) {
  const K = S.keys, sy = K.sys[id], tn = K.tnt[id], me = K.me[id];
  if (lvl === 'sys') return sy ? (sy.share ? ['ok', 'La usano tutti i tenant che non ne hanno una loro'] : ['warn', 'Solo per la piattaforma: i tenant devono mettere una loro chiave']) : ['off', 'Il servizio è spento per chi non ha una sua chiave'];
  if (lvl === 'tnt') return tn ? (tn.share ? ['ok', 'La usano tutti gli utenti del tenant'] : ['warn', `La usano solo i gestori; gli utenti usano ${sy && sy.share ? 'quella della piattaforma' : 'una loro chiave'}`]) : sy && sy.share ? ['ok', 'In uso: la chiave della piattaforma'] : ['off', 'Nessuna chiave: il servizio è spento per il tenant'];
  return me ? ['ok', 'In uso: la tua chiave'] : tn && tn.share ? ['ok', 'In uso: la chiave del tenant'] : sy && sy.share ? ['ok', 'In uso: la chiave della piattaforma'] : ['off', 'Nessuna chiave disponibile: per usarlo metti la tua'];
}
function keysSection(S, lvl) {
  const shareLbl = lvl === 'sys' ? 'Condivisa con tutti i tenant' : lvl === 'tnt' ? 'Condivisa con gli utenti del tenant' : '';
  const intro = lvl === 'sys' ? 'Le chiavi dei servizi di mappa che la piattaforma paga. Una chiave condivisa la usano tutti i tenant che non ne hanno una loro.'
    : lvl === 'tnt' ? 'Le chiavi che il tenant paga. Condivisa, la usano tutti gli utenti del tenant; non condivisa, solo i gestori. Dove manca vale quella della piattaforma, se condivisa.'
      : 'Le tue chiavi valgono solo per te. Dove non ne hai una, usi quella del tenant o della piattaforma, se sono condivise.';
  const rows = KEY_GROUPS.map(g => `<h3 class="key-g">${g[1]}</h3><ul class="rows keys">${KEY_SVC.filter(s => s[2] === g[0]).map(s => {
    const k = S.keys[lvl][s[0]], u = keyUse(S, lvl, s[0]), ed = S.keyEdit === lvl + ':' + s[0];
    return `<li class="key-r"><span class="ri">${ic(s[3], 15)}</span><div class="rt grow"><b>${s[1]}</b><span>${s[4]}</span><span class="key-u ku-${u[0]}">${ic(u[0] === 'ok' ? 'check' : u[0] === 'warn' ? 'alert' : 'x', 12)}${u[1]}</span>
        ${ed ? `<div class="key-ed"><input type="password" class="mono" aria-label="Chiave per ${s[1]}" placeholder="Incolla la chiave">${btn('Annulla', { icon: 'undo', act: 'keyedit', v: lvl + ':' + s[0] })}${btn('Salva', { kind: 'pri', icon: 'save', act: 'keysave', v: lvl + ':' + s[0] })}</div>` : ''}</div>
      <div class="key-v">${k ? `<span class="mono small">••••••••${k.tail}</span>` : ''}${k && shareLbl ? `<label class="key-sh"><span>${shareLbl}</span><button type="button" class="tgl" role="switch" aria-label="${shareLbl}" aria-checked="${!!k.share}" data-act="keyshare" data-v="${lvl}:${s[0]}"><i></i></button></label>` : ''}</div>
      <div class="key-a">${ed ? '' : k ? btn('', { icon: 'pencil', act: 'keyedit', v: lvl + ':' + s[0], al: 'Cambia la chiave di ' + s[1] }) + btn('', { icon: 'trash', act: 'keydel', v: lvl + ':' + s[0], al: 'Togli la chiave di ' + s[1] }) : btn('Aggiungi', { icon: 'plus', act: 'keyedit', v: lvl + ':' + s[0] })}</div></li>`;
  }).join('')}</ul>`).join('');
  return `<section class="panel"><h2>Chiavi dei servizi di mappa</h2><p class="muted small">${intro}</p>${rows}
    <p class="muted small mt">${ic('lock', 12)} Le chiavi si conservano cifrate e non tornano mai al browser: dopo il salvataggio se ne vedono solo le ultime quattro cifre. Le chiavi degli altri servizi (assistente AI, messaggi) sono nel mockup Mosaikit.</p></section>`;
}
function secConfig(S) {
  const mgr = S.role === 'manager', adm = S.role === 'admin', lvl = adm ? 'sys' : mgr ? 'tnt' : 'me';
  const parts = [['Chiavi dei servizi di mappa', 'key', keysSection(S, lvl), 'keys']];
  if (mgr) {
    parts.unshift(['Catalogo pubblico', 'folder', `<section class="panel"><div class="panel-h"><div><h2>Catalogo pubblico (CSW)</h2><p class="muted small">Le schede RNDT dei dati del Comune di Esempio, pubblicate con pycsw in CSW e OGC API Records. Il Repertorio nazionale le raccoglie da qui.</p></div><div class="row">${pill('Raccolto dal Repertorio 2 giorni fa', 'ok', 'check')}${btn('Apri il catalogo pubblico', { icon: 'globe', go: 'pubcat' })}</div></div>
        <dl class="kv"><dt>Indirizzo CSW</dt><dd class="mono small">https://esempio.geoportal.example/csw</dd><dt>OGC API Records</dt><dd class="mono small">https://esempio.geoportal.example/records</dd><dt>Schede pubblicate</dt><dd>161 di 214 dati · 46 da completare</dd></dl></section>
      <section class="panel mt"><h2>Valori dell’ente</h2><p class="muted small">Entrano in ogni scheda nuova; chi compila i metadati di un dato li può cambiare.</p><div class="fgrid">${fld('Codice iPA dell’ente', 'c_x000', { mono: true, hint: 'Dall’Indice delle PA: forma gli identificatori RNDT' })}${fld('Responsabile dei metadati', 'Ufficio SIT · sit@comune.esempio.example')}<label class="lbl">Licenza predefinita<select><option selected>CC-BY 4.0</option><option>IODL 2.0</option><option>Uso interno</option></select></label><label class="lbl">Lingua dei metadati<select><option selected>Italiano</option><option>Inglese</option></select></label></div>
        <ul class="rows mt"><li><div class="rt grow"><b>Revisione del gestore prima della pubblicazione</b><span>Un dato diventa pubblico solo dopo l’approvazione di un gestore: il proprietario la chiede, il gestore approva o rimanda con una nota</span></div>${toggle(1)}</li><li><div class="rt grow"><b>Proponi il catalogo pubblico per i dati nuovi</b><span>Quando i metadati sono completi, l’interruttore del dato parte acceso; il proprietario lo può spegnere</span></div>${toggle(1)}</li></ul></section>`, 'catalog']);
    parts.push(['Mappe', 'map', `<section class="panel"><h2>Mappe nuove</h2><p class="muted small">Da dove parte chi crea una mappa nel tenant; ciascuno poi la sposta dove vuole.</p><div class="fgrid"><label class="lbl">Partono da<select><option>Il territorio comunale</option><option>Il centro storico</option><option>Tutta la regione</option></select></label><label class="lbl">Mappa di base<select><option>Sfondo chiaro</option><option>Ortofoto 2025</option><option>OpenStreetMap</option></select></label><label class="lbl">Unità di misura<select><option>Metriche</option><option>Imperiali</option></select></label></div></section>`, 'maps']);
  }
  const nav = secNav(S, 'cfg', parts);
  const page = adm ? 'Sistema' : 'Configurazione';
  return detailPage(S, { title: page, sub: adm ? 'Le sezioni di Geoportal nel Sistema della piattaforma' : mgr ? 'Le sezioni di Geoportal nella Configurazione del Comune di Esempio' : 'Le tue chiavi dei servizi di mappa',
    body: `<div class="banner info">${ic('cog', 18)}<div>${page} è una pagina del mockup Mosaikit (<span class="mono">mosaikit:${adm ? 'system' : 'config'}</span>): qui ci sono solo le sezioni che Geoportal le aggiunge.</div></div><div class="mt">${nav.body}</div>`, side: nav.side, sideNav: true });
}

/* ── Public catalog, without an account ──────────────────────────────── */

const PUB_DOCS = [{ id: 'doc1', t: 'PUMS — Relazione di piano', f: 'PDF', theme: 'Mobilità', upd: '12/06/2026', size: '8,4 MB · 212 pagine', own: 'Dipartimento Mobilità', d: 'La relazione del Piano urbano della mobilità sostenibile, con gli scenari e il programma degli interventi. Si riferisce alle mappe e ai dati della mobilità qui accanto.' }];
function pubItems(S) {
  const ds = DATASETS.filter(x => x.acc === 'pubblico').map(x => Object.assign({ kind: 'dato' }, x));
  const maps = MAPS.filter(m => m.acc === 'pubblico').map(m => ({ id: m.id, kind: 'mappa', t: m.t, d: m.d, f: 'Mappa', theme: 'Mobilità', upd: m.upd, own: m.own, size: m.layers + ' livelli' }));
  const docs = PUB_DOCS.map(x => Object.assign({ kind: 'documento' }, x));
  const q = (S.pubQ || '').toLowerCase(), f = S.pubF || 'tutti';
  return ds.concat(maps, docs).filter(x => (f === 'tutti' || x.kind === f) && (!q || (x.t + ' ' + x.d + ' ' + x.theme + ' ' + x.f).toLowerCase().includes(q)));
}
function secPubCat(S) {
  setSub(null);
  const tn = TENANT_SEL[0], all = pubItems(Object.assign({}, S, { pubQ: '', pubF: 'tutti' })), list = pubItems(S);
  const sel = S.pubSel ? all.find(x => x.id === S.pubSel) : null;
  const kindIc = { dato: 'database', mappa: 'map', documento: 'file' };
  const head = `<header class="pc-h"><button type="button" class="pc-brand" data-act="pubhome" aria-label="Catalogo pubblico, inizio">${tenantLogo(tn, 36)}<span class="pc-sep"></span><span class="pc-name">Catalogo dei dati</span></button><span class="grow"></span>
      <label class="lg-lang"><span class="sr">Lingua</span><select data-melang>${LANGS.map(l => `<option value="${l[0]}" ${S.lang === l[0] ? 'selected' : ''}>${l[1]}</option>`).join('')}</select></label>${btn('Accedi', { kind: 'pri', icon: 'user', act: 'mk', v: 'login' })}</header>`;
  let body;
  if (sel) {
    const isDoc = sel.kind === 'documento', isMap = sel.kind === 'mappa';
    const meta = [['Ente responsabile', 'Comune di Esempio · ' + (sel.theme === 'Mobilità' ? 'Dipartimento Mobilità' : sel.theme === 'Ambiente' ? 'Dipartimento Ambiente' : 'Ufficio SIT')], ['Contatto', 'sit@comune.esempio.example'], ['Aggiornamento', sel.upd], ['Tema INSPIRE', sel.theme === 'Mobilità' ? 'Reti di trasporto' : sel.theme === 'Ambiente' ? 'Impianti di monitoraggio ambientale' : 'Ortoimmagini'], ['Parole chiave', sel.theme === 'Mobilità' ? 'traffico, mobilità urbana' : 'qualità dell’aria, monitoraggio'], ['Estensione', '11,30 – 11,60 E · 43,60 – 43,85 N'], ['Sistema di riferimento', 'EPSG:6707'], ['Licenza', 'CC-BY 4.0']];
    body = `<div class="pc-wrap"><button type="button" class="lnk small" data-act="pubhome">${ic('back', 13)} Tutti i risultati</button>
      <div class="pc-det"><div class="pc-main"><span class="pc-kind">${ic(kindIc[sel.kind], 14)} ${sel.kind}</span><h1>${sel.t}</h1><p class="pc-abs">${sel.d}</p>
        ${isDoc ? `<div class="pc-doc">${ic('file', 34)}<div><b>${sel.t}.pdf</b><span class="muted small">${sel.size}</span></div>${btn('Scarica il PDF', { kind: 'pri', icon: 'download', act: 'toast', v: 'Scaricamento del PDF avviato.' })}</div>
          <section class="pc-sec"><h2>Dati collegati</h2><ul class="rows">${all.filter(x => x.kind !== 'documento' && x.theme === 'Mobilità').map(x => `<li><span class="ri">${ic(kindIc[x.kind], 15)}</span><div class="rt grow"><b>${x.t}</b><span>${x.f}</span></div>${btn('Apri', { act: 'pubsel', v: x.id })}</li>`).join('')}</ul></section>`
        : `<div class="pc-prev">${mapArt(+String(sel.id).replace(/\D/g, '') || 3, { points: 12, poly: true })}</div><div class="row">${btn(isMap ? 'Apri la mappa' : 'Apri in mappa', { kind: 'pri', icon: 'map', act: 'toast', v: 'Si apre la mappa in sola lettura, senza account: si sposta, si inclina e si interroga.' })}</div>
          ${isMap ? '' : `<section class="pc-sec"><h2>Scarica</h2><div class="row">${['GeoPackage', 'GeoJSON', 'CSV', 'Shapefile'].map(f => btn(f, { icon: 'download', act: 'toast', v: 'Scaricamento in ' + f + ' avviato. Licenza CC-BY 4.0: cita il Comune di Esempio.' })).join('')}</div></section>
          <section class="pc-sec"><h2>Servizi</h2><dl class="kv">${[['WMS', 'https://esempio.geoportal.example/ows?service=WMS&layers=esempio:' + sel.id], ['WFS', 'https://esempio.geoportal.example/ows?service=WFS&typeName=esempio:' + sel.id], ['OGC API Features', 'https://esempio.geoportal.example/ogc/collections/' + sel.id]].map(s => `<dt>${s[0]}</dt><dd class="mono small pc-url">${s[1].replace(/&/g, '&amp;')}</dd>`).join('')}</dl></section>`}`}
      </div><aside class="pc-side"><h2>Metadati</h2><dl class="kv">${meta.map(m => `<dt>${m[0]}</dt><dd>${m[1]}</dd>`).join('')}</dl>
        <div class="pc-perma"><span class="muted small">Indirizzo stabile</span><span class="mono small">https://esempio.geoportal.example/r/${sel.id}</span>${btn('', { icon: 'copy', al: 'Copia l’indirizzo', act: 'toast', v: 'Indirizzo copiato: non cambia se la risorsa si rinomina.' })}</div>
        <p class="small muted">Metadati: <a>XML ISO 19139 (RNDT)</a> · <a>DCAT-AP_IT</a></p></aside></div></div>`;
  } else {
    const n = k => all.filter(x => k === 'tutti' || x.kind === k).length;
    body = `<section class="pc-hero"><h1>I dati del Comune di Esempio, aperti a tutti</h1><p>Mappe, dati e documenti che l’ente pubblica: si cercano, si guardano sulla mappa, si scaricano e si usano come servizi.</p>
        <label class="pc-search">${ic('search', 18)}<span class="sr">Cerca</span><input type="search" data-pubq value="${(S.pubQ || '').replace(/"/g, '&quot;')}" placeholder="Cerca per nome, tema o formato"></label></section>
      <div class="pc-wrap pc-grid"><aside class="pc-f"><div class="eyebrow">Tipo</div>${[['tutti', 'Tutto'], ['dato', 'Dati'], ['mappa', 'Mappe'], ['documento', 'Documenti']].map(k => `<button type="button" class="pc-fb ${(S.pubF || 'tutti') === k[0] ? 'on' : ''}" data-act="pubf" data-v="${k[0]}" aria-pressed="${(S.pubF || 'tutti') === k[0]}"><span>${k[1]}</span><span class="num muted">${n(k[0])}</span></button>`).join('')}
        <div class="eyebrow mt">Tema</div>${['Mobilità', 'Ambiente', 'Territorio'].map(t => `<div class="pc-fr"><span>${t}</span><span class="num muted">${all.filter(x => x.theme === t).length}</span></div>`).join('')}
        <div class="eyebrow mt">Licenza</div><div class="pc-fr"><span>CC-BY 4.0</span><span class="num muted">${all.length}</span></div></aside>
        <div class="pc-res"><p class="muted small">${list.length} ${list.length === 1 ? 'risultato' : 'risultati'}</p>${list.length ? list.map(x => `<button type="button" class="pc-card" data-act="pubsel" data-v="${x.id}"><span class="pc-kind">${ic(kindIc[x.kind], 14)} ${x.kind}</span><b>${x.t}</b><span class="pc-d">${x.d}</span><span class="pc-m">${pill(x.f)}${pill(x.theme)}<span class="muted small">Aggiornato ${x.upd}</span></span></button>`).join('') : `<div class="empty">${ic('search', 26)}<b>Nessun risultato</b><span class="muted">Prova con un tema, per esempio «mobilità».</span></div>`}</div></div>`;
  }
  return `<div class="pc-page">${head}${body}<footer class="pc-foot"><nav class="lg-legal2"><a>Dichiarazione di accessibilità</a><a>Privacy</a><a>Note legali</a><a>Condizioni d’uso dei dati</a></nav><span class="small muted">Per gli aggregatori: <span class="mono">CSW · OGC API Records · DCAT-AP_IT</span></span><span class="small muted">Pubblicato con Geoportal</span></footer></div>`;
}

/* ── Dialogs of Geoportal ────────────────────────────────────────────── */

/* Create: the entries Geoportal adds to the Create dialog of Mosaikit. */
DIALOGS.new = () => ({ html: `<div class="dlg-h"><h2>Crea</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b"><div class="newg">${[['map', 'Mappa', 'Editor GeoLibre, da zero o da un modello', 'map-edit'], ['dash', 'Cruscotto', 'Sette modelli per dominio', 'dash-edit'], ['flow', 'Processo', 'GeoFlow, oppure importa un modello GeoLibre', 'process-edit'], ['upload', 'Carica dati', 'In PostGIS o come raster, pubblicato su GeoServer', '#upload'], ['link', 'Collega una fonte', 'Servizio OGC, flusso, database, elaborazione', '#connect']].map(n => `<button type="button" class="newi" ${n[3][0] === '#' ? `data-act="${n[3].slice(1)}"` : `data-go="${n[3]}"`}>${ic(n[0], 22)}<b>${n[1]}</b><span>${n[2]}</span></button>`).join('')}</div><p class="muted small mt">Sono le voci che Geoportal aggiunge alla finestra Crea di Mosaikit (<span class="mono">mosaikit:dlg-create</span>).</p></div>` });
DIALOGS.upload = S => {
  const err = S.upErr, lv = S.upLv || ['quartieri', 'municipi', 'zone_urbanistiche'];
  return { html: `<div class="dlg-h"><h2>Carica dati</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b stack">
      <div class="drop">${ic('upload', 26)}<b>${err ? 'confini_storici.shp' : 'quartieri_2026.gpkg'}</b><span class="muted small">${err ? '12,1 MB · 1 livello' : '48,2 MB · 3 livelli trovati'}</span></div>
      ${err ? `<div class="banner bad">${ic('alert', 18)}<div><b>Non si capisce in che sistema di riferimento è.</b> Il file non ha il .prj. Sceglilo tu: dalla forma dei dati sembra RDN2008 / UTM 32N.</div></div>
      <label class="lbl">Sistema di riferimento<select><option>EPSG:6707 · RDN2008 / UTM zone 32N (suggerito)</option><option>EPSG:32632 · WGS 84 / UTM 32N</option><option>EPSG:4326 · WGS 84</option></select></label>`
      : `<div><div class="lbl-h inv-l">Quali livelli</div><div class="gchips">${['quartieri', 'municipi', 'zone_urbanistiche'].map(l => { const on = lv.includes(l); return `<button type="button" class="gchip tg ${on ? 'on' : ''}" data-act="uplv" data-v="${l}" aria-pressed="${on}">${on ? ic('check', 12) : ic('plus', 12)}<span class="mono">${l}</span></button>`; }).join('')}</div></div>
      <ol class="steps">${[['ok', 'Letto e controllato', 'EPSG:6707, ' + lv.length + ' livelli, 1.214 poligoni'], ['ok', 'Preparato per la mappa e per le analisi', 'Si apre veloce anche con molti elementi'], ['run', 'Pubblicato nel tenant', 'Quasi fatto…'], ['todo', 'Chi lo vede', 'Ti propongo: solo tu. Puoi cambiarlo adesso.']].map(s => `<li class="st-${s[0]}"><i></i><div><b>${s[1]}</b><span class="muted small">${s[2]}</span></div></li>`).join('')}</ol>
      <div class="row"><span class="lbl">Chi lo vede</span><button type="button" class="chip">${ic('lock', 12)} Solo io ${ic('down', 12)}</button><span class="grow"></span><span class="muted small">Spazio: 7,4 di 25 GB</span></div>`}
      <button type="button" class="lnk small" data-act="uperr">${err ? 'Torna al caricamento riuscito' : 'Vedi un caricamento con un problema'}</button></div>
      <div class="dlg-f">${btn('Annulla il caricamento', { act: 'close' })}<span class="grow"></span>${err ? btn('Riprova', { kind: 'pri', icon: 'refresh', act: 'uperr' }) : btn('Apri la scheda', { go: 'dataset' }) + btn('Aggiungi a una mappa…', { act: 'toast', v: 'Scegli la mappa: il dato entra come livello nuovo, in cima.' }) + btn('Apri in una mappa nuova', { kind: 'pri', icon: 'map', act: 'dsmap', v: 'new' })}</div>` };
};
DIALOGS.connect = () => ({ html: `<div class="dlg-h"><h2>Nuova connessione</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b"><div class="newg">${[['globe', 'Servizio OGC', 'WMS, WMTS, WFS, WCS, OGC API'], ['radio', 'Flusso in tempo reale', 'Kafka, MQTT, WebSocket, SSE'], ['database', 'Database', 'PostGIS, Trino, SQL Server, Oracle'], ['cpu', 'Elaborazione', 'OGC API Processes, WPS'], ['folder', 'Catalogo', 'CSW, OGC API Records, STAC'], ['box', 'Archivio oggetti', 'S3, MinIO, Azure']].map(n => `<button type="button" class="newi" data-act="okmodal" data-v="Nel prodotto si compilano indirizzo e credenziali: Geoportal prova il collegamento prima di salvare.">${ic(n[0], 22)}<b>${n[1]}</b><span>${n[2]}</span></button>`).join('')}</div><p class="muted small mt">Ogni fonte collegata diventa una voce del catalogo con gli stessi permessi dei file. Le credenziali restano in Geoportal.</p></div>` });
/* A data rule: for whom, on which data, which area, which rows, which columns. The preview says what is seen. */
DIALOGS.rule = S => {
  const R = S.rule || (S.rule = { ds: S.ruleCtx || 'd4', who: 'Mobilità', area: 'Municipio 1', rows: "stato = 'attivo'", hide: ['cf_titolare'] });
  const cols = ['foglio', 'particella', 'superficie', 'rendita', 'cf_titolare', 'indirizzo'];
  const ds = DATASETS.find(x => x.id === R.ds) || DATASETS[3];
  const n = R.area === 'Tutto il comune' ? 61204 : R.area === 'Municipio 1' ? 4812 : 1214;
  return { wide: true, html: `<div class="dlg-h"><h2>Regola sul dato</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b"><div class="rule-g"><div class="stack">
      <div class="fgrid"><label class="lbl">Dato<select data-rule="ds">${DATASETS.filter(x => x.host === 'geoserver').map(x => `<option value="${x.id}" ${x.id === R.ds ? 'selected' : ''}>${x.t}</option>`).join('')}</select></label>
        <label class="lbl">Per chi<select data-rule="who">${GROUPS.map(g => `<option ${g.n === R.who ? 'selected' : ''}>${g.n}</option>`).join('')}<option ${R.who === 'link' ? 'selected' : ''} value="link">Chiunque con il link</option></select></label></div>
      <div><div class="lbl-h inv-l">Area</div><div class="opts row3">${['Tutto il comune', 'Municipio 1', 'Disegnata sulla mappa'].map(a => `<button type="button" class="opt ${R.area === a ? 'sel' : ''}" data-act="rulearea" data-v="${a}" aria-pressed="${R.area === a}"><b>${a}</b></button>`).join('')}</div></div>
      <label class="lbl">Righe che vede<input type="text" class="mono" data-rule="rows" value="${R.rows.replace(/"/g, '&quot;')}" placeholder="per esempio: stato = 'attivo'"><span class="hint">Un filtro sui campi del dato; vuoto, vede tutte le righe dell’area</span></label>
      <div><div class="lbl-h inv-l">Colonne nascoste</div><div class="gchips">${cols.map(c => { const on = R.hide.includes(c); return `<button type="button" class="gchip tg ${on ? 'on' : ''}" data-act="rulecol" data-v="${c}" aria-pressed="${on}">${on ? ic('x', 12) : ic('eye', 12)}<span class="mono">${c}</span></button>`; }).join('')}</div></div></div>
      <aside class="rule-p"><div class="eyebrow">Che cosa vede ${R.who === 'link' ? 'chi ha il link' : 'il gruppo ' + R.who}</div><div class="rule-map">${mapArt(4, { points: 12, poly: true })}</div>
        <dl class="kv"><dt>Elementi</dt><dd class="num">${fmtN(R.rows ? Math.round(n * 0.8) : n)} di ${fmtN(61204)}</dd><dt>Colonne</dt><dd>${cols.length - R.hide.length} di ${cols.length}${R.hide.length ? ' · senza ' + R.hide.join(', ') : ''}</dd><dt>Vale anche</dt><dd>Da QGIS, perché la applica GeoFence</dd></dl></aside></div></div>
      <div class="dlg-f"><span class="muted small">${ds.t}: la regola restringe, non concede. Chi non vede il dato non lo vede neanche con la regola.</span>${btn('Annulla', { act: 'close' })}${btn('Salva la regola', { kind: 'pri', act: 'rulesave' })}</div>` };
};
DIALOGS.adddata = () => ({ wide: true, html: `<div class="dlg-h"><h2>Aggiungi dati</h2>${btn('', { icon: 'x', act: 'close' })}</div>
    <div class="dlg-b split"><nav class="vtabs">${['Catalogo Geoportal', 'Il mio computer', 'URL o servizio', 'STAC', 'Database (via Geoportal)', 'Tempo reale'].map((x, i) => `<button type="button" aria-selected="${i === 0}">${x}</button>`).join('')}</nav>
    <div class="dlg-list"><div class="field">${ic('search', 15)}<input type="text" placeholder="Cerca tra i 214 dati che puoi vedere" aria-label="Cerca"></div>${DATASETS.slice(0, 7).map(d => `<label class="pick"><input type="checkbox" ${d.id === 'd2' ? 'checked' : ''}>${kindIcon(d.k)}<span><b>${d.t}</b><span class="muted small">${d.f} · ${HOSTS[d.host].label}</span></span>${accPill(d.acc)}</label>`).join('')}</div></div>
    <div class="dlg-f"><span class="muted small">Vedi solo i dati che puoi vedere; le credenziali dei database restano in Geoportal.</span>${btn('Aggiungi 1 dato', { kind: 'pri', act: 'okmodal', v: 'Livello aggiunto in cima, con simbolo e legenda.' })}</div>` });
DIALOGS.access = S => ({ html: `<div class="dlg-h"><h2>Chiedi l’accesso</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b stack">
      <p><b>${S.accessWhat || 'Ortofoto 2025, 20 cm'}</b> è riservato. La richiesta va al proprietario e ai gestori del tenant; ti arriva una notifica quando decidono.</p>
      <label class="lbl">Che cosa ti serve<select><option>Vedere</option><option>Vedere e scaricare</option></select></label>
      <label class="lbl">Perché<textarea rows="3" placeholder="Per esempio: mi serve per la mappa del piano di emergenza"></textarea></label></div>
      <div class="dlg-f"><span class="grow"></span>${btn('Annulla', { act: 'close' })}${btn('Manda la richiesta', { kind: 'pri', icon: 'mail', act: 'okmodal', v: 'Richiesta mandata a Elena Ricci e ai gestori.' })}</div>` });
DIALOGS.versions = S => {
  const kind = S.route.startsWith('dash') ? 'dash' : 'map', r = curRes(S, kind);
  const vs = [['Oggi 12:04', 'Elena Ricci', 'Soglie di allerta aggiornate', 1], ['Ieri 17:30', 'Davide Conti', 'Aggiunto il livello dei flussi in diretta', 0], ['03/10/2026', 'Elena Ricci', 'Stile delle stazioni per portata', 0], ['28/09/2026', 'Elena Ricci', 'Prima versione', 0]];
  return { html: `<div class="dlg-h"><h2>Versioni di «${r.t}»</h2>${btn('', { icon: 'x', act: 'close' })}</div><div class="dlg-b"><ul class="rows">${vs.map(v => `<li><div class="rt grow"><b>${v[2]}</b><span>${v[0]} · ${v[1]}</span></div>${v[3] ? pill('Attuale', 'ok') : (canEdit(S, kind) ? btn('Ripristina', { act: 'okmodal', v: 'Versione del ' + v[0] + ' ripristinata: quella di prima resta nella cronologia.' }) : btn('Apri', { act: 'okmodal', v: 'Aperta la versione del ' + v[0] + ', in sola lettura.' }))}</li>`).join('')}</ul></div>
      <div class="dlg-f"><span class="muted small">Ogni salvataggio è una versione. Ripristinare non cancella niente.</span>${btn('Chiudi', { act: 'close' })}</div>` };
};

/* ── The table of screens ────────────────────────────────────────────── */

const SECTIONS = {
  home: secHome, catalog: secCatalog, dataset: secDataset, conn: secConn, maps: secMaps, 'map-edit': S => secMapEdit(S, S.role === 'collab' && !canEdit(S, 'map')), 'map-view': S => secMapEdit(S, true),
  dashboards: secDashboards, 'dash-view': secDashView, 'dash-edit': S => S.role === 'collab' && !canEdit(S, 'dash') ? secDashView(S) : secDashEdit(S),
  processes: secProcesses, 'process-edit': S => secProcessEdit(S, S.role === 'collab' && !canEdit(S, 'proc')), 'process-view': S => secProcessEdit(S, true), runs: secRuns,
  pubcat: secPubCat, config: secConfig
};
