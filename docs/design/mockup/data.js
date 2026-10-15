// SPDX-FileCopyrightText: 2026 Massimo Antonini

/* Geoportal mockup: sample data. The body is the fictitious «Comune di Esempio»; places, people
   and services are invented. */

/* ── People and bodies of the frame ──────────────────────────────────── */

const ROLES = {
  admin: { label: 'Amministratore', long: 'Amministratore di piattaforma', who: 'Paola Ferri', ini: 'PF', mail: 'p.ferri@piattaforma.esempio.example', unit: 'Gestione della piattaforma' },
  manager: { label: 'Gestore', long: 'Gestore', who: 'Elena Ricci', ini: 'ER', mail: 'e.ricci@comune.esempio.example', unit: 'Ufficio SIT' },
  collab: { label: 'Utente', long: 'Utente · profilo Consultazione e modifica', who: 'Paolo Fontana', ini: 'PO', mail: 'p.fontana@comune.esempio.example', unit: 'Ambiente · Rilevatore sul campo' },
  user: { label: 'Utente', long: 'Utente del tenant', who: 'Davide Conti', ini: 'DC', mail: 'd.conti@comune.esempio.example', unit: 'Dipartimento Mobilità · Analista' }
};
const GROUPS = [
  { n: 'Mobilità', d: 'Dipartimento Mobilità e Trasporti', m: 23 },
  { n: 'Urbanistica', d: 'Pianificazione e catasto', m: 17 },
  { n: 'Protezione civile', d: 'Sala operativa e piani di emergenza', m: 12 },
  { n: 'Ambiente', d: 'Qualità dell\'aria, verde, rifiuti', m: 14 },
  { n: 'Polizia locale', d: 'Varchi, segnalazioni, incidenti', m: 31 },
  { n: 'Ufficio SIT', d: 'Amministratori dei dati territoriali', m: 6 }
];
/* The bodies of whoever is signed in, as the switcher of the frame draws them. */
const TENANT_SEL = [
  { id: 'esempio', name: 'Comune di Esempio', pre: 'COMUNE DI', city: 'Esempio', ini: 'E', fill: '#1f4e79', mark: '#f2b632', ink: '#f2b632', pat: 'band' },
  { id: 'cm-esempio', name: 'Città Metropolitana di Esempio', pre: 'CITTÀ METROPOLITANA DI', city: 'Esempio', ini: 'C', fill: '#ffffff', mark: '#c8102e', ink: '#c8102e', pat: 'cross' },
  { id: 'regione-esempio', name: 'Regione Esempio', pre: 'REGIONE', city: 'Esempio', ini: 'R', fill: '#0b6e4f', mark: '#ffffff', ink: '#ffffff', pat: 'chevron' }
];
const SISTEMA = { id: 'sistema', name: 'Geoportal (Sistema)' };
const MY_TENANTS = {
  user: [['esempio', 'Utente', 'home'], ['cm-esempio', 'Utente', 'assoc'], ['regione-esempio', 'Utente', 'assoc']],
  collab: [['esempio', 'Utente', 'home'], ['regione-esempio', 'Utente', 'assoc']],
  manager: [['esempio', 'Gestore', 'home'], ['cm-esempio', 'Utente', 'assoc'], ['regione-esempio', 'Utente', 'assoc']]
};
const LANGS = [['it', 'Italiano'], ['en', 'English'], ['zh', '中文']];

/* ── The catalog: every kind of data has a home and a way of enforcing permissions ── */

