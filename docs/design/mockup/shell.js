// SPDX-FileCopyrightText: 2026 Massimo Antonini

/* Geoportal mockup: the frame of Mosaikit (top band, sidebar, menus) with the Geoportal theme, the
   review bar with its index and notes, and the events. The generic pages and dialogs of the frame
   belong to the Mosaikit mockup: here their entries are greyed and say so. Loaded last. */

const MOCKUP = 'geoportal';
const ALL = ['user', 'collab', 'manager', 'admin'];

const S = {
  role: 'user', route: 'home', ds: 'd4', tab: {}, modal: null, notes: false, map: false, drawer: false, engine3d: false, side: 'mini', mode: 'auto', lang: 'it', hc: false,
  tenantMenu: false, tenant: null, helpMenu: false, userMenu: false, themeMenu: false, keyEdit: null,
  keys: { sys: { terrain3d: { tail: '77d2', share: true }, photo3d: { tail: 'e5b1', share: false }, geocode: { tail: '1f3a', share: true }, sat: { tail: '0b8c', share: true } }, tnt: { photo3d: { tail: '4a6e', share: false } }, me: {} }
};
/* Where each role lands in this mockup. The first entry of the sidebar stays the one of Mosaikit
   (greyed for manager and administrator, whose overviews are Mosaikit pages). */
const LANDING = { user: 'home', collab: 'home', manager: 'catalog', admin: 'config' };
const MK_TOP = { user: 'home', collab: 'home', manager: 'space', admin: 'platform' };

/* ── Notes: what each screen is for ──────────────────────────────────── */

const DECISIONE = {
  idea: 'Geoportal è un insieme di estensioni di Mosaikit: <b>Catalogo</b>, <b>Mappe</b>, <b>Cruscotti</b> e <b>Processi</b>, più il catalogo pubblico e le sezioni geografiche della configurazione. Questo mockup mostra solo le loro pagine, dentro la cornice di Mosaikit con il tema e il logo di Geoportal. Le voci grigie della barra laterale sono pagine del mockup Mosaikit. La mappa è GeoLibre incorporato.',
  roles: { user: 'Panoramica (i riquadri che Geoportal aggiunge), Catalogo, Mappe, Cruscotti, Processi. Atterra sulla Panoramica.', collab: 'Un Utente con il profilo Consultazione e modifica: vede solo ciò che gli è condiviso e non crea, perché il profilo non gli dà i permessi per farlo.', manager: 'Le voci del tenant sono di Mosaikit (grigie); di Geoportal ha le quattro voci di lavoro e le sue sezioni della Configurazione. Qui atterra sul Catalogo.', admin: 'Le voci di piattaforma sono di Mosaikit (grigie). Di Geoportal vede solo le sue sezioni del Sistema: le chiavi dei servizi di mappa. Mappe e dati di un tenant non li vede.' },
  how: 'Per verificare: scegli il ruolo in alto, apri l&#39;<b>Indice</b> e percorri ogni schermata e finestra. «Segna verificata» tiene il conto per ruolo in questo browser. L&#39;etichetta con l&#39;identificativo della schermata è quella che un requisito cita nel campo <span class="mono">ui</span>.'
};
const SCREEN_NOTES = {
  home: 'Geoportal aggiunge alla Panoramica di Mosaikit i riquadri delle sue quattro app, il lavoro da riprendere e le allerte attive. Notifiche e spazio dati sono riquadri di Mosaikit.',
  catalog: 'Un solo catalogo per sei tipi di dato: file, servizi OGC, flussi in tempo reale, database, elaborazioni, cataloghi. Ogni riga dice <i>dove sta</i> (GeoServer, Geoportal, archivio oggetti, remoto) e <i>chi lo vede</i>. «Mostra» passa alle connessioni da cui i dati nascono.',
  dataset: 'La scheda <b>Metadati RNDT</b> separa i campi da compilare da quelli che Geoportal ricava da solo; completi, vanno nel catalogo CSW (pycsw). I <b>permessi</b> hanno cinque livelli (vedere, scaricare, modificare, modificare i dati, gestire); le <b>regole sul dato</b> (area, righe, colonne) diventano regole GeoFence per i dati su GeoServer e regole del gateway per gli altri (GP-308, GP-309, GP-042).',
  conn: 'Una connessione non è un dato: da lei ne nascono molti. Si condivide per usarla (crearne dati senza vederne le credenziali) o per gestirla.',
  maps: 'Le schede mostrano chi vede la mappa e chi ci sta lavorando adesso (GP-027, GP-039).',
  'map-edit': 'L\'editor è GeoLibre in un iframe, guidato con <span class="mono">@geolibre/embed</span>. Il plugin Geoportal porta dentro il catalogo, il salvataggio e i flussi in tempo reale. La sessione condivisa usa il relay con un\'identità firmata (GP-044). Prova «Aggiungi dati» e il passaggio 2D/3D in basso a destra.',
  'map-view': 'La mappa si apre sempre in vista. Chi può modificarla trova lo scambio Modifica/Vista nella barra; chi non può vede «Sola lettura». L\'utente modifica Rete di monitoraggio, ZTL e varchi, Isocrone TPL.',
  dashboards: 'Ogni cruscotto con una mappa usa lo stesso componente dell\'editor.',
  'dash-view': 'Il widget mappa è GeoLibre incorporato: <span class="mono">viewChanged</span> diventa il filtro spaziale e <span class="mono">selectionChanged</span> aggiunge il filtro incrociato su un clic.',
  'dash-edit': 'Il pannello a destra mostra che cosa passa fra cruscotto e mappa.',
  processes: 'GeoFlow resta il motore dei processi: esecuzione sul server, pianificazione, eventi, flussi, AI.',
  'process-view': 'La vista del processo usa la stessa cornice della mappa e del cruscotto: grafo in sola lettura, ultima esecuzione a destra, Esegui e Cronologia.',
  'process-edit': 'Il Model Builder di GeoLibre è lineare e gira solo nel browser: non sostituisce GeoFlow. Entra come nodi del sidecar (conversioni, Sedona, SAM 3) e come modelli importati.',
  runs: 'Il registro dice perché un\'esecuzione è fallita e che cosa fare, non solo che è fallita.',
  pubcat: 'Il catalogo pubblico, senza account (GP-241–GP-246): quello che il tenant ha dichiarato pubblico, con ricerca, scheda RNDT, anteprima, scarico e servizi.',
  config: 'Le sezioni che Geoportal aggiunge alla Configurazione (gestore, utente) e al Sistema (amministratore) di Mosaikit: i valori RNDT dell\'ente per il catalogo pubblico, le chiavi dei servizi di mappa, le impostazioni delle mappe nuove.'
};

/* ── Navigation ───────────────────────────────────────────────────────
   [group, id, label, icon, roles, owner]. Entries owned by «mk» are pages of the Mosaikit mockup:
   greyed, they show a note instead of rendering. */