const KINDS = {
  file: { label: 'File', icon: 'file', tone: 'c1' },
  ogc: { label: 'Servizio OGC', icon: 'globe', tone: 'c2' },
  stream: { label: 'Tempo reale', icon: 'radio', tone: 'c4' },
  db: { label: 'Database', icon: 'database', tone: 'c3' },
  proc: { label: 'Elaborazione', icon: 'cpu', tone: 'c5' },
  cat: { label: 'Catalogo', icon: 'folder', tone: 'c2' }
};
const HOSTS = {
  geoserver: { label: 'GeoServer', tip: 'In PostGIS o come raster, servito da GeoServer; GeoFence applica permessi e regole' },
  geoportal: { label: 'Geoportal', tip: 'Registrato in Geoportal; i permessi li applica il gateway di Geoportal' },
  remoto: { label: 'Remoto', tip: 'Servizio esterno; Geoportal fa da proxy e applica i permessi' },
  oggetti: { label: 'Archivio oggetti', tip: 'Formato cloud su archivio S3; Geoportal firma l\'URL dopo il controllo' }
};
const DATASETS = [
  { id: 'd1', t: 'Stazioni di monitoraggio idrometrico', k: 'file', f: 'GeoPackage', theme: 'Ambiente', host: 'geoserver', acc: 'spazio', upd: 'Oggi 10:45', size: '14 punti', rules: 0, own: 'Luca Bianchi', d: 'Stazioni idro-pluviometriche sul bacino del fiume Lete con livello, portata e soglie di allerta.' },
  { id: 'd2', t: 'Flussi di traffico, rete principale', k: 'stream', f: 'Kafka', theme: 'Mobilità', host: 'geoportal', acc: 'gruppi', upd: 'In diretta', size: '≈ 420 msg/min', rules: 1, own: 'Sara Greco', d: 'Conteggi e velocità medie dalle spire e dalle telecamere delle arterie principali, aggiornati ogni 60 secondi.' },
  { id: 'd3', t: 'Zone a traffico limitato e varchi', k: 'file', f: 'Shapefile', theme: 'Mobilità', host: 'geoserver', acc: 'pubblico', upd: '03/10/2026', size: '9 zone · 58 varchi', rules: 0, own: 'Davide Conti', d: 'Perimetri delle ZTL, fasce orarie e varchi elettronici con stato di attivazione.' },
  { id: 'd4', t: 'Particelle catastali, Municipio 1', k: 'db', f: 'PostGIS', theme: 'Urbanistica', host: 'geoserver', acc: 'gruppi', upd: '01/10/2026', size: '61.204 particelle', rules: 2, own: 'Marco Esposito', d: 'Tabella pubblicata da PostGIS su GeoServer; il codice fiscale del titolare è visibile solo a Urbanistica.' },
  { id: 'd5', t: 'Ortofoto 2025, 20 cm', k: 'file', f: 'COG', theme: 'Territorio', host: 'geoserver', acc: 'spazio', upd: '26/09/2026', size: '38,2 GB', rules: 1, own: 'Elena Ricci', d: 'Mosaico RGB a 20 cm convertito in Cloud Optimized GeoTIFF all\'ingresso.' },
  { id: 'd6', t: 'Qualità dell\'aria, centraline', k: 'stream', f: 'MQTT', theme: 'Ambiente', host: 'geoportal', acc: 'pubblico', upd: 'In diretta', size: '13 centraline', rules: 0, own: 'Sara Greco', d: 'PM10, PM2.5, NO2 e O3 ogni 10 minuti dal broker regionale.' },
  { id: 'd7', t: 'Fermate e linee del trasporto pubblico', k: 'file', f: 'GTFS', theme: 'Mobilità', host: 'geoserver', acc: 'pubblico', upd: '22/09/2026', size: '8.311 fermate', rules: 0, own: 'Sara Greco', d: 'Feed GTFS statico convertito in due dati: fermate (punti) e tracciati (linee).' },
  { id: 'd8', t: 'Carta tecnica regionale 1:5000', k: 'ogc', f: 'WMS', theme: 'Territorio', host: 'remoto', acc: 'pubblico', upd: 'Servizio esterno', size: '4 livelli', rules: 0, own: 'Ufficio SIT', d: 'Servizio WMS della Regione, registrato come servizio remoto e attraversato dal proxy.' },
  { id: 'd9', t: 'Isocrone di servizio', k: 'proc', f: 'OGC API Processes', theme: 'Mobilità', host: 'geoportal', acc: 'gruppi', upd: 'Disponibile', size: '3 processi', rules: 0, own: 'Ufficio SIT', d: 'Calcolo di isocrone a piedi, in bici e in TPL. Chi ha «Esegui» può usarlo in mappa e nei processi.' },
  { id: 'd10', t: 'Catalogo della Regione Esempio', k: 'cat', f: 'CSW', theme: 'Ambiente', host: 'remoto', acc: 'spazio', upd: 'Raccolto stanotte', size: '312 record', rules: 0, own: 'Ufficio SIT', d: 'Catalogo esterno interrogato in CSW; i record diventano dati remoti quando li si aggiunge.' },
  { id: 'd11', t: 'Edifici 3D, centro storico', k: 'file', f: '3D Tiles', theme: 'Territorio', host: 'oggetti', acc: 'spazio', upd: '14/09/2026', size: '1,9 GB', rules: 0, own: 'Elena Ricci', d: 'Modello LOD2 in 3D Tiles nell’archivio oggetti: GeoServer non serve il 3D, il link lo firma Geoportal.' },
  { id: 'd12', t: 'Segnalazioni dei cittadini', k: 'file', f: 'CSV', theme: 'Servizi', host: 'oggetti', acc: 'privato', upd: 'Ieri 18:20', size: '2.107 righe', rules: 0, own: 'Davide Conti', d: 'Esportazione settimanale del contact center, geocodificata. Resta privata finché non è anonimizzata.' },
  { id: 'd13', t: 'Livello del fiume Lete, sensori', k: 'stream', f: 'WebSocket', theme: 'Protezione civile', host: 'geoportal', acc: 'gruppi', upd: 'In diretta', size: '6 sensori', rules: 0, own: 'Luca Bianchi', d: 'Letture ogni 30 secondi dagli idrometri sui ponti, con soglie di attenzione e allarme.' },
  { id: 'd14', t: 'Grafo stradale', k: 'db', f: 'Trino', theme: 'Mobilità', host: 'geoportal', acc: 'gruppi', upd: '30/09/2026', size: '412.880 archi', rules: 1, own: 'Sara Greco', d: 'Tabella del lakehouse interrogata in sola lettura; il gateway applica un filtro sull\'area del tenant.' }
];
const MAPS = [
  { id: 'm1', own: 'Elena Ricci', t: 'Rete di monitoraggio, fiume Lete', d: '14 stazioni idro-pluviometriche, soglie e aree di allerta sul bacino.', acc: 'spazio', layers: 6, upd: '12 min fa', people: ['ER', 'DC', 'SG'], live: true, seed: 1 },
  { id: 'm2', own: 'Sara Greco', t: 'Piano urbano della mobilità', d: 'Corsie preferenziali, ciclabili e nodi di interscambio del PUMS.', acc: 'pubblico', layers: 14, upd: '3 ore fa', people: ['SG'], live: false, seed: 2 },
  { id: 'm3', own: 'Davide Conti', t: 'ZTL e varchi', d: 'Zone, fasce orarie, varchi attivi e transiti dell\'ultima ora.', acc: 'gruppi', layers: 5, upd: 'Ieri', people: ['DC'], live: false, seed: 3 },
  { id: 'm4', own: 'Davide Conti', t: 'Isocrone TPL, 30 minuti', d: 'Raggiungibilità dei poli sanitari con il trasporto pubblico.', acc: 'privato', layers: 4, upd: '2 giorni fa', people: ['DC'], live: false, seed: 4 },
  { id: 'm5', own: 'Luca Bianchi', t: 'Rischio idraulico, scenario 200 anni', d: 'Aree allagabili, edifici esposti e percorsi di evacuazione.', acc: 'gruppi', layers: 9, upd: '5 giorni fa', people: ['LB', 'ER'], live: false, seed: 5 },
  { id: 'm6', own: 'Elena Ricci', t: 'Centro storico in 3D', d: 'Edifici LOD2, ortofoto 2025 e cantieri attivi.', acc: 'spazio', layers: 7, upd: '9 giorni fa', people: ['ER'], live: false, seed: 6 }
];
const DASHES = [
  { id: 'b1', own: 'Davide Conti', t: 'Sala operativa traffico', d: 'Flussi in diretta, incidenti, varchi e tempi di percorrenza.', acc: 'gruppi', w: 9, upd: '5 min fa', seed: 2 },
  { id: 'b2', own: 'Davide Conti', t: 'Monitoraggio ZTL', d: 'Transiti per varco, violazioni e permessi in scadenza.', acc: 'spazio', w: 7, upd: 'Ieri', seed: 3 },
  { id: 'b3', own: 'Sara Greco', t: 'Qualità dell\'aria', d: 'Centraline, superamenti e previsione a 48 ore.', acc: 'pubblico', w: 6, upd: '2 ore fa', seed: 1 },
  { id: 'b4', own: 'Luca Bianchi', t: 'Livelli del fiume Lete', d: 'Sei idrometri, soglie e confronto con la piena di riferimento.', acc: 'gruppi', w: 5, upd: '30 s fa', seed: 5 }
];
const PROCS = [
  { id: 'p1', t: 'Isocrone TPL, 30 minuti', trig: 'Ogni notte alle 02:00', last: 'Riuscito · 02:04', st: 'ok', nodes: 7, upd: 'Ieri' },
  { id: 'p2', t: 'Allerta piena fiume Lete', trig: 'A ogni lettura sopra soglia', last: 'Riuscito · 08:41', st: 'ok', nodes: 9, upd: '3 giorni fa' },
  { id: 'p3', t: 'Catasto in GeoPackage', trig: 'A mano', last: 'Fallito · ieri 16:12', st: 'bad', nodes: 5, upd: 'Ieri' },
  { id: 'p4', t: 'NDVI dei parchi', trig: 'Ogni lunedì', last: 'In corso · 61%', st: 'run', nodes: 6, upd: 'Oggi' },
  { id: 'p5', t: 'Report settimanale ZTL', trig: 'Ogni venerdì alle 18:00', last: 'Riuscito · ven 18:02', st: 'ok', nodes: 8, upd: '1 settimana fa' }
];
const ACCESS = {
  privato: { label: 'Privato', icon: 'lock' },
  gruppi: { label: 'Gruppi', icon: 'users' },
  spazio: { label: 'Tutto il tenant', icon: 'building' },
  pubblico: { label: 'Pubblico', icon: 'globe' }
};
/* Connections are resources of the catalog like data: many data are born from one (a table, a
   topic, a process). One shares a connection to USE it (make data, without seeing address and
   credentials) or to MANAGE it. */