const ROUTES = [
  ['lavoro', 'home', 'Panoramica', 'overview', ['user', 'collab']],
  ['lavoro', 'catalog', 'Catalogo', 'database', ['user', 'collab', 'manager']],
  ['lavoro', 'maps', 'Mappe', 'map', ['user', 'collab', 'manager']],
  ['lavoro', 'dashboards', 'Cruscotti', 'dash', ['user', 'collab', 'manager']],
  ['lavoro', 'processes', 'Processi', 'flow', ['user', 'collab', 'manager']],
  ['spazio', 'space', 'Panoramica', 'overview', ['manager'], 'mk'],
  ['spazio', 'members', 'Utenti del tenant', 'users', ['manager'], 'mk'],
  ['spazio', 'splugins', 'Moduli', 'puzzle', ['manager'], 'mk'],
  ['spazio', 'usage', 'Consumi', 'pie', ['manager'], 'mk'],
  ['spazio', 'activity', 'Registro attività', 'history', ['manager'], 'mk'],
  ['piattaforma', 'platform', 'Panoramica', 'overview', ['admin'], 'mk'],
  ['piattaforma', 'tenants', 'Tenants', 'building', ['admin'], 'mk'],
  ['piattaforma', 'gusers', 'Utenti globali', 'users', ['admin'], 'mk'],
  ['piattaforma', 'marketplace', 'Marketplace', 'bag', ['admin'], 'mk'],
  ['piattaforma', 'monitoring', 'Monitoraggio', 'activity', ['admin'], 'mk'],
  ['piattaforma', 'audit', 'Registro attività', 'history', ['admin'], 'mk'],
  ['fondo', 'config', 'Sistema', 'cog', ['admin']],
  ['fondo', 'config', 'Configurazione', 'cog', ['manager', 'user', 'collab']]
];
/* Screens reached from others: [label, the sidebar entry that stays lit, roles]. */
const HIDDEN = {
  dataset: ['Scheda del dato', 'catalog', ['user', 'collab', 'manager']], conn: ['Scheda della connessione', 'catalog', ['user', 'manager']],
  'map-edit': ['Editor di mappa', 'maps', ['user', 'collab', 'manager']], 'map-view': ['Vista di mappa', 'maps', ['user', 'collab', 'manager']],
  'dash-view': ['Cruscotto', 'dashboards', ['user', 'collab', 'manager']], 'dash-edit': ['Editor di cruscotto', 'dashboards', ['user', 'manager']],
  'process-view': ['Vista di processo', 'processes', ['user', 'collab', 'manager']], 'process-edit': ['Editor di processo', 'processes', ['user', 'manager']], runs: ['Esecuzioni', 'processes', ['user', 'collab', 'manager']],
  pubcat: ['Catalogo pubblico', null, ALL]
};
const FULLSCREEN = ['map-edit', 'map-view', 'dash-edit', 'dash-view', 'process-edit', 'process-view'];
/* Pages of the Mosaikit mockup that Geoportal pages link to: route -> label. */
const MK_PAGES = { space: 'Panoramica del tenant', members: 'Utenti del tenant', splugins: 'Moduli', usage: 'Consumi', activity: 'Registro attività', platform: 'Panoramica della piattaforma', tenants: 'Tenants', gusers: 'Utenti globali', marketplace: 'Marketplace', monitoring: 'Monitoraggio', audit: 'Registro attività', inbox: 'Notifiche', profile: 'Dati personali', trash: 'Cestino', login: 'Accesso', system: 'Sistema' };
const GROUP_LABEL = { lavoro: 'Lavoro', spazio: 'Gestione del tenant', piattaforma: 'Piattaforma', fondo: 'Impostazioni' };
function navFor(role) {
  const order = role === 'admin' ? ['piattaforma', 'spazio', 'lavoro', 'fondo'] : ['spazio', 'lavoro', 'fondo'];
  const first = ROUTES.find(r => r[1] === MK_TOP[role] && r[4].includes(role));
  const groups = order.map(g => [g, ROUTES.filter(r => r[0] === g && r[4].includes(role) && r !== first)]).filter(x => x[1].length);
  return [['top', [first]], ...groups];
}
function activeTop(route) { return HIDDEN[route] ? HIDDEN[route][1] || route : route; }
function navItem(r) {
  if (r[5] === 'mk') {
    const tip = `${r[2]} · pagina del mockup Mosaikit`;
    return `<button type="button" class="nv off" data-act="mk" data-v="${r[1]}" aria-disabled="true" data-stip="${tip}" aria-label="${tip}">${ic(r[3], 17)}<span>${r[2]}</span></button>`;
  }
  const on = activeTop(S.route) === r[1];
  return `<button type="button" class="nv ${on ? 'on' : ''}" data-go="${r[1]}" ${on ? 'aria-current="page"' : ''} data-stip="${r[2]}" aria-label="${r[2]}">${ic(r[3], 17)}<span>${r[2]}</span></button>`;
}

/* ── Theme: the Geoportal theme, and high contrast ─────────────────────
   Each theme redefines the tokens of three zones (top band, sidebar, content) plus the accent. */
function isDark() {
  const t = document.documentElement.dataset.theme;
  return t ? t === 'dark' : !matchMedia('(prefers-color-scheme: light)').matches;
}
const PRESETS = [
  { id: 'geoportal', name: 'Geoportal',
    light: { nav: ['#444791', '#ffffff', '#ecedfb', '#d9daf6', '#e8e9fa', 'rgba(255,255,255,.16)', '#444791', 'rgba(255,255,255,.35)'], navInput: ['#e8e9fa', '#4f5170'], side: ['#ebebeb', '#242424', '#424242', '#616161', '#e0e0e0', '#e0e0e0', '#ffffff', '#5b5fc7'], pane: '#f5f5f5', content: '#ffffff', accent: ['#5b5fc7', 'rgba(91,95,199,.10)', '#ffffff'] },
    dark: { nav: ['#2b2c4a', '#ffffff', '#dcddf7', '#b7b9e8', '#3b3d63', 'rgba(255,255,255,.12)', '#2b2c4a', 'rgba(255,255,255,.25)'], navInput: ['#3b3d63', '#d0d1ee'], side: ['#141414', '#f0f0f0', '#d1d1d1', '#adadad', '#2b2b2b', '#2b2b2b', '#2e2e2e', '#9ea2ff'], pane: '#242424', content: '#1f1f1f', accent: ['#7f85f5', 'rgba(127,133,245,.16)', '#0e0f2a'] } },
  { id: 'contrasto', name: 'Alto contrasto',
    light: { nav: ['#000000', '#ffffff', '#ffffff', '#e5e5e5', '#000000', '#333333', '#000000', '#ffffff'], side: ['#000000', '#ffffff', '#ffffff', '#d4d4d4', '#333333', '#555555', '#ffd400', '#000000'], content: '#ffffff', accent: ['#0040a0', 'rgba(0,64,160,.12)', '#ffffff'] },
    dark: { nav: ['#000000', '#ffffff', '#ffffff', '#e5e5e5', '#000000', '#333333', '#000000', '#ffffff'], side: ['#000000', '#ffffff', '#ffffff', '#d4d4d4', '#333333', '#555555', '#ffd400', '#000000'], content: '#000000', accent: ['#ffd400', 'rgba(255,212,0,.2)', '#000000'] } }
];
const MODES = [['light', 'Chiaro', 'sun'], ['dark', 'Scuro', 'moon'], ['auto', 'Automatico', 'contrast']];
function presetCss(id) {
  const p = PRESETS.find(x => x.id === id), v = isDark() ? p.dark : p.light, n = v.nav, s = v.side, a = v.accent;
  return `#stage { --accent: ${a[0]}; --accent-soft: ${a[1]}; --on-accent: ${a[2]}; }
#stage .top { --bg2: ${n[0]}; --text: ${n[1]}; --text2: ${n[2]}; --text3: ${n[3]}; --bg: ${n[4]}; --bg3: ${n[5]}; --line: ${n[6]}; --line2: ${n[7]}; --accent-soft: rgba(255,255,255,.16); --accent: ${n[1]}; --on-accent: ${n[0]}; }
#stage .side { --bg2: ${s[0]}; --text: ${s[1]}; --text2: ${s[2]}; --text3: ${s[3]}; --bg3: ${s[4]}; --line: ${s[5]}; --accent-soft: ${s[6]}; --accent: ${s[7]}; }
#stage .main, #stage .editor { --bg: ${v.content}; }${v.pane ? `
#stage .rlay > .facets, #stage .dside > .facets { background: ${v.pane}; }
#stage .facet.scope label:has(input:checked) { background: ${isDark() ? '#333333' : '#ffffff'}; box-shadow: 0 1px 2px rgba(0,0,0,.08); }` : ''}${v.navInput ? `
#stage .top .sbox { background: ${v.navInput[0]}; color: ${v.navInput[1]}; border-color: transparent; }
#stage .top .sbox kbd { color: ${v.navInput[1]}; border-color: ${v.navInput[1]}55; }` : ''}
#stage .side-h { background: ${n[0]}; border-bottom-color: ${n[6]}; --text: ${n[1]}; --text2: ${n[2]}; --text3: ${n[3]}; --bg3: ${n[5]}; --accent: ${n[1]}; --on-accent: ${n[0]}; }`;
}
function applyPreset() {
  let st = document.getElementById('preset-css');
  if (!st) { st = document.createElement('style'); st.id = 'preset-css'; document.head.appendChild(st); }
  st.textContent = presetCss(S.hc ? 'contrasto' : 'geoportal');
}
function applyMode() {
  if (S.mode === 'auto') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = S.mode;
}

/* ── Menus of the band (frame of Mosaikit) ───────────────────────────── */

function menuRight() { return S.notes && innerWidth > 1100 ? 356 : 16; }
function themeMenuHtml() {
  if (!S.themeMenu) return '';
  return `<div class="tmenu" role="dialog" aria-label="Aspetto" style="right:${menuRight()}px">
    <div class="eyebrow">Modalità</div>
    <div class="tm-modes" role="radiogroup" aria-label="Modalità">${MODES.map(m => `<button type="button" role="radio" aria-checked="${S.mode === m[0]}" data-mode="${m[0]}">${ic(m[2], 16)}<span>${m[1]}</span></button>`).join('')}</div>
    <div class="tm-row"><div><b>Alto contrasto</b><span class="muted small">Colori più netti, per chi legge con fatica</span></div><button type="button" class="tgl" role="switch" data-act="hc" aria-label="Alto contrasto" aria-checked="${!!S.hc}"><i></i></button></div>
    <p class="muted small">Valgono solo per te e si applicano subito. Temi e aspetto sono pagine del mockup Mosaikit.</p></div>`;
}
function userMenuHtml() {
  if (!S.userMenu) return '';
  const R = ROLES[S.role], adm = S.role === 'admin', tn = curTenant();
  const it = (icon, label, attr) => `<button type="button" class="hm-i" role="menuitem" ${attr}>${ic(icon, 16)}<span>${label}</span></button>`;
  return `<div class="tmenu hmenu umenu" role="menu" aria-label="Menu di ${R.who}" style="right:${menuRight()}px">
    <div class="um-h">${av(R.ini, adm ? 5 : S.role === 'manager' ? 2 : 3, 40)}<div><b>${R.who}</b><span class="muted small">${R.mail}</span><span class="small">${R.label} · ${tn.name}</span></div></div>
    <div class="hm-l">${it('user', 'Dati personali', 'data-go="profile"')}${it('cog', adm ? 'Sistema' : 'Configurazione', 'data-go="config"')}${it('bell', 'Notifiche', 'data-go="inbox"')}</div>
    <div class="hm-l um-out">${it('back', 'Esci', 'data-go="login"')}</div></div>`;
}
function helpMenuHtml() {
  if (!S.helpMenu) return '';
  return `<div class="tmenu hmenu" role="dialog" aria-label="Guida" style="right:${menuRight()}px">
    <div class="hm-l">${[['play', 'Visita guidata di questa pagina'], ['help', 'Guida di Geoportal'], ['star', 'Novità della versione 1.4']].map(x => `<button type="button" class="hm-i" data-act="toast" data-v="Nel prodotto: ${x[1].toLowerCase()}.">${ic(x[0], 16)}<span>${x[1]}</span></button>`).join('')}</div>
    <div class="hm-v">${gpLogo(18)}<div><b>Geoportal 1.4.0</b><span class="muted small">Su Mosaikit 1.0 · aggiornato il 02/10/2026</span></div></div></div>`;
}
function curTenant() { return S.role === 'admin' ? SISTEMA : TENANT_SEL.find(x => x.id === S.tenant) || TENANT_SEL[0]; }
function topRight() {
  const R = ROLES[S.role], n = S.role === 'collab' ? 1 : 2;
  return `<div class="tr"><button type="button" class="ib ${S.themeMenu ? 'on' : ''}" data-act="theme" aria-haspopup="dialog" aria-expanded="${S.themeMenu}" aria-label="Aspetto" data-tip="${S.themeMenu ? '' : 'Aspetto: modalità e contrasto'}">${ic('contrast', 18)}</button><button type="button" class="ib ${S.helpMenu ? 'on' : ''}" data-act="helpmenu" aria-haspopup="dialog" aria-expanded="${!!S.helpMenu}" aria-label="Guida" data-tip="${S.helpMenu ? '' : 'Guida e versione'}">${ic('help', 18)}</button><button type="button" class="ib" data-go="inbox" aria-label="Notifiche, ${n} nuove" data-tip="Notifiche · ${n} nuove">${ic('bell', 18)}<em>${n}</em></button><button type="button" class="me ${S.userMenu ? 'on' : ''}" data-act="usermenu" aria-haspopup="menu" aria-expanded="${!!S.userMenu}" aria-label="Menu di ${R.who}" data-tip="${S.userMenu ? '' : 'Dati personali e impostazioni'}" data-tip-end><span class="me-t"><b>${R.who}</b><span>${R.label}</span></span>${av(R.ini, S.role === 'admin' ? 5 : S.role === 'manager' ? 2 : 3, 30)}</button></div>`;
}
function tenantSwitch() {
  if (S.role === 'admin') return `<div class="tsw static" role="img" aria-label="${SISTEMA.name}">${tenantLogo(SISTEMA, 34)}</div>`;
  const tn = curTenant();
  return `<button type="button" class="tsw" data-act="tenants" aria-haspopup="listbox" aria-expanded="${!!S.tenantMenu}" aria-label="Tenant: ${tn.name}. Cambia tenant" data-tip="${S.tenantMenu ? '' : `${tn.name} · cambia tenant`}" data-tip-start>${tenantLogo(tn, 34)}${ic('down', 14)}</button>`;
}
function tenantMenuHtml() {
  if (!S.tenantMenu || S.role === 'admin') return '';
  const cur = curTenant(), mine = MY_TENANTS[S.role];
  const item = m => { const tn = TENANT_SEL.find(x => x.id === m[0]); return `<button type="button" role="option" aria-selected="${tn.id === cur.id}" data-tenant="${tn.id}"><span class="tn-l">${tenantLogo(tn, 32)}<span class="muted small">${m[1]}</span></span>${tn.id === cur.id ? ic('check', 15) : ''}</button>`; };
  return `<div class="tnmenu" role="listbox" aria-label="Cambia tenant" style="left:8px;top:${S.drawer ? 106 : 48}px">
    <div class="eyebrow">Appartenenza</div>${mine.filter(m => m[2] === 'home').map(item).join('')}
    <div class="eyebrow">Ospite in</div>${mine.filter(m => m[2] !== 'home').map(item).join('')}
    <p class="tn-note">${ic('key', 13)} Il selettore è della cornice di Mosaikit: cambiare tenant non chiede di rientrare.</p></div>`;
}
/* The quota indicator at the foot of the sidebar belongs to Mosaikit: here it is a still picture. */
function quotaWidget() {
  const r = 14, c = 2 * Math.PI * r, p = S.role === 'admin' ? 0 : 30, tip = S.role === 'admin' ? 'Tenant nei limiti · indicatore del mockup Mosaikit' : 'Spazio del tenant: 7,4 di 25 GB · indicatore del mockup Mosaikit';
  return `<div class="qw" tabindex="0"><span class="qw-h"><b>${S.role === 'admin' ? 'Tenant nei limiti' : 'Spazio del tenant'}</b></span><span class="small muted">${S.role === 'admin' ? 'Tutte le quote sotto l’80%' : '7,4 di 25 GB'}</span></div><div class="side-ring q-ok" role="img" tabindex="0" aria-label="${tip}" data-stip="${tip}"><svg viewBox="0 0 36 36" aria-hidden="true"><circle cx="18" cy="18" r="${r}" class="rg-b"/><circle cx="18" cy="18" r="${r}" class="rg-v" stroke-dasharray="${(c * p / 100).toFixed(1)} ${c.toFixed(1)}"/></svg><span class="num">${p}%</span><em>${S.role === 'admin' ? 'al limite' : 'spazio'}</em></div>`;
}
function toastHtml() {
  if (!S.toast) return '';
  clearTimeout(toastHtml.t); toastHtml.t = setTimeout(() => { S.toast = null; const el = document.querySelector('.toast'); if (el) el.remove(); }, 4200);
  return `<div class="toast" role="status">${ic('check', 16)}<span>${S.toast}</span></div>`;
}