const CONNS = [
  { id: 'c1', t: 'PostGIS · catasto_prod', k: 'db', proto: 'PostgreSQL 16 + PostGIS 3.4', host: 'db.interno:5432', db: 'catasto_prod', user: 'gp_catasto', own: 'Elena Ricci', st: 'ok', derived: 12, acc: 'gruppi', upd: 'Verificata 2 min fa' },
  { id: 'c2', t: 'Broker MQTT regionale', k: 'stream', proto: 'MQTT 5 · TLS', host: 'mqtt.regione-esempio.example:8883', db: 'aria/#', user: 'esempio-ingest', own: 'Sara Greco', st: 'bad', derived: 3, acc: 'spazio', upd: 'Non risponde da 14 min' },
  { id: 'c3', t: 'Kafka · traffico', k: 'stream', proto: 'Kafka 3.7 · SASL', host: 'kafka.mobilita.esempio.example:9093', db: 'traffico.*', user: 'geoportal', own: 'Davide Conti', st: 'ok', derived: 4, acc: 'gruppi', upd: 'Verificata 1 min fa' },
  { id: 'c4', t: 'Trino · lakehouse', k: 'db', proto: 'Trino 450 · Iceberg', host: 'trino.interno:8443', db: 'lake.urbanistica', user: 'gp_reader', own: 'Elena Ricci', st: 'ok', derived: 6, acc: 'spazio', upd: 'Verificata 5 min fa' },
  { id: 'c5', t: 'pygeoapi · OGC API Processes', k: 'proc', proto: 'OGC API Processes 1.0', host: 'https://processi.esempio.example', db: '—', user: 'token del tenant', own: 'Davide Conti', st: 'ok', derived: 5, acc: 'spazio', upd: 'Verificata 10 min fa' },
  { id: 'c6', t: 'CSW · Regione Esempio', k: 'cat', proto: 'CSW 2.0.2', host: 'https://geoportale.regione-esempio.example/csw', db: '—', user: 'anonimo', own: 'Sara Greco', st: 'ok', derived: 31, acc: 'spazio', upd: 'Verificata 1 ora fa' }
];
const CONN_KIND = { db: ['database', 'Database'], stream: ['radio', 'Tempo reale'], proc: ['cpu', 'Elaborazione'], cat: ['folder', 'Catalogo'] };
/* Data rules: one source, read by the page of the data. */
const DATA_RULES = [
  { ds: 'd4', who: 'Mobilità', acc: 'Vedere', area: 'Tutto il comune', rows: '—', cols: 'nasconde cf_titolare' },
  { ds: 'd4', who: 'link', acc: 'Vedere', area: 'Municipio 1', rows: '—', cols: 'solo foglio, particella' },
  { ds: 'd12', who: 'Polizia locale', acc: 'Vedere', area: 'Tutto il comune', rows: 'tipo in (traffico, sosta)', cols: 'nasconde telefono' },
  { ds: 'd12', who: 'Mobilità', acc: 'Vedere', area: 'Tutto il comune', rows: "tipo = 'traffico'", cols: 'nasconde telefono' }
];
function ruleWho(r) { return r.who === 'link' ? 'Chiunque con il link' : 'Gruppo ' + r.who; }
/* Outside map services with a key: [id, name, group, icon, what it is for]. */
const KEY_GROUPS = [['map', 'Mappe, terreno e 3D'], ['data', 'Dati e immagini']];
const KEY_SVC = [
  ['terrain3d', 'Terreno ed edifici 3D', 'map', 'layers', 'Terreno mondiale ed edifici in 3D'],
  ['photo3d', 'Città 3D fotorealistiche', 'map', 'layers', 'Città in 3D fotorealistico'],
  ['geocode', 'Geocodifica', 'map', 'pin', 'Ricerca di indirizzi e luoghi'],
  ['sat', 'Immagini satellitari', 'data', 'globe', 'Immagini radar e ottiche'],
  ['meteo', 'Dati meteo', 'data', 'activity', 'Previsioni e dati meteo']
];

/* ── Composition helpers of geodata ──────────────────────────────────── */

function accPill(a) { const x = ACCESS[a]; return pill(x.label, 'acc-' + a, x.icon); }
function hostPill(h) { return `<span class="host host-${h}" title="${HOSTS[h].tip}">${HOSTS[h].label}</span>`; }
function kindIcon(k) { const x = KINDS[k]; return `<span class="kind ${x.tone}" title="${x.label}">${ic(x.icon, 15)}</span>`; }