/* ── Search: the results Geoportal contributes to the search of Mosaikit ── */
function cmdNorm(s) { return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase(); }
function cmdHl(s, q) {
  const esc = String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  if (!q) return esc;
  const i = esc.toLowerCase().indexOf(q.toLowerCase());
  return i < 0 ? esc : esc.slice(0, i) + '<mark>' + esc.slice(i, i + q.length) + '</mark>' + esc.slice(i + q.length);
}
function cmdSources() {
  if (S.role === 'admin') return [['Sezioni di Geoportal', null, [{ t: 'Chiavi dei servizi di mappa', sub: 'Sistema', icon: 'key', f: ['chiavi', 'mappa', 'servizi'], attr: 'data-go="config"' }]]];
  const acc = k => (ACCESS[k] || {}).label || '';
  return [
    ['Mappe', 'maps', vis(S, 'maps', MAPS).map(m => ({ t: m.t, sub: `Mappa · ${m.layers} livelli · ${acc(m.acc)} · ${m.upd}`, icon: 'map', f: [m.t, m.d], attr: `data-res="map:${m.id}" data-go="map-view"` }))],
    ['Cruscotti', 'dashboards', vis(S, 'dash', DASHES).map(m => ({ t: m.t, sub: `Cruscotto · ${m.w} widget · ${acc(m.acc)} · ${m.upd}`, icon: 'dash', f: [m.t, m.d], attr: `data-res="dash:${m.id}" data-go="dash-view"` }))],
    ['Processi', 'processes', vis(S, 'proc', PROCS).map(p => ({ t: p.t, sub: `Processo · ${p.trig} · ${p.last}`, icon: 'flow', f: [p.t, p.trig], attr: `data-res="proc:${p.id}" data-go="process-view"` }))],
    ['Dati', 'catalog', vis(S, 'cat', DATASETS).map(x => ({ t: x.t, sub: `${x.f} · ${x.theme} · ${x.size} · ${x.own}`, icon: 'database', f: [x.t, x.d, x.theme, x.f, x.own], attr: `data-ds="${x.id}" data-go="dataset"` }))
      .concat((S.role === 'collab' ? [] : CONNS).map(c => ({ t: c.t, sub: `Connessione · ${c.proto}`, icon: CONN_KIND[c.k][0], f: [c.t, c.proto, c.host, c.own], attr: `data-res="conn:${c.id}" data-go="conn"` })))]
  ];
}
function cmdResults() {
  const q = (S.cmdQ || '').trim(), nq = cmdNorm(q);
  const item = (r, on) => `<li><button type="button" class="cr ${on ? 'on' : ''}" ${r.attr}>${ic(r.icon, 16)}<span class="cr-t"><b>${cmdHl(r.t, q)}</b><span class="muted small">${r.sub}</span></span>${on ? '<kbd>Invio</kbd>' : ''}</button></li>`;
  let n = 0;
  if (!q) return `<div class="cr-g">Aperti di recente</div><ul class="cmdl">${cmdSources().map(g => g[2][0]).filter(Boolean).map(r => item(r, n++ === 0)).join('')}</ul>`;
  const groups = cmdSources().map(g => [g[0], g[1], g[2].filter(r => r.f.some(f => cmdNorm(f).includes(nq)))]).filter(g => g[2].length);
  if (!groups.length) return `<p class="cr-none">Nessun risultato per «${cmdHl(q)}». Prova con un tema, un formato o il nome di chi l’ha creato.</p>`;
  const tot = groups.reduce((s, g) => s + g[2].length, 0);
  return `<p class="cr-n muted small">${tot} ${tot === 1 ? 'risultato' : 'risultati'}</p>` + groups.map(g => `<div class="cr-g">${g[0]} <span class="num">${g[2].length}</span></div><ul class="cmdl">${g[2].slice(0, 4).map(r => item(r, n++ === 0)).join('')}${g[2].length > 4 && g[1] ? `<li><button type="button" class="cr more" data-go="${g[1]}">${ic('right', 14)}<span class="cr-t">Altri ${g[2].length - 4} in ${g[0]}</span></button></li>` : ''}</ul>`).join('');
}
DIALOGS.cmd = () => ({ top: true, html: `<div class="cmd"><div class="field big">${ic('search', 18)}<input type="text" data-cmdq placeholder="${S.role === 'admin' ? 'Cerca tra le sezioni di Geoportal' : 'Cerca mappe, cruscotti, processi e dati'}" aria-label="Cerca" value="${(S.cmdQ || '').replace(/"/g, '&quot;')}" autocomplete="off"><kbd>Esc</kbd></div><div class="cmdr" role="listbox">${cmdResults()}</div>
    <div class="cmd-f muted small"><span><kbd>↑</kbd><kbd>↓</kbd> per scegliere</span><span><kbd>Invio</kbd> per aprire</span><span>Persone e pagine di Mosaikit: nel mockup Mosaikit</span></div></div>` });

/* ── The frame ────────────────────────────────────────────────────────
   Map, dashboard and process open full screen: the same band, with the logo of the body that does
   not switch, and the commands of the page in place of the search (the page writes its bar; render()
   moves it into the band). */
function shellA(content, full) {
  if (full) {
    const tn = curTenant();
    return `<div class="app pA full-band"><div class="band"><div class="side-h"><div class="tsw static" role="img" aria-label="Tenant: ${tn.name}">${tenantLogo(tn, 34)}</div></div>
        <header class="top"><div class="top-slot"></div>${topRight()}</header></div><div class="full-body">${content}</div></div>`;
  }
  const groups = navFor(S.role), mini = !S.drawer && S.side !== 'open';
  const label = g => g === 'spazio' ? 'Gestione del tenant' : GROUP_LABEL[g];
  return `<div class="app pA ${S.drawer ? 'drawer' : ''}">
    <div class="band"><div class="side-h">${tenantSwitch()}</div>
      <header class="top"><button type="button" class="ib sbt" data-act="drawer" aria-expanded="${!!S.drawer}" aria-label="${S.drawer ? 'Chiudi il menu' : 'Apri il menu'}">${ic(S.drawer ? 'x' : 'menu', 18)}</button><button type="button" class="sbox" data-act="cmd" data-tip="Cerca tra le risorse · Ctrl K">${ic('search', 15)}<span>Cerca tra le risorse</span><kbd>Ctrl K</kbd></button>${topRight()}</header></div>
    <div class="body"><nav class="side ${mini ? 'mini' : ''}" aria-label="Navigazione">
      ${S.drawer ? `<div class="side-hd">${tenantSwitch()}</div>` : ''}
      <div class="side-list">${groups.filter(x => x[0] !== 'fondo').map(([g, items]) => `<div class="sg sg-${g}">${g === 'top' ? '' : `<div class="sg-l">${label(g)}</div>`}${items.map(navItem).join('')}</div>`).join('')}</div>
      ${groups.filter(x => x[0] === 'fondo').map(x => `<div class="sg sg-fondo">${x[1].map(navItem).join('')}</div>`).join('')}
      <div class="side-f">${quotaWidget()}</div></nav>
      <div class="scrim-d" data-act="drawer"></div>
      <main class="main">${content}</main></div></div>`;
}
function modalHtml() {
  if (!S.modal || !DIALOGS[S.modal]) return '';
  const d = DIALOGS[S.modal](S);
  return `<div class="scrim" data-act="close"></div><div class="dlg ${d.top ? 'atop cmdd' : ''} ${d.wide ? 'wide' : ''}" role="dialog" aria-modal="true">${d.html}</div>`;
}

/* ── Review: one box per screen and per dialog, per role ─────────────── */

const MODALS = [['new', 'Crea · le voci di Geoportal', 'dlg-create', ['user', 'manager']], ['upload', 'Carica dati', 'dlg-upload', ['user', 'manager']], ['connect', 'Nuova connessione', 'dlg-connect', ['user', 'manager']], ['rule', 'Regola sul dato', 'dlg-rule', ['user', 'manager']], ['adddata', 'Aggiungi dati (editor di mappa)', 'dlg-add-data', ['user', 'manager']], ['access', 'Chiedi l’accesso a un dato', 'dlg-access', ['user', 'collab', 'manager']], ['versions', 'Versioni di una mappa', 'dlg-versions', ['user', 'collab', 'manager']], ['cmd', 'Ricerca · i risultati di Geoportal', 'dlg-search', ALL]];
let CHECK = {};
try { CHECK = JSON.parse(localStorage.getItem('gp-mockup-review') || '{}') || {}; } catch (e) { CHECK = {}; }
function saveCheck() { try { localStorage.setItem('gp-mockup-review', JSON.stringify(CHECK)); } catch (e) { /* stays in memory */ } }
function checkItems(role) {
  const items = [];
  navFor(role).forEach(([g, list]) => list.filter(r => r[5] !== 'mk').forEach(r => items.push(['go', r[1], r[2], g === 'top' ? 'Panoramica' : GROUP_LABEL[g], r[3], r[1]])));
  Object.entries(HIDDEN).filter(([, v]) => v[2].includes(role)).forEach(([id, v]) => items.push(['go', id, v[0], 'Schermate di dettaglio', 'right', id]));
  MODALS.filter(m => m[3].includes(role)).forEach(m => items.push(['act', m[0], m[1], 'Finestre', 'grid', m[2]]));
  return items;
}
function isChecked(role, key) { return !!(CHECK[role] && CHECK[role][key]); }
function curKey() { return S.modal ? 'act:' + S.modal : 'go:' + S.route; }
function progress(role) { const it = checkItems(role); return [it.filter(i => isChecked(role, i[0] + ':' + i[1])).length, it.length]; }
function screenId() {
  if (S.modal) { const m = MODALS.find(x => x[0] === S.modal); return MOCKUP + ':' + (m ? m[2] : 'dlg-' + S.modal); }
  return MOCKUP + ':' + S.route + (SUB ? '/' + SUB : '');
}
function indexHtml() {
  if (!S.map) return '';
  const groups = [];
  checkItems(S.role).forEach(i => { let g = groups.find(x => x[0] === i[3]); if (!g) { g = [i[3], []]; groups.push(g); } g[1].push(i); });
  const [done, tot] = progress(S.role);
  return `<div class="scrim" data-act="sitemap"></div><div class="dlg wide" role="dialog" aria-label="Indice e verifica"><div class="dlg-h"><h2>Indice e verifica · ${ROLES[S.role].long} · ${done} di ${tot}</h2>${btn('', { icon: 'x', act: 'sitemap' })}</div>
    <div class="dlg-b sitemap">${groups.map(([g, list]) => `<div><div class="eyebrow">${g}</div>${list.map(i => { const key = i[0] + ':' + i[1]; const on = isChecked(S.role, key); return `<div class="smr"><button type="button" class="chkb ${on ? 'on' : ''}" data-chk="${key}" aria-pressed="${on}" aria-label="Verificata: ${i[2]}">${on ? ic('check', 13) : ''}</button><button type="button" data-${i[0]}="${i[1]}" class="smi">${ic(i[4], 15)}${i[2]}<span class="mono">${i[5]}</span></button></div>`; }).join('')}</div>`).join('')}</div>
    <div class="dlg-f"><span class="muted small">Il conto resta in questo browser, separato per ruolo. Le pagine grigie sono nel mockup Mosaikit.</span>${btn('Azzera il ruolo', { act: 'reset' })}</div></div>`;
}
function notesHtml() {
  return `<aside class="notes" ${S.notes ? '' : 'hidden'} aria-label="Note"><div class="notes-h"><b>Geoportal · estensioni di Mosaikit</b>${btn('', { icon: 'x', act: 'notes' })}</div>
    <div class="notes-b"><p class="lead">${DECISIONE.idea}</p><div class="eyebrow">Questa schermata · <span class="mono">${screenId()}</span></div><p>${SCREEN_NOTES[S.route] || ''}</p>
    <div class="eyebrow">Per il ruolo ${ROLES[S.role].long.toLowerCase()}</div><p>${DECISIONE.roles[S.role]}</p>
    <div class="eyebrow">Come si verifica</div><p>${DECISIONE.how}</p></div></aside>`;
}
function reviewBar() {
  const [done, tot] = progress(S.role), key = curKey(), on = isChecked(S.role, key), pub = S.route === 'pubcat';
  return `<div class="rev-in"><span class="rev-t">Geoportal · mockup</span>
    <div class="rseg" role="group"><button type="button" data-go="pubcat" aria-pressed="${pub}" aria-label="Catalogo pubblico" title="Catalogo pubblico, senza account">${ic('globe', 14)}<b>Pubblico</b></button>${[['admin', 'Amministratore', 'Admin'], ['manager', 'Gestore', 'Gestore'], ['user', 'Utente', 'Utente'], ['collab', 'Consultazione', 'Cons.']].map(i => `<button type="button" data-role="${i[0]}" aria-pressed="${!pub && S.role === i[0]}" aria-label="${i[1]}" title="${i[0] === 'collab' ? 'Un Utente con il profilo Consultazione e modifica' : i[1]}"><b class="rs-l">${i[1]}</b><b class="rs-s" aria-hidden="true">${i[2]}</b></button>`).join('')}</div>
    <button type="button" class="rev-id" data-act="copyid" title="Identificativo della schermata, come lo citano i requisiti: clic per copiarlo">${screenId()}</button>
    <span class="grow"></span>
    <button type="button" class="rbtn ${on ? 'on' : ''}" data-chk="${key}" aria-pressed="${on}">${ic('check', 15)}<span>${on ? 'Verificata' : 'Segna verificata'}</span></button>
    <button type="button" class="rbtn" data-act="sitemap">${ic('tree', 15)}<span>Indice</span><b class="num">${done}/${tot}</b></button>
    <button type="button" class="rbtn ${S.notes ? 'on' : ''}" data-act="notes">${ic('note', 15)}<span>Note</span></button></div>`;
}

/* ── Tips of the icon sidebar: one tip outside the column, next to the icon ── */
function sideTip(el) {
  let tip = document.getElementById('side-tip');
  if (!el) { if (tip) tip.hidden = true; return; }
  if (!tip) { tip = document.createElement('div'); tip.id = 'side-tip'; tip.setAttribute('role', 'tooltip'); document.body.appendChild(tip); }
  const r = el.getBoundingClientRect();
  tip.textContent = el.dataset.stip; tip.hidden = false;
  tip.style.left = (r.right + 10) + 'px'; tip.style.top = (r.top + r.height / 2) + 'px';
}
function sideTipTarget(e) { const el = e.target.closest && e.target.closest('[data-stip]'); return el && el.closest('.side.mini') ? el : null; }
document.addEventListener('mouseover', e => sideTip(sideTipTarget(e)));
document.addEventListener('focusin', e => sideTip(sideTipTarget(e)));
document.addEventListener('focusout', () => sideTip(null));
document.addEventListener('scroll', () => sideTip(null), true);

/* ── Rendering ───────────────────────────────────────────────────────── */

function render() {
  setSub(null);
  applyPreset();
  sideTip(null);
  const stage = document.getElementById('stage');
  // The public catalog is a page of its own: no band and no sidebar.
  const html = S.route === 'pubcat' ? secPubCat(S) + toastHtml() + notesHtml() + indexHtml()
    : shellA((SECTIONS[S.route] || secHome)(S), FULLSCREEN.includes(S.route)) + notesHtml() + toastHtml() + themeMenuHtml() + helpMenuHtml() + userMenuHtml() + tenantMenuHtml() + modalHtml() + indexHtml();
  document.getElementById('rev').innerHTML = reviewBar();
  stage.innerHTML = html;
  stage.classList.toggle('with-notes', S.notes);
  try { localStorage.setItem('gp-mockup-state', JSON.stringify({ role: S.role, route: S.route, notes: S.notes, mode: S.mode })); } catch (e) { /* no storage */ }
  if (S.route === 'pubcat') return;
  document.querySelectorAll('[data-ind]').forEach(el => { el.indeterminate = true; });
  if (S.modal === 'cmd') { const q = document.querySelector('[data-cmdq]'); if (q && document.activeElement !== q) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); } }
  // On a phone every row of a table becomes a card: the cells carry the name of their column.
  document.querySelectorAll('#stage .tbl:not(.perm)').forEach(tb => {
    const hs = [...tb.querySelectorAll('thead th')].map(th => th.textContent.trim());
    if (!hs.length) return;
    tb.classList.add('cardable');
    tb.querySelectorAll('tbody tr').forEach(tr => [...tr.children].forEach((td, i) => { if (hs[i] && !td.classList.contains('cbx')) td.dataset.l = hs[i]; }));
  });
  // The logo in the band takes the variant for dark backgrounds when the band is dark.
  document.querySelectorAll('.band').forEach(b => { const h = b.querySelector('.side-h, .top'), m = h && getComputedStyle(h).backgroundColor.match(/\d+/g); if (m) b.classList.toggle('navdark', (0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2]) < 140); });
  // The «Consultazione e modifica» profile creates and shares nothing.
  if (S.role === 'collab') document.querySelectorAll('#stage [data-act="share"], #stage [data-act="new"], #stage [data-act="upload"]').forEach(el => el.remove());
  // Full-screen pages: the commands of the bar of the page move into the band of the application.
  const slot = document.querySelector('.top-slot'), eb = document.querySelector('.full-body .ed-bar');
  if (slot && eb) { while (eb.firstChild) slot.appendChild(eb.firstChild); eb.remove(); }
  // Where there is a fixed header, the rest of the page goes in a body that scrolls by itself.
  const page = document.querySelector('.main > .page'), head0 = page && page.querySelector(':scope > .lhead');
  if (head0) { const body = document.createElement('div'); body.className = 'pbody'; while (head0.nextSibling) body.appendChild(head0.nextSibling); page.appendChild(body); page.classList.add('has-lh'); }
  const fo = document.querySelector('.facets.open'), pb = document.querySelector('.rpage');
  if (fo && pb && window.innerWidth <= 900) { const r = pb.getBoundingClientRect(); [fo, document.querySelector('.fscrim')].forEach(el => { if (el) { el.style.top = r.top + 'px'; el.style.left = r.left + 'px'; el.style.height = r.height + 'px'; } }); }
  const main = document.querySelector('.main');
  if (main && render.scrollTop) main.scrollTop = 0;
  render.scrollTop = false;
}
function go(route) {
  // A page of the Mosaikit mockup does not render here: it says where it is.
  if (!SECTIONS[route]) { mkNote(route); return; }
  Object.assign(S, { filtersOpen: false, sortMenu: null, route, userMenu: false, helpMenu: false, sel: {}, bulkDone: null, modal: null, map: false, drawer: false });
  render.scrollTop = true; render();
}
function mkNote(route) {
  S.userMenu = false; S.helpMenu = false;
  S.toast = `«${MK_PAGES[route] || route}» è una pagina del mockup Mosaikit (<span class="mono">mosaikit:${route}</span>).`;
  render();
}

/* ── Events ──────────────────────────────────────────────────────────── */

document.addEventListener('click', e => {
  if (S.themeMenu && !e.target.closest('.tmenu, [data-act="theme"]')) { S.themeMenu = false; render(); }
  if (S.helpMenu && !e.target.closest('.hmenu, [data-act="helpmenu"]')) { S.helpMenu = false; render(); }
  if (S.userMenu && !e.target.closest('.umenu, [data-act="usermenu"]')) { S.userMenu = false; render(); }
  if (S.tenantMenu && !e.target.closest('.tnmenu, [data-act="tenants"]')) { S.tenantMenu = false; render(); }
  if (S.sortMenu && !e.target.closest('.sortmenu, [data-act="sortmenu"]')) { S.sortMenu = null; render(); }
  const t = e.target.closest('[data-sel],[data-selall],[data-go],[data-act],[data-tab],[data-role],[data-ds],[data-chk],[data-mode],[data-tenant],[data-sort],[data-dsel],[data-res]');
  if (!t) return;
  if (t.dataset.mode) { S.mode = t.dataset.mode; applyMode(); render(); return; }
  if (t.dataset.dsel) { S.catSel = S.catSel === t.dataset.dsel ? null : t.dataset.dsel; render(); return; }
  if (t.dataset.sort) { const [k, v] = t.dataset.sort.split(':'); S.sortBy = Object.assign({}, S.sortBy, { [k]: v }); S.sortMenu = null; render(); return; }
  if (t.dataset.tenant) { S.tenant = t.dataset.tenant; S.tenantMenu = false; S.drawer = false; render(); return; }
  if (t.dataset.sel || t.dataset.selall) {
    e.stopPropagation();
    S.sel = Object.assign({}, S.sel); S.bulkDone = null;
    if (t.dataset.sel) { const [k, id] = t.dataset.sel.split(':'); const cur = S.sel[k] || []; S.sel[k] = cur.includes(id) ? cur.filter(x => x !== id) : cur.concat(id); }
    else { const k = t.dataset.selall, ids = t.dataset.ids.split(','); const cur = S.sel[k] || []; S.sel[k] = ids.every(id => cur.includes(id)) ? [] : ids; }
    render(); return;
  }
  if (t.dataset.chk) { const k = t.dataset.chk; CHECK[S.role] = CHECK[S.role] || {}; CHECK[S.role][k] = !CHECK[S.role][k]; saveCheck(); render(); return; }
  if (t.dataset.role) { S.role = t.dataset.role; S.tenant = null; S.tab = {}; go(LANDING[S.role]); return; }
  if (t.dataset.tab) { const [k, v] = t.dataset.tab.split(':'); S.tab[k] = v; S.bulkDone = null; S.filtersOpen = false; render(); return; }
  if (t.dataset.ds) { S.ds = t.dataset.ds; if (!t.dataset.go) { render(); return; } }
  if (t.dataset.res && t.dataset.res.startsWith('conn:')) S.tab.cn = 'det';
  if (t.dataset.res) { const [k, id] = t.dataset.res.split(':'); S.resId = Object.assign({}, S.resId, { [k]: id }); }
  if (t.dataset.go) { go(t.dataset.go); return; }
  act(t.dataset.act, t);
});
function act(a, t) {
  const v = t.dataset.v;
  if (a === 'close') { S.modal = null; render(); }
  else if (a === 'notes') { S.notes = !S.notes; render(); }
  else if (a === 'sitemap') { S.map = !S.map; render(); }
  else if (a === 'drawer') { S.drawer = !S.drawer; render(); }
  else if (a === 'copyid') { const id = screenId(); try { navigator.clipboard.writeText(id).catch(() => {}); } catch (e) { /* no clipboard */ } S.toast = 'Identificativo: <span class="mono">' + id + '</span>'; render(); }
  else if (a === 'mk') mkNote(v);
  else if (a === 'share') { S.toast = 'Condividi è la finestra del mockup Mosaikit (<span class="mono">mosaikit:dlg-share</span>); Geoportal le aggiunge i livelli del dato e l’avviso sui dati usati da una mappa.'; render(); }
  else if (a === 'bulk') {
    const key = t.dataset.key, n = (S.sel[key] || []).length;
    S.bulkDone = `<b>${v}</b> su ${n} element${n === 1 ? 'o' : 'i'}. Nel mockup non cambia nulla; nel prodotto ${/Elimina|Ferma/.test(v) ? 'chiede prima conferma, dicendo che cosa si perde' : 'parte subito e lo registra nel registro attività'}.`;
    S.sel = Object.assign({}, S.sel, { [key]: [] }); render();
  }
  else if (a === 'sortmenu') { S.sortMenu = S.sortMenu === t.dataset.key ? null : t.dataset.key; render(); }
  else if (a === 'filters') { if (window.innerWidth <= 900) S.filtersOpen = !S.filtersOpen; else S.filtersHidden = !S.filtersHidden; render(); }
  else if (a === 'nofilters') { document.querySelectorAll('.facets input').forEach(i => { i.checked = i.type === 'radio' ? i.closest('fieldset').querySelector('input') === i : false; }); }
  else if (a === 'closeds') { S.catSel = null; render(); }
  else if (a === 'engine') { S.engine3d = !S.engine3d; render(); }
  else if (a === 'reset') { delete CHECK[S.role]; saveCheck(); render(); }
  else if (a === 'metadone') { S.metaDone = Object.assign({}, S.metaDone, { [v]: 1 }); render(); }
  else if (a === 'metacsw') { S.metaOff = Object.assign({}, S.metaOff, { [v]: !(S.metaOff && S.metaOff[v]) }); render(); }
  else if (a === 'uperr') { S.upErr = !S.upErr; render(); }
  else if (a === 'uplv') { const cur = S.upLv || ['quartieri', 'municipi', 'zone_urbanistiche']; S.upLv = cur.includes(v) ? cur.filter(x => x !== v) : cur.concat(v); render(); }
  else if (a === 'okmodal') { S.modal = null; S.rule = null; S.toast = v; render(); }
  else if (a === 'rulenew') { S.ruleCtx = v; S.rule = null; S.modal = 'rule'; render(); }
  else if (a === 'rulearea') { S.rule.area = v; render(); }
  else if (a === 'rulecol') { S.rule.hide = S.rule.hide.includes(v) ? S.rule.hide.filter(x => x !== v) : S.rule.hide.concat(v); render(); }
  else if (a === 'rulesave') { S.modal = null; S.rule = null; S.toast = 'Regola salvata. Geoportal l’ha scritta in GeoFence: vale subito, anche da QGIS.'; render(); }
  else if (a === 'access') { S.accessWhat = v; S.modal = 'access'; S.map = false; render(); }
  else if (a === 'dsmap') { const ds = DATASETS.find(x => x.id === v) || { t: 'quartieri_2026' }; const msg = S.role === 'collab' ? `«${ds.t}» aperto in una vista di sola lettura.` : `Mappa nuova con «${ds.t}», già con simbolo e legenda.`; go(S.role === 'collab' ? 'map-view' : 'map-edit'); S.toast = msg; render(); }
  else if (a === 'fav') { const f = S.fav || []; S.fav = f.includes(v) ? f.filter(x => x !== v) : f.concat(v); S.toast = f.includes(v) ? 'Tolto dai preferiti.' : 'Aggiunto ai preferiti: lo trovi con il filtro «Preferite».'; render(); }
  else if (a === 'dashper') { S.dashPer = v; render(); }
  else if (a === 'toast') { S.toast = v; render(); }
  else if (a === 'usermenu') { S.userMenu = !S.userMenu; S.helpMenu = false; S.themeMenu = false; render(); }
  else if (a === 'helpmenu') { S.helpMenu = !S.helpMenu; S.userMenu = false; S.themeMenu = false; render(); }
  else if (a === 'theme') { S.themeMenu = !S.themeMenu; S.helpMenu = false; S.userMenu = false; S.tenantMenu = false; render(); }
  else if (a === 'tenants') { S.tenantMenu = !S.tenantMenu; S.userMenu = false; S.themeMenu = false; render(); }
  else if (a === 'hc') { S.hc = !S.hc; render(); }
  else if (a === 'keyedit') { S.keyEdit = S.keyEdit === v ? null : v; render(); }
  else if (a === 'keysave') { const [lv, id] = v.split(':'); S.keys[lv][id] = { tail: Math.random().toString(16).slice(2, 6), share: false }; S.keyEdit = null; render(); }
  else if (a === 'keydel') { const [lv, id] = v.split(':'); delete S.keys[lv][id]; render(); }
  else if (a === 'keyshare') { const [lv, id] = v.split(':'); S.keys[lv][id].share = !S.keys[lv][id].share; render(); }
  else if (a === 'pubf') { S.pubF = v; S.pubSel = null; render(); }
  else if (a === 'pubsel') { S.pubSel = v; render(); }
  else if (a === 'pubhome') { S.pubSel = null; render(); }
  else if (a === 'pubopen') { S.pubSel = v; go('pubcat'); }
  else if (a === 'pubask') { S.pubReq = Object.assign({}, S.pubReq, { [v]: 1 }); S.toast = 'Pubblicazione chiesta: i gestori ricevono una notifica.'; render(); }
  else if (a === 'puback') { S.pubOk = Object.assign({}, S.pubOk, { [v]: 1 }); S.toast = 'Approvata: il dato è nel catalogo pubblico e il proprietario riceve una notifica.'; render(); }
  else if (a === 'cmd') { S.cmdQ = ''; S.modal = 'cmd'; S.map = false; S.userMenu = false; render(); }
  else if (['new', 'upload', 'connect', 'adddata', 'versions'].includes(a)) { if (a === 'adddata' && !FULLSCREEN.includes(S.route)) { S.route = 'map-edit'; } S.modal = a; S.map = false; render(); }
  else if (a === 'rule') { S.ruleCtx = 'd4'; S.rule = null; S.modal = 'rule'; S.map = false; render(); }
}

window.addEventListener('resize', () => { if ((S.drawer || S.filtersOpen) && window.innerWidth > 900) { S.drawer = false; S.filtersOpen = false; render(); } });
document.addEventListener('keydown', e => {
  if (S.modal === 'cmd' && ['ArrowDown', 'ArrowUp', 'Enter'].includes(e.key) && e.target.matches && e.target.matches('[data-cmdq]')) {
    const list = [...document.querySelectorAll('.cmdr .cr')], cur = list.findIndex(b => b.classList.contains('on'));
    if (e.key === 'Enter') { if (list[Math.max(cur, 0)]) { e.preventDefault(); list[Math.max(cur, 0)].click(); } return; }
    e.preventDefault();
    const nx = Math.min(list.length - 1, Math.max(0, cur + (e.key === 'ArrowDown' ? 1 : -1)));
    list.forEach((b, k) => { b.classList.toggle('on', k === nx); const kb = b.querySelector('kbd'); if (kb) kb.remove(); });
    if (list[nx]) { list[nx].insertAdjacentHTML('beforeend', '<kbd>Invio</kbd>'); list[nx].scrollIntoView({ block: 'nearest' }); }
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k' && S.route !== 'pubcat') { e.preventDefault(); S.cmdQ = ''; S.modal = 'cmd'; S.userMenu = false; render(); }
  else if (e.key === 'Escape' && (S.modal || S.map || S.themeMenu || S.helpMenu || S.userMenu || S.tenantMenu || S.sortMenu)) { Object.assign(S, { modal: null, map: false, themeMenu: false, helpMenu: false, userMenu: false, tenantMenu: false, sortMenu: null }); render(); }
});
document.addEventListener('input', e => {
  const el = e.target;
  if (el.matches && el.matches('[data-cmdq]')) { S.cmdQ = el.value; const box = document.querySelector('.cmdr'); if (box) box.innerHTML = cmdResults(); }
});
document.addEventListener('change', e => {
  const el = e.target;
  if (!el.matches) return;
  if (el.matches('[data-melang]')) { S.lang = el.value; S.toast = 'Nel prodotto l’interfaccia cambia lingua subito; il mockup resta in italiano.'; render(); }
  else if (el.matches('[data-pubq]')) { S.pubQ = el.value; S.pubSel = null; render(); }
  else if (el.matches('[data-tabsel]')) { S.tab[el.dataset.tabsel] = el.value; render(); }
  else if (el.matches('[data-rule]')) { S.rule[el.dataset.rule] = el.value; render(); }
});

(function boot() {
  let first = true;
  try {
    const saved = JSON.parse(localStorage.getItem('gp-mockup-state') || 'null');
    if (saved && ROLES[saved.role]) { Object.assign(S, saved); first = false; }
  } catch (e) { /* start from the beginning */ }
  if (!SECTIONS[S.route] || (S.route !== 'pubcat' && HIDDEN[S.route] && !HIDDEN[S.route][2].includes(S.role))) S.route = LANDING[S.role];
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { for (const k in _tw) delete _tw[k]; render(); });
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (S.mode === 'auto') render(); });
  applyMode();
  if (window.innerWidth >= 1500 && first) S.notes = true;
  document.documentElement.lang = 'it';
  render();
})();
