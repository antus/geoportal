// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * The services the catalog publishes on GeoServer (GP-114, GP-115, GP-118): the publishing dialog of a
 * loaded dataset (destination, layer name, the style of Geoportal next to its translation into the SLD
 * of GeoServer), the list of published services with their addresses on the origin of Geoportal and
 * their health check, republish, repoint and unpublish, and the reconciliation of the GeoServer
 * workspace for those who manage it. A failure says what went wrong in Italian, what GeoServer
 * answered, what to do and the reference of the log. The texts follow the language of the person.
 */

import { dataRulesSection } from './data-rules.js';
import { assignedBadge, editingSection, myLayers } from './editing.js';
import {
  constantOf,
  defaultStyle,
  fromPanel,
  kindOf,
  LABEL_PANEL,
  migrate,
  PANEL,
  representable,
  retype,
  symbolFor,
  toPanel,
  TYPES,
  validate,
} from './style-grammar.js';

const API = '/api/v1/ext/catalog-geodata/publications';
const GEOSERVER = '/api/v1/ext/catalog-geodata/geoserver';
export const CHANGED = 'catalog-publications-changed';

const TEXTS = {
  it: {
    title: 'Servizi pubblicati',
    subtitle: 'I dati caricati che GeoServer serve come livelli del workspace dell’organizzazione, con il loro stato.',
    columns: ['Livello', 'Dato', 'Stato del servizio'],
    none: 'Nessun dato pubblicato.',
    count: (n) => (n === 1 ? '1 servizio pubblicato' : `${n} servizi pubblicati`),
    error: (s) => `I servizi pubblicati non si possono leggere (${s}).`,
    health: { ok: 'Risponde', failed: 'Non risponde', unknown: 'Da controllare', checking: 'Controllo…' },
    healthCodes: {
      'layer-missing': 'GeoServer non conosce più questo livello.',
      'data-error': 'GeoServer non riesce a leggere i dati del livello.',
      'geoserver-unreachable': 'GeoServer non risponde.',
      'credential-missing': 'Manca la credenziale «geoserver», né dell’organizzazione né dell’installazione.',
    },
    checkedAt: (d) => `Controllato ${d}`,
    services: 'Indirizzi del servizio',
    servicesHint: 'Attraverso Geoportal, con il tuo accesso: l’indirizzo interno di GeoServer non è mai esposto.',
    desktopHint:
      'Per QGIS e gli altri programmi aggiungi all’indirizzo &authkey= seguito da una tua chiave di accesso personale (Impostazioni, Chiavi di accesso): vedi quello che vedi qui.',
    dataset: 'Dato',
    style: 'Stile',
    publishedAt: 'Pubblicato il',
    check: 'Controlla ora',
    republish: 'Ripubblica',
    repoint: 'Ripunta',
    unpublish: 'Ritira',
    close: 'Chiudi',
    copy: 'Copia',
    copied: 'Indirizzo copiato.',
    // The publishing dialog.
    publish: 'Pubblica',
    publishTitle: (t) => `Pubblica «${t}»`,
    republishTitle: (n) => `Ripubblica il livello ${n}`,
    destination: 'Destinazione',
    destinationValue: 'Workspace GeoServer dell’organizzazione',
    layerName: 'Nome del livello',
    layerHint: 'Lettere minuscole, cifre e trattini bassi; resta lo stesso quando ripubblichi.',
    geoportalStyle: 'Stile Geoportal',
    geoserverStyle: 'Stile GeoServer (SLD tradotto)',
    translating: 'Traduzione…',
    fill: 'Colore di riempimento',
    fillOpacity: 'Opacità del riempimento',
    stroke: 'Colore del bordo',
    strokeWidth: 'Spessore del bordo (px)',
    pointSize: 'Dimensione del simbolo (px)',
    pointShape: 'Forma del simbolo',
    symbolType: 'Tipo di simbolo',
    types: { point: 'Punto', line: 'Linea', polygon: 'Poligono', mixed: 'Tutte le geometrie' },
    strokeOpacity: 'Opacità della linea',
    lineColor: 'Colore della linea',
    lineWidth: 'Spessore della linea (px)',
    labelField: 'Etichetta (attributo)',
    noLabel: 'nessuna',
    labelColor: 'Colore dell’etichetta',
    labelSize: 'Dimensione dell’etichetta (px)',
    notRepresentable: 'Lo stile ha valori che il pannello non mostra (guidati dai dati, o diversi per geometria): modificali nel documento JSON; una modifica nel pannello li sostituisce.',
    document: 'Documento di stile (JSON)',
    exportStyle: 'Esporta JSON',
    importStyle: 'Importa JSON…',
    documentHint: 'Il formato geoportal/style/v1: si copia tra livelli, mappe e installazioni.',
    problems: {
      json: 'Il testo non è JSON valido.',
      object: 'deve essere un oggetto',
      grammar: 'la grammatica è geoportal/style/v1',
      enum: (p) => `uno tra ${p.allowed.join(', ')}`,
      unknown: 'non previsto qui',
      required: 'obbligatorio',
      color: 'un colore #rrggbb',
      number: (p) => `un numero da ${p.min} a ${p.max}`,
      field: 'il nome di un attributo: lettere, cifre e _, senza cifra iniziale',
      driver: 'uno solo tra categories, graduated e scaled; categories va con otherwise',
      count: (p) => (p.count ? `ne servono ${p.count}` : 'numero di elementi non valido'),
      breaks: 'da 1 a 50 numeri crescenti',
      from: 'due numeri, il primo minore',
      duplicate: 'un’altra categoria ha lo stesso valore',
      scalar: 'un testo, un numero, true o false',
      string: (p) => `un testo fino a ${p.max} caratteri`,
      boolean: 'true o false',
      text: 'un testo, {"field": …} o {"template": …}',
      template: 'un testo in cui {nome} indica un attributo',
      dash: 'da 2 a 8 numeri, tratto e spazio alternati',
    },
    shapes: { circle: 'Cerchio', square: 'Quadrato', triangle: 'Triangolo', star: 'Stella', cross: 'Croce', x: 'X' },
    cancel: 'Annulla',
    retry: 'Riprova',
    published: (n) => `Il livello ${n} è pubblicato.`,
    republished: (n) => `Il livello ${n} è stato ripubblicato.`,
    // Repoint and unpublish.
    repointTitle: (n) => `Ripunta il livello ${n}`,
    repointHint: 'Il livello mantiene nome e indirizzi e serve i dati scelti: le mappe che lo usano continuano a funzionare.',
    target: 'Dati da servire',
    noTarget: 'Nessun altro dato caricato che puoi gestire.',
    repointed: (n, t) => `Il livello ${n} ora serve «${t}».`,
    unpublishTitle: (n) => `Ritirare il livello ${n}?`,
    unpublishWhat: 'Da GeoServer vengono tolti il livello, il suo stile e le tessere in cache. I dati restano caricati e si possono pubblicare di nuovo.',
    unpublishMaps: 'Le mappe di Geoportal non sono ancora collegate al catalogo: chi usa questo indirizzo in un’altra applicazione non lo troverà più.',
    unpublished: (n) => `Il livello ${n} è stato ritirato.`,
    // Failures (GP-115).
    failure: {
      'geoserver-unreachable': 'GeoServer non risponde. Riprova tra poco; se continua, avvisa chi amministra GeoServer.',
      'geoserver-refused': 'GeoServer ha rifiutato l’operazione.',
      'geodata-unreachable': 'Il database dei dati geografici non risponde. Riprova tra poco.',
      'credential-missing': 'Manca la credenziale «geoserver» o «geodata»: chiedi a un amministratore della piattaforma di salvarla nelle credenziali dell’installazione, o a un amministratore dell’organizzazione in Amministrazione, Credenziali.',
      'name-in-use': 'Il catalogo usa già questo nome per un altro livello: scegline un altro.',
      'name-taken': 'GeoServer ha già un livello o uno stile con questo nome che il catalogo non conosce: scegli un altro nome, oppure adottalo o rimuovilo nella riconciliazione.',
      'not-loaded': 'Il dato non è caricato nel database dei dati geografici: caricalo di nuovo prima di pubblicarlo.',
      'not-vector': 'Le immagini si pubblicheranno quando GeoServer servirà i Cloud Optimized GeoTIFF.',
      invalid: 'Il nome o lo stile non sono validi.',
      'invalid-style': 'Lo stile non è un documento geoportal/style/v1 valido.',
      conflict: 'L’operazione è in conflitto con lo stato del catalogo.',
    },
    whatToDo: { retry: 'Puoi riprovare.', correct: 'Correggi e riprova.' },
    geoserverSaid: 'Risposta di GeoServer',
    reference: (r) => `Riferimento per l’assistenza: ${r}`,
    forbidden: 'Non hai il permesso per farlo.',
    failed: (s) => `Non è stato possibile completare l’operazione (${s}).`,
    // Reconciliation (GP-118).
    reconciliation: 'Riconciliazione con GeoServer',
    reconciliationHint: 'Confronta il workspace GeoServer dell’organizzazione con il catalogo.',
    compare: 'Confronta',
    align: 'Allinea',
    aligning: 'Allineamento…',
    groups: {
      aligned: 'Allineati',
      missing: 'Mancano in GeoServer',
      missingApplied: 'Mancavano in GeoServer: ricreati',
      changed: 'Modificati a mano in GeoServer',
      changedApplied: 'Modificati a mano: riportati al catalogo',
      withdrawn: 'Ritirati dal catalogo, ancora in GeoServer',
      withdrawnApplied: 'Ritirati dal catalogo: tolti da GeoServer',
      unknown: 'In GeoServer ma sconosciuti al catalogo (serve una persona)',
      failed: 'Non riusciti (serve una persona)',
    },
    kinds: { layer: 'livello', style: 'stile', store: 'store', 'coverage-store': 'store raster', workspace: 'workspace', schema: 'schema' },
    needsPerson: (n) => (n === 0 ? 'Niente da decidere.' : n === 1 ? '1 oggetto richiede una persona.' : `${n} oggetti richiedono una persona.`),
    adopt: 'Adotta',
    remove: 'Rimuovi da GeoServer',
    confirmRemove: (k, n) => `Rimuovere da GeoServer ${k} ${n}? Il catalogo non lo conosce: non si può annullare.`,
    adopted: (n) => `Il livello ${n} è ora gestito dal catalogo.`,
    removed: (n) => `${n} è stato rimosso da GeoServer.`,
    // GeoFence rules (GP-335).
    rules: 'Regole di accesso (GeoFence)',
    rulesHint: 'Generate dalla condivisione dei dati pubblicati: chi vede un livello e con quali servizi. Nessuno le modifica a mano in GeoServer.',
    rulesCount: (n) => (n === 1 ? '1 regola generata' : `${n} regole generate`),
    rulesWritten: (d) => `Scritte l’ultima volta ${d}`,
    rulesOutcome: {
      written: 'Riscritte e rilette: GeoFence ha esattamente queste regole.',
      unchanged: 'GeoFence ha già esattamente queste regole.',
      matching: 'GeoFence ha esattamente queste regole.',
      mismatch: 'GeoFence ha regole diverse da quelle generate.',
      failed: 'Le regole non si possono scrivere in GeoServer.',
      unknown: 'Le regole non sono ancora state scritte.',
    },
    rulesMismatches: 'Differenze (serve una persona)',
    rulesRemoved: 'Regole aggiunte a mano, tolte dall’ultima riscrittura',
    rulesWarnings: 'Cosa le regole non esprimono',
    rewrite: 'Riscrivi le regole',
    rewritten: 'Le regole di accesso sono state riscritte in GeoFence.',
  },
  en: {
    title: 'Published services',
    subtitle: 'The uploaded data that GeoServer serves as layers of the workspace of the organization, with their state.',
    columns: ['Layer', 'Dataset', 'Service state'],
    none: 'No published data.',
    count: (n) => (n === 1 ? '1 published service' : `${n} published services`),
    error: (s) => `The published services cannot be read (${s}).`,
    health: { ok: 'Answers', failed: 'Does not answer', unknown: 'To be checked', checking: 'Checking…' },
    healthCodes: {
      'layer-missing': 'GeoServer no longer knows this layer.',
      'data-error': 'GeoServer cannot read the data of the layer.',
      'geoserver-unreachable': 'GeoServer does not answer.',
      'credential-missing': 'The credential “geoserver” is missing, in the organization and in the installation.',
    },
    checkedAt: (d) => `Checked ${d}`,
    services: 'Service addresses',
    servicesHint: 'Through Geoportal, with your access: the internal address of GeoServer is never exposed.',
    desktopHint:
      'For QGIS and other desktop clients add &authkey= followed by a personal access key of yours to the address (Settings, Access keys): you see what you see here.',
    dataset: 'Dataset',
    style: 'Style',
    publishedAt: 'Published on',
    check: 'Check now',
    republish: 'Republish',
    repoint: 'Repoint',
    unpublish: 'Unpublish',
    close: 'Close',
    copy: 'Copy',
    copied: 'Address copied.',
    publish: 'Publish',
    publishTitle: (t) => `Publish “${t}”`,
    republishTitle: (n) => `Republish the layer ${n}`,
    destination: 'Destination',
    destinationValue: 'GeoServer workspace of the organization',
    layerName: 'Layer name',
    layerHint: 'Lowercase letters, digits and underscores; it stays when you republish.',
    geoportalStyle: 'Geoportal style',
    geoserverStyle: 'GeoServer style (translated SLD)',
    translating: 'Translating…',
    fill: 'Fill color',
    fillOpacity: 'Fill opacity',
    stroke: 'Stroke color',
    strokeWidth: 'Stroke width (px)',
    pointSize: 'Symbol size (px)',
    pointShape: 'Symbol shape',
    symbolType: 'Symbol type',
    types: { point: 'Point', line: 'Line', polygon: 'Polygon', mixed: 'All geometries' },
    strokeOpacity: 'Line opacity',
    lineColor: 'Line color',
    lineWidth: 'Line width (px)',
    labelField: 'Label (attribute)',
    noLabel: 'none',
    labelColor: 'Label color',
    labelSize: 'Label size (px)',
    notRepresentable: 'The style has values the panel does not show (driven by the data, or different per geometry): change them in the JSON document; a change in the panel replaces them.',
    document: 'Style document (JSON)',
    exportStyle: 'Export JSON',
    importStyle: 'Import JSON…',
    documentHint: 'The format geoportal/style/v1: it copies between layers, maps and installations.',
    problems: {
      json: 'The text is not valid JSON.',
      object: 'must be an object',
      grammar: 'the grammar is geoportal/style/v1',
      enum: (p) => `one of ${p.allowed.join(', ')}`,
      unknown: 'not allowed here',
      required: 'required',
      color: 'a color #rrggbb',
      number: (p) => `a number from ${p.min} to ${p.max}`,
      field: 'the name of an attribute: letters, digits and _, not starting with a digit',
      driver: 'one of categories, graduated and scaled; categories goes with otherwise',
      count: (p) => (p.count ? `${p.count} needed` : 'not a valid number of items'),
      breaks: 'from 1 to 50 increasing numbers',
      from: 'two numbers, the first lower',
      duplicate: 'another category has the same value',
      scalar: 'a text, a number, true or false',
      string: (p) => `a text up to ${p.max} characters`,
      boolean: 'true or false',
      text: 'a text, {"field": …} or {"template": …}',
      template: 'a text where {name} stands for an attribute',
      dash: 'from 2 to 8 numbers, dash and gap alternately',
    },
    shapes: { circle: 'Circle', square: 'Square', triangle: 'Triangle', star: 'Star', cross: 'Cross', x: 'X' },
    cancel: 'Cancel',
    retry: 'Try again',
    published: (n) => `The layer ${n} is published.`,
    republished: (n) => `The layer ${n} was republished.`,
    repointTitle: (n) => `Repoint the layer ${n}`,
    repointHint: 'The layer keeps its name and addresses and serves the chosen data: maps that use it keep working.',
    target: 'Data to serve',
    noTarget: 'No other loaded dataset you manage.',
    repointed: (n, t) => `The layer ${n} now serves “${t}”.`,
    unpublishTitle: (n) => `Unpublish the layer ${n}?`,
    unpublishWhat: 'GeoServer loses the layer, its style and its cached tiles. The data stay loaded and can be published again.',
    unpublishMaps: 'The maps of Geoportal are not linked to the catalog yet: whoever uses this address in another application will not find it any more.',
    unpublished: (n) => `The layer ${n} was unpublished.`,
    failure: {
      'geoserver-unreachable': 'GeoServer does not answer. Try again shortly; if it goes on, tell whoever runs GeoServer.',
      'geoserver-refused': 'GeoServer refused the operation.',
      'geodata-unreachable': 'The geodata database does not answer. Try again shortly.',
      'credential-missing': 'The credential “geoserver” or “geodata” is missing: ask a platform administrator to store it in the installation credentials, or an administrator of the organization in Administration, Credentials.',
      'name-in-use': 'The catalog already uses this name for another layer: choose another one.',
      'name-taken': 'GeoServer already has a layer or style with this name that the catalog does not know: choose another name, or adopt or remove it in the reconciliation.',
      'not-loaded': 'The dataset is not loaded into the geodata database: load it again before publishing it.',
      'not-vector': 'Images will be published when GeoServer serves Cloud Optimized GeoTIFF.',
      invalid: 'The name or the style is not valid.',
      'invalid-style': 'The style is not a valid geoportal/style/v1 document.',
      conflict: 'The operation conflicts with the state of the catalog.',
    },
    whatToDo: { retry: 'You can try again.', correct: 'Correct it and try again.' },
    geoserverSaid: 'GeoServer answered',
    reference: (r) => `Reference for support: ${r}`,
    forbidden: 'You are not allowed to do that.',
    failed: (s) => `The operation could not be completed (${s}).`,
    reconciliation: 'Reconciliation with GeoServer',
    reconciliationHint: 'Compares the GeoServer workspace of the organization with the catalog.',
    compare: 'Compare',
    align: 'Align',
    aligning: 'Aligning…',
    groups: {
      aligned: 'Aligned',
      missing: 'Missing from GeoServer',
      missingApplied: 'Were missing from GeoServer: created',
      changed: 'Changed by hand in GeoServer',
      changedApplied: 'Changed by hand: brought back to the catalog',
      withdrawn: 'Withdrawn by the catalog, still in GeoServer',
      withdrawnApplied: 'Withdrawn by the catalog: removed from GeoServer',
      unknown: 'In GeoServer but unknown to the catalog (needs a person)',
      failed: 'Failed (needs a person)',
    },
    kinds: { layer: 'layer', style: 'style', store: 'store', 'coverage-store': 'raster store', workspace: 'workspace', schema: 'schema' },
    needsPerson: (n) => (n === 0 ? 'Nothing to decide.' : n === 1 ? '1 object needs a person.' : `${n} objects need a person.`),
    adopt: 'Adopt',
    remove: 'Remove from GeoServer',
    confirmRemove: (k, n) => `Remove ${k} ${n} from GeoServer? The catalog does not know it: this cannot be undone.`,
    adopted: (n) => `The layer ${n} is now managed by the catalog.`,
    removed: (n) => `${n} was removed from GeoServer.`,
    // GeoFence rules (GP-335).
    rules: 'Access rules (GeoFence)',
    rulesHint: 'Generated from the sharing of the published data: who sees a layer, with which services. Nobody changes them by hand in GeoServer.',
    rulesCount: (n) => (n === 1 ? '1 rule generated' : `${n} rules generated`),
    rulesWritten: (d) => `Last written ${d}`,
    rulesOutcome: {
      written: 'Written again and read back: GeoFence holds exactly these rules.',
      unchanged: 'GeoFence already holds exactly these rules.',
      matching: 'GeoFence holds exactly these rules.',
      mismatch: 'GeoFence holds other rules than those generated.',
      failed: 'The rules cannot be written into GeoServer.',
      unknown: 'The rules have not been written yet.',
    },
    rulesMismatches: 'Differences (a person is needed)',
    rulesRemoved: 'Rules added by hand, removed by the last rewrite',
    rulesWarnings: 'What the rules do not express',
    rewrite: 'Rewrite the rules',
    rewritten: 'The access rules were written again into GeoFence.',
  },
};

const STYLE = `
  :host { display: block; }
  section { display: flex; flex-direction: column; gap: 12px; }
  h2 { margin: 0; font-size: 20px; }
  h3 { margin: 0; font-size: 17px; }
  h4 { margin: 6px 0 0; font-size: 14px; }
  p { margin: 4px 0 0; }
  .muted { color: var(--mk-muted); }
  .small { font-size: 13px; }
  .mono { font-family: var(--mk-font-mono, ui-monospace, monospace); font-size: 12px; }
  button { font: inherit; border: 1px solid var(--mk-line); background: none; color: var(--mk-fg);
    border-radius: var(--mk-radius); padding: 6px 12px; cursor: pointer; }
  button.primary { background: var(--mk-accent); border-color: var(--mk-accent); color: var(--mk-accent-fg, #fff); }
  button.danger { color: var(--mk-danger, #c0392b); border-color: currentColor; }
  button:disabled { opacity: .6; cursor: default; }
  table { width: 100%; border-collapse: collapse; background: var(--mk-surface); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--mk-muted);
    padding: 10px 12px; border-bottom: 1px solid var(--mk-line); }
  td { padding: 10px 12px; border-top: 1px solid var(--mk-line); vertical-align: middle; overflow-wrap: anywhere; }
  tr.row { cursor: pointer; }
  tr.row:hover td, tr.row[aria-selected=true] td { background: var(--mk-bg); }
  .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--mk-line); white-space: nowrap; }
  .pill.ok { border-color: var(--mk-success, #1e8e3e); color: var(--mk-success, #1e8e3e); }
  .pill.failed { border-color: var(--mk-danger, #c0392b); color: var(--mk-danger, #c0392b); }
  .pill.assigned { border-color: var(--mk-accent); color: var(--mk-accent); }
  ul.surveyors li { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
  .editing .failure { color: var(--mk-danger, #c0392b); }
  .panel { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 16px;
    display: flex; flex-direction: column; gap: 10px; }
  .panel[hidden], [hidden] { display: none !important; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; }
  dt { color: var(--mk-muted); font-size: 13px; }
  dd { margin: 0; overflow-wrap: anywhere; }
  .actions, .row-end { display: flex; flex-wrap: wrap; gap: 6px; }
  .row-end { justify-content: flex-end; }
  ul.urls { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  ul.urls li { display: flex; gap: 8px; align-items: center; }
  ul.urls code { flex: 1; overflow-wrap: anywhere; font-size: 12px; }
  ul.items { margin: 4px 0 0; padding-left: 18px; }
  ul.items li { margin: 2px 0; }
  .explained { border: 1px solid var(--mk-danger, #c0392b); border-radius: var(--mk-radius); padding: 10px 12px;
    display: flex; flex-direction: column; gap: 6px; }
  .explained b { color: var(--mk-danger, #c0392b); }
  dialog { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); background: var(--mk-surface);
    color: var(--mk-fg); padding: 20px; width: min(920px, calc(100vw - 32px)); }
  dialog.narrow { width: min(520px, calc(100vw - 32px)); }
  dialog::backdrop { background: rgb(0 0 0 / 40%); }
  form { display: flex; flex-direction: column; gap: 12px; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--mk-muted); }
  input, select { font: inherit; color: var(--mk-fg); background: var(--mk-bg); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); padding: 7px 9px; }
  input[type=color] { padding: 2px; height: 34px; }
  .styles { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr); gap: 16px; }
  @media (max-width: 720px) { .styles { grid-template-columns: minmax(0, 1fr); } }
  .styles fieldset { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 10px 12px; margin: 0;
    display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .styles legend, .sld-head { font-size: 13px; font-weight: 600; color: var(--mk-fg); }
  .swatch { grid-column: 1 / -1; display: flex; justify-content: center; padding: 8px; background: var(--mk-bg);
    border-radius: var(--mk-radius); }
  pre.sld { margin: 4px 0 0; max-height: 300px; overflow: auto; background: var(--mk-bg); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); padding: 10px; font-size: 11px; white-space: pre; }
  [role=status]:empty { display: none; }
`;

/** The publishing dialog brings its own rules: it opens in the shadow root of the uploaded data too. */
const DIALOG_STYLE = `
  dialog.publish { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); background: var(--mk-surface);
    color: var(--mk-fg); padding: 20px; width: min(920px, calc(100vw - 32px)); }
  dialog.publish::backdrop { background: rgb(0 0 0 / 40%); }
  dialog.publish form { display: flex; flex-direction: column; gap: 12px; }
  dialog.publish h3 { margin: 0; font-size: 17px; }
  dialog.publish p { margin: 0; }
  dialog.publish dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; }
  dialog.publish dt { color: var(--mk-muted); font-size: 13px; }
  dialog.publish dd { margin: 0; }
  dialog.publish label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--mk-muted); }
  dialog.publish input, dialog.publish select { font: inherit; color: var(--mk-fg); background: var(--mk-bg);
    border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 7px 9px; }
  dialog.publish input[type=color] { padding: 2px; height: 34px; }
  dialog.publish button { font: inherit; border: 1px solid var(--mk-line); background: none; color: var(--mk-fg);
    border-radius: var(--mk-radius); padding: 6px 12px; cursor: pointer; }
  dialog.publish button.primary { background: var(--mk-accent); border-color: var(--mk-accent); color: var(--mk-accent-fg, #fff); }
  dialog.publish button:disabled { opacity: .6; cursor: default; }
  dialog.publish .muted { color: var(--mk-muted); }
  dialog.publish .small { font-size: 13px; }
  dialog.publish .row-end, dialog.publish .actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
  dialog.publish .actions { justify-content: flex-start; }
  dialog.publish .styles { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr); gap: 16px; }
  @media (max-width: 720px) { dialog.publish .styles { grid-template-columns: minmax(0, 1fr); } }
  dialog.publish fieldset { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 10px 12px; margin: 0;
    display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  dialog.publish legend, dialog.publish .sld-head { font-size: 13px; font-weight: 600; color: var(--mk-fg); }
  dialog.publish fieldset .wide { grid-column: 1 / -1; }
  dialog.publish fieldset [hidden] { display: none; }
  dialog.publish .note { color: var(--mk-fg); background: var(--mk-bg); border-radius: var(--mk-radius); padding: 6px 8px; }
  dialog.publish details.document { display: flex; flex-direction: column; gap: 6px; }
  dialog.publish details.document summary { cursor: pointer; font-size: 13px; font-weight: 600; }
  dialog.publish details.document textarea { width: 100%; box-sizing: border-box; font-size: 12px; color: var(--mk-fg);
    background: var(--mk-bg); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 8px;
    font-family: var(--mk-font-mono, ui-monospace, monospace); margin-top: 6px; }
  dialog.publish .doc-problems { color: var(--mk-danger, #c0392b); }
  dialog.publish .doc-problems:empty { display: none; }
  dialog.publish .document-row { align-items: center; }
  dialog.publish .swatch { grid-column: 1 / -1; display: flex; justify-content: center; padding: 8px; background: var(--mk-bg);
    border-radius: var(--mk-radius); }
  dialog.publish pre.sld { margin: 4px 0 0; height: 300px; overflow: auto; background: var(--mk-bg);
    border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 10px; font-size: 11px; white-space: pre;
    font-family: var(--mk-font-mono, ui-monospace, monospace); }
  dialog.publish .explained { border: 1px solid var(--mk-danger, #c0392b); border-radius: var(--mk-radius); padding: 10px 12px;
    display: flex; flex-direction: column; gap: 6px; }
  dialog.publish .explained b { color: var(--mk-danger, #c0392b); }
`;

const language = (context) => (context.locale ?? 'en').split('-')[0];

/** A problem of a style document (GP-009) in the language of the person: where, and what is expected. */
function problemText(t, problem) {
  const what = t.problems[problem.code];
  return `${problem.path || '/'}: ${typeof what === 'function' ? what(problem) : (what ?? problem.code)}.`;
}
const texts = (context) => TEXTS[language(context)] ?? TEXTS.en;

/** The body of a refused call: the problem detail of the catalog, or what can be said of the status. */
async function problemOf(response) {
  try {
    return await response.json();
  } catch {
    return {};
  }
}

/**
 * The explanation of a failure (GP-115): what went wrong in the language of the person, what
 * GeoServer answered, what to do, and the reference of the log. Returns the element to show.
 */
function explain(context, status, problem, retry) {
  const t = texts(context);
  const box = document.createElement('div');
  box.className = 'explained';
  box.setAttribute('role', 'alert');
  const head = document.createElement('b');
  head.textContent =
    status === 403 ? t.forbidden : (t.failure[problem.code] ?? problem.detail ?? t.failed(status));
  box.append(head);
  if (problem.cause) {
    const said = document.createElement('div');
    said.className = 'small';
    said.innerHTML = '<span class="muted"></span>: <span class="cause"></span>';
    said.querySelector('.muted').textContent = t.geoserverSaid;
    said.querySelector('.cause').textContent = problem.cause;
    box.append(said);
  }
  if (problem.action) {
    const todo = document.createElement('div');
    todo.className = 'small';
    todo.textContent = t.whatToDo[problem.action] ?? '';
    box.append(todo);
  }
  if (problem.reference) {
    const ref = document.createElement('div');
    ref.className = 'small muted reference';
    ref.textContent = t.reference(problem.reference);
    box.append(ref);
  }
  if (retry) {
    const again = document.createElement('button');
    again.type = 'button';
    again.textContent = t.retry;
    again.addEventListener('click', retry);
    const row = document.createElement('div');
    row.className = 'actions';
    row.append(again);
    box.append(row);
  }
  return box;
}

function changed() {
  window.dispatchEvent(new CustomEvent(CHANGED));
}

/**
 * Opens the publishing dialog of a dataset (GP-114) inside a shadow root: the destination, the name of
 * the layer, the style of Geoportal and, next to it, the SLD GeoServer will receive, translated by the
 * catalog as the style changes. With {@code layer}, republishes that layer with a new style.
 *
 * @param root the shadow root that hosts the dialog
 * @param dataset the uploaded dataset ({id, title, technicalName, loaded})
 * @param options {layer, style, onDone(message)}
 */
export function openPublishDialog(context, root, dataset, options = {}) {
  const t = texts(context);
  root.querySelector('dialog.publish')?.remove();
  const dialog = document.createElement('dialog');
  dialog.className = 'publish';
  dialog.setAttribute('aria-labelledby', 'publish-title');
  const republishing = Boolean(options.layer);
  // GP-009: the style is a document of geoportal/style/v1; the panel is a facade over it.
  const initial = options.style ? migrate(options.style) : null;
  let doc = initial && validate(initial).length === 0
    ? initial
    : defaultStyle(kindOf(options.geometryType ?? dataset.loaded?.geometryType));
  dialog.innerHTML = `<style>${DIALOG_STYLE}</style>
    <form method="dialog" novalidate>
      <h3 id="publish-title"></h3>
      <dl><dt>${t.destination}</dt><dd>${t.destinationValue}</dd></dl>
      <label>${t.layerName}<input name="layer" maxlength="63" pattern="[a-z][a-z0-9_]{0,62}" required /></label>
      <p class="muted small">${t.layerHint}</p>
      <div class="styles">
        <fieldset><legend>${t.geoportalStyle}</legend>
          <label class="wide">${t.symbolType}<select name="type">${TYPES.map((k) => `<option value="${k}">${t.types[k]}</option>`).join('')}</select></label>
          <label data-key="shape">${t.pointShape}<select name="shape">${Object.entries(t.shapes)
            .map(([k, v]) => `<option value="${k}">${v}</option>`)
            .join('')}</select></label>
          <label data-key="fill">${t.fill}<input type="color" name="fill" /></label>
          <label data-key="fillOpacity">${t.fillOpacity}<input type="number" name="fillOpacity" min="0" max="1" step="0.05" /></label>
          <label data-key="stroke"><span class="text"></span><input type="color" name="stroke" /></label>
          <label data-key="strokeOpacity">${t.strokeOpacity}<input type="number" name="strokeOpacity" min="0" max="1" step="0.05" /></label>
          <label data-key="strokeWidth"><span class="text"></span><input type="number" name="strokeWidth" min="0" max="64" step="0.5" /></label>
          <label data-key="size">${t.pointSize}<input type="number" name="size" min="1" max="64" step="1" /></label>
          <label>${t.labelField}<input name="labelField" maxlength="63" placeholder="${t.noLabel}" autocomplete="off" /></label>
          <label data-key="labelColor">${t.labelColor}<input type="color" name="labelColor" /></label>
          <label data-key="labelSize">${t.labelSize}<input type="number" name="labelSize" min="6" max="72" step="1" /></label>
          <p class="note small wide" role="status" hidden>${t.notRepresentable}</p>
          <div class="swatch" aria-hidden="true"><svg width="160" height="40" viewBox="0 0 160 40">
            <rect x="4" y="6" width="44" height="28" rx="2"></rect><path d="M60 32 L100 8"></path><circle cx="132" cy="20" r="8"></circle></svg></div>
        </fieldset>
        <div><div class="sld-head" id="sld-head">${t.geoserverStyle}</div>
          <pre class="sld mono" aria-labelledby="sld-head" tabindex="0"></pre></div>
      </div>
      <div class="document-row actions"><button type="button" class="export">${t.exportStyle}</button>
        <button type="button" class="import">${t.importStyle}</button>
        <input type="file" name="styleFile" accept=".json,application/json" hidden />
        <span class="muted small">${t.documentHint}</span></div>
      <p class="doc-problems small" role="status"></p>
      <details class="document"><summary>${t.document}</summary>
        <textarea name="document" class="mono" aria-label="${t.document}" spellcheck="false" rows="12"></textarea>
      </details>
      <div class="failure"></div>
      <div class="row-end"><button type="button" class="cancel">${t.cancel}</button>
        <button type="button" class="primary confirm">${republishing ? t.republish : t.publish}</button></div>
    </form>`;
  root.append(dialog);
  dialog.querySelector('h3').textContent = republishing ? t.republishTitle(options.layer) : t.publishTitle(dataset.title);
  const form = dialog.querySelector('form');
  const name = form.querySelector('input[name=layer]');
  name.value = options.layer ?? dataset.technicalName;
  name.readOnly = republishing;
  const area = form.querySelector('textarea[name=document]');
  const docProblems = dialog.querySelector('.doc-problems');
  /** The values of the panel, for the fields of a type. */
  const readPanel = (type) => {
    const p = { type };
    for (const k of [...PANEL[type], ...LABEL_PANEL]) {
      const input = form.querySelector(`[name=${k}]`);
      p[k] = input.type === 'number' ? Number(input.value) : input.value.trim();
    }
    return p;
  };
  /** The JSON of the document, and whether the panel shows all of it. */
  const renderDocument = () => {
    area.value = JSON.stringify(doc, null, 2);
    docProblems.textContent = '';
    dialog.querySelector('.note').hidden = representable(doc);
  };
  /** The panel of the document; the JSON too, unless the person is writing it. */
  const render = (fromArea = false) => {
    const p = toPanel(doc);
    form.querySelector('[name=type]').value = p.type;
    for (const k of [...PANEL[p.type], ...LABEL_PANEL]) {
      form.querySelector(`[name=${k}]`).value = String(p[k]);
    }
    for (const label of form.querySelectorAll('fieldset label[data-key]')) {
      const key = label.dataset.key;
      label.hidden = !PANEL[p.type].includes(key) && !LABEL_PANEL.includes(key);
    }
    const ofLine = p.type === 'line';
    form.querySelector('label[data-key=stroke] .text').textContent = ofLine ? t.lineColor : t.stroke;
    form.querySelector('label[data-key=strokeWidth] .text').textContent = ofLine ? t.lineWidth : t.strokeWidth;
    if (fromArea) {
      dialog.querySelector('.note').hidden = representable(doc);
    } else {
      renderDocument();
    }
  };
  const sld = dialog.querySelector('pre.sld');
  sld.textContent = t.translating;
  const swatch = () => {
    const paint = (shape, fill, fillOpacity, stroke, strokeWidth) => {
      shape.setAttribute('fill', fill);
      shape.setAttribute('fill-opacity', String(fillOpacity));
      shape.setAttribute('stroke', stroke);
      shape.setAttribute('stroke-width', String(strokeWidth));
    };
    const point = symbolFor(doc, 'point').style;
    const line = symbolFor(doc, 'line').style;
    const poly = symbolFor(doc, 'polygon').style;
    const c = (v, fallback) => constantOf(v, fallback);
    paint(dialog.querySelector('.swatch rect'), c(poly.fill, '#888888'), c(poly.fillOpacity, 1), c(poly.stroke, '#444444'), c(poly.strokeWidth, 1));
    paint(dialog.querySelector('.swatch path'), 'none', 0, c(line.stroke, '#444444'), c(line.strokeWidth, 1));
    dialog.querySelector('.swatch path').setAttribute('stroke-opacity', String(c(line.strokeOpacity, 1)));
    const circle = dialog.querySelector('.swatch circle');
    paint(circle, c(point.fill, '#888888'), c(point.fillOpacity, 1), c(point.stroke, '#444444'), c(point.strokeWidth, 1));
    circle.setAttribute('r', String(Math.min(18, c(point.size, 8) / 2)));
  };
  let pending = 0;
  // GP-309: a raster is published with the default raster style of GeoServer, no style of Geoportal.
  const raster = Boolean(options.raster) || dataset.loaded?.geometryType === 'Raster';
  if (raster) {
    dialog.querySelector('.styles').hidden = true;
    dialog.querySelector('.document').hidden = true;
    dialog.querySelector('.document-row').hidden = true;
  }
  const translate = async () => {
    if (raster) {
      return;
    }
    swatch();
    const mine = ++pending;
    // The SLD stays until the new one arrives: the dialog must not change size under the pointer.
    sld.setAttribute('aria-busy', 'true');
    const response = await context.fetch(`${API}/style-translation`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dataset: dataset.id, layer: name.value.trim(), style: doc }),
    });
    if (mine !== pending) {
      return;
    }
    sld.removeAttribute('aria-busy');
    if (!response.ok) {
      const problem = await problemOf(response);
      sld.textContent = problem.detail ?? t.failed(response.status);
      return;
    }
    sld.textContent = (await response.json()).sld;
  };
  /** A document written or imported as JSON: taken when valid, its problems shown otherwise. */
  const take = (text) => {
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      docProblems.textContent = t.problems.json;
      return false;
    }
    const problems = validate(migrate(parsed));
    if (problems.length > 0) {
      docProblems.textContent = problems.slice(0, 5).map((p) => problemText(t, p)).join(' ');
      return false;
    }
    doc = migrate(parsed);
    docProblems.textContent = '';
    render(true);
    void translate();
    return true;
  };
  form.addEventListener('input', (event) => {
    const field = event.target.name;
    if (field === 'document') {
      take(area.value);
    } else if (field !== 'type' && field !== 'layer' && field !== 'styleFile') {
      // The fields stay as the person writes them; the document follows when they make a valid one.
      const next = fromPanel(readPanel(doc.type), doc);
      const problems = validate(next);
      if (problems.length > 0) {
        docProblems.textContent = problems.slice(0, 5).map((p) => problemText(t, p)).join(' ');
        return;
      }
      doc = next;
      renderDocument();
      void translate();
    } else if (field === 'layer') {
      void translate();
    }
  });
  form.querySelector('[name=type]').addEventListener('change', (event) => {
    // GP-009: what has the same meaning in the new type stays.
    doc = retype(doc, event.target.value);
    render();
    void translate();
  });
  dialog.querySelector('.export').addEventListener('click', () => {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(new Blob([`${JSON.stringify(doc, null, 2)}\n`], { type: 'application/json' }));
    link.download = `${name.value.trim() || 'stile'}.style.json`;
    dialog.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
  });
  const file = form.querySelector('input[name=styleFile]');
  dialog.querySelector('.import').addEventListener('click', () => file.click());
  file.addEventListener('change', async () => {
    const chosen = file.files?.[0];
    if (!chosen) {
      return;
    }
    const text = await chosen.text();
    area.value = text;
    dialog.querySelector('details.document').open = true;
    if (take(text)) {
      renderDocument();
    }
    file.value = '';
  });
  render();
  dialog.querySelector('.cancel').addEventListener('click', () => dialog.close());
  const submit = async () => {
    const button = form.querySelector('button.confirm');
    const failure = dialog.querySelector('.failure');
    button.disabled = true;
    failure.replaceChildren();
    try {
      const response = republishing
        ? await context.fetch(`${API}/${encodeURIComponent(options.layer)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ style: doc }),
          })
        : await context.fetch(API, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dataset: dataset.id, layer: name.value.trim(), style: doc }),
          });
      if (!response.ok) {
        failure.append(explain(context, response.status, await problemOf(response), () => void submit()));
        return;
      }
      const publication = await response.json();
      dialog.close();
      changed();
      options.onDone?.(republishing ? t.republished(publication.name) : t.published(publication.name));
    } finally {
      button.disabled = false;
    }
  };
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void submit();
  });
  form.querySelector('button.confirm').addEventListener('click', () => void submit());
  dialog.showModal();
  void translate();
  return dialog;
}

/** Defines <catalog-publications>, which the catalog places under the uploaded data. */
export function definePublications(context) {
  if (customElements.get('catalog-publications')) {
    return;
  }
  const t = () => texts(context);
  const when = (iso) =>
    iso ? new Intl.DateTimeFormat(context.locale, { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso)) : '';

  class Publications extends HTMLElement {
    connectedCallback() {
      this.items = [];
      this.chosen = undefined;
      const tx = t();
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `
        <style>${STYLE}</style>
        <section aria-labelledby="pub-title">
          <div><h2 id="pub-title">${tx.title}</h2><p class="muted small">${tx.subtitle}</p></div>
          <p class="muted note" aria-live="polite"></p>
          <table><thead><tr>${tx.columns.map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody></tbody></table>
          <div class="panel detail" hidden></div>
          <div class="panel reconciliation" hidden aria-labelledby="rec-title">
            <div><h3 id="rec-title">${tx.reconciliation}</h3><p class="muted small">${tx.reconciliationHint}</p></div>
            <div class="actions"><button type="button" class="compare">${tx.compare}</button>
              <button type="button" class="align primary">${tx.align}</button></div>
            <div class="report"></div>
          </div>
        </section>`;
      this.root = root;
      root.querySelector('.compare').addEventListener('click', () => void this.reconcile(false));
      root.querySelector('.align').addEventListener('click', () => void this.reconcile(true));
      this.onChanged = () => void this.load();
      window.addEventListener(CHANGED, this.onChanged);
      void this.load();
      void this.probeReconciliation();
    }

    disconnectedCallback() {
      window.removeEventListener(CHANGED, this.onChanged);
    }

    /** Says what happened; the message stays when the list is drawn again after the change. */
    say(text) {
      this.message = text;
      this.root.querySelector('.note').textContent = text;
    }

    async load() {
      const response = await context.fetch(API);
      if (!response.ok) {
        this.say(t().error(response.status));
        return;
      }
      this.items = await response.json();
      // The layers assigned to the person (GP-036), which also tells the catalog whether they hold the permission.
      this.mine = await myLayers(context);
      this.editingBox = undefined;
      if (this.chosen) {
        this.chosen = this.items.find((p) => p.name === this.chosen.name);
      }
      this.render();
      // The health of each service, asked of GeoServer now.
      for (const p of this.items) {
        void this.check(p, true);
      }
    }

    render() {
      const tx = t();
      this.root.querySelector('.note').textContent =
        this.message ?? (this.items.length === 0 ? tx.none : tx.count(this.items.length));
      this.root.querySelector('tbody').replaceChildren(...this.items.map((p) => this.row(p, tx)));
      this.renderDetail();
    }

    healthPill(p, tx) {
      const pill = document.createElement('span');
      pill.className = `pill health ${p.health.status}`;
      pill.textContent = p.checking ? tx.health.checking : (tx.health[p.health.status] ?? p.health.status);
      return pill;
    }

    row(p, tx) {
      const row = document.createElement('tr');
      row.className = 'row';
      row.tabIndex = 0;
      row.dataset.layer = p.name;
      row.setAttribute('aria-selected', String(p.name === this.chosen?.name));
      row.innerHTML = '<td><b class="mono"></b></td><td></td><td></td>';
      row.querySelector('b').textContent = p.name;
      row.children[1].textContent = p.datasetTitle;
      const badge = assignedBadge(context, this.mine, p.name);
      if (badge) {
        row.children[0].append(' ', badge);
      }
      row.children[2].append(this.healthPill(p, tx));
      if (p.health.status === 'failed' && p.health.code) {
        const why = document.createElement('div');
        why.className = 'small muted';
        why.textContent = tx.healthCodes[p.health.code] ?? p.health.reason ?? '';
        row.children[2].append(why);
      }
      const open = () => {
        this.chosen = p;
        this.render();
      };
      row.addEventListener('click', open);
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
          open();
        }
      });
      return row;
    }

    renderDetail() {
      const tx = t();
      const panel = this.root.querySelector('.detail');
      const p = this.chosen;
      panel.hidden = !p;
      if (!p) {
        return;
      }
      panel.setAttribute('aria-label', p.name);
      panel.innerHTML = `<div class="row-end"><button type="button" class="close">${tx.close}</button></div>
        <h3 class="mono"></h3><dl></dl>
        <h4>${tx.services}</h4><p class="muted small">${tx.servicesHint}</p><ul class="urls"></ul>
        <p class="muted small desktop" ${p.gateway ? '' : 'hidden'}>${tx.desktopHint}</p>
        <div class="editing-slot"></div><div class="actions"></div><div class="failure"></div>`;
      panel.querySelector('h3').textContent = p.name;
      // Kept while the same publication stays open, so that a health check does not ask for its surveyors again.
      if (this.editingBox?.dataset.layer !== p.name) {
        this.editingBox = editingSection(context, p, this.mine, (m) => this.say(m));
        this.editingBox.dataset.layer = p.name;
      }
      panel.querySelector('.editing-slot').append(this.editingBox);
      const health = document.createElement('span');
      health.append(this.healthPill(p, tx));
      if (p.health.checkedAt) {
        health.append(` ${tx.checkedAt(when(p.health.checkedAt))}`);
      }
      const rows = [
        [tx.dataset, p.datasetTitle],
        [tx.columns[2], health],
        ...(p.health.status === 'failed' ? [['', `${tx.healthCodes[p.health.code] ?? ''} ${p.health.reason ?? ''}`.trim()]] : []),
        [tx.publishedAt, when(p.publishedAt)],
      ];
      panel.querySelector('dl').replaceChildren(
        ...rows.flatMap(([label, value]) => {
          const dt = document.createElement('dt');
          dt.textContent = label;
          const dd = document.createElement('dd');
          if (value instanceof Node) {
            dd.append(value);
          } else {
            dd.textContent = value;
          }
          return [dt, dd];
        }),
      );
      panel.querySelector('ul.urls').replaceChildren(
        ...Object.entries(p.services).map(([kind, path]) => {
          const li = document.createElement('li');
          li.innerHTML = '<b></b><code class="mono"></code><button type="button"></button>';
          const url = new URL(path, location.origin).toString();
          li.querySelector('b').textContent = kind.toUpperCase();
          li.querySelector('code').textContent = url;
          li.querySelector('button').textContent = tx.copy;
          li.querySelector('button').setAttribute('aria-label', `${tx.copy} ${kind.toUpperCase()}`);
          li.querySelector('button').addEventListener('click', async () => {
            try {
              await navigator.clipboard.writeText(url);
              this.say(tx.copied);
            } catch {
              // no clipboard: the address is on the page
            }
          });
          return li;
        }),
      );
      panel.querySelector('.close').addEventListener('click', () => {
        this.chosen = undefined;
        this.render();
      });
      // Its own row: the editing section of GP-036, kept across redraws, has a form of class actions too.
      const actions = panel.querySelector(':scope > .actions');
      const action = (label, run, extra = '') => {
        const b = document.createElement('button');
        b.type = 'button';
        b.textContent = label;
        if (extra) {
          b.className = extra;
        }
        b.addEventListener('click', run);
        actions.append(b);
      };
      action(tx.check, () => void this.check(p, false));
      if (p.canPublish) {
        action(tx.republish, () =>
          openPublishDialog(context, this.root, { id: p.dataset, title: p.datasetTitle, technicalName: p.datasetName }, {
            layer: p.name,
            style: p.styleSource ?? undefined,
            geometryType: p.geometryType,
            onDone: (message) => this.say(message),
          }),
        );
        action(tx.repoint, () => void this.openRepoint(p));
        action(tx.unpublish, () => this.openUnpublish(p), 'danger');
      }
      // GP-308: the data rules of the layer, for those who manage its dataset; kept across redraws.
      if (p.level === 'manage') {
        this.ruleSections ??= new Map();
        if (!this.ruleSections.has(p.name)) {
          this.ruleSections.set(p.name, dataRulesSection(context, p, (message) => this.say(message)));
        }
        panel.append(this.ruleSections.get(p.name));
      }
    }

    /** Asks the catalog to check the service now; quietly on load, with the result in the panel on demand. */
    async check(p, quiet) {
      p.checking = true;
      this.render();
      const response = await context.fetch(`${API}/${encodeURIComponent(p.name)}/health`, { method: 'POST' });
      p.checking = false;
      if (response.ok) {
        const fresh = await response.json();
        Object.assign(p, fresh);
        if (this.chosen?.name === p.name) {
          this.chosen = p;
        }
      } else if (!quiet) {
        this.root.querySelector('.detail .failure')?.replaceChildren(explain(context, response.status, await problemOf(response)));
      }
      this.render();
    }

    async openRepoint(p) {
      const tx = t();
      const response = await context.fetch('/api/v1/ext/catalog-geodata/uploads');
      const all = response.ok ? await response.json() : [];
      const targets = all.filter(
        (d) => d.id !== p.dataset && d.level === 'manage' && (d.state === 'loaded' || d.state === 'published') && d.loaded?.table,
      );
      this.root.querySelector('dialog.repoint')?.remove();
      const dialog = document.createElement('dialog');
      dialog.className = 'repoint narrow';
      dialog.setAttribute('aria-labelledby', 'repoint-title');
      dialog.innerHTML = `<form method="dialog"><h3 id="repoint-title"></h3><p class="muted small">${tx.repointHint}</p>
        <label>${tx.target}<select name="dataset"></select></label><p class="muted small empty" hidden>${tx.noTarget}</p>
        <div class="failure"></div>
        <div class="row-end"><button type="button" class="cancel">${tx.cancel}</button>
          <button type="button" class="primary confirm">${tx.repoint}</button></div></form>`;
      dialog.querySelector('h3').textContent = tx.repointTitle(p.name);
      const select = dialog.querySelector('select');
      for (const d of targets) {
        const option = document.createElement('option');
        option.value = d.id;
        option.textContent = `${d.title} (${d.technicalName})`;
        select.append(option);
      }
      dialog.querySelector('.empty').hidden = targets.length > 0;
      dialog.querySelector('button.confirm').disabled = targets.length === 0;
      dialog.querySelector('.cancel').addEventListener('click', () => dialog.close());
      const submit = async () => {
        const failure = dialog.querySelector('.failure');
        failure.replaceChildren();
        const r = await context.fetch(`${API}/${encodeURIComponent(p.name)}/repoint`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ dataset: select.value }),
        });
        if (!r.ok) {
          failure.append(explain(context, r.status, await problemOf(r), () => void submit()));
          return;
        }
        const moved = await r.json();
        dialog.close();
        changed();
        this.say(tx.repointed(moved.name, moved.datasetTitle));
      };
      dialog.querySelector('form').addEventListener('submit', (event) => {
        event.preventDefault();
        void submit();
      });
      dialog.querySelector('button.confirm').addEventListener('click', () => void submit());
      this.root.append(dialog);
      dialog.showModal();
    }

    openUnpublish(p) {
      const tx = t();
      this.root.querySelector('dialog.unpublish')?.remove();
      const dialog = document.createElement('dialog');
      dialog.className = 'unpublish narrow';
      dialog.setAttribute('aria-labelledby', 'unpublish-title');
      dialog.innerHTML = `<form method="dialog"><h3 id="unpublish-title"></h3>
        <p>${tx.unpublishWhat}</p><p class="muted small">${tx.unpublishMaps}</p><div class="failure"></div>
        <div class="row-end"><button type="button" class="cancel">${tx.cancel}</button>
          <button type="button" class="danger confirm">${tx.unpublish}</button></div></form>`;
      dialog.querySelector('h3').textContent = tx.unpublishTitle(p.name);
      dialog.querySelector('.cancel').addEventListener('click', () => dialog.close());
      const submit = async () => {
        const failure = dialog.querySelector('.failure');
        failure.replaceChildren();
        const r = await context.fetch(`${API}/${encodeURIComponent(p.name)}`, { method: 'DELETE' });
        if (!r.ok) {
          failure.append(explain(context, r.status, await problemOf(r), () => void submit()));
          return;
        }
        dialog.close();
        this.chosen = undefined;
        changed();
        this.say(tx.unpublished(p.name));
      };
      dialog.querySelector('form').addEventListener('submit', (event) => {
        event.preventDefault();
        void submit();
      });
      dialog.querySelector('button.confirm').addEventListener('click', () => void submit());
      this.root.append(dialog);
      dialog.showModal();
    }

    // Reconciliation (GP-118), for those who manage the GeoServer publication -------------------

    async probeReconciliation() {
      const response = await context.fetch(`${GEOSERVER}/layers`);
      this.root.querySelector('.reconciliation').hidden = !response.ok;
    }

    async reconcile(apply) {
      const tx = t();
      const report = this.root.querySelector('.report');
      report.replaceChildren(document.createTextNode(apply ? tx.aligning : '…'));
      const response = await context.fetch(`${GEOSERVER}/reconciliation`, { method: apply ? 'POST' : 'GET' });
      if (!response.ok) {
        report.replaceChildren(explain(context, response.status, await problemOf(response), () => void this.reconcile(apply)));
        return;
      }
      const r = await response.json();
      const parts = [];
      const summary = document.createElement('p');
      summary.className = 'needs';
      summary.textContent = tx.needsPerson(r.needsPerson);
      parts.push(summary);
      const groups = [
        ['failed', tx.groups.failed],
        ['unknown', tx.groups.unknown],
        ['missing', r.applied ? tx.groups.missingApplied : tx.groups.missing],
        ['changed', r.applied ? tx.groups.changedApplied : tx.groups.changed],
        ['withdrawn', r.applied ? tx.groups.withdrawnApplied : tx.groups.withdrawn],
        ['aligned', tx.groups.aligned],
      ];
      for (const [key, label] of groups) {
        const items = r[key] ?? [];
        if (items.length === 0) {
          continue;
        }
        const group = document.createElement('div');
        group.className = `group ${key}`;
        group.setAttribute('role', 'group');
        group.setAttribute('aria-label', label);
        group.innerHTML = '<h4></h4><ul class="items"></ul>';
        group.querySelector('h4').textContent = `${label} (${items.length})`;
        group.querySelector('ul').replaceChildren(
          ...items.map((item) => {
            const li = document.createElement('li');
            const kind = tx.kinds[item.kind] ?? item.kind;
            li.innerHTML = '<span class="muted"></span> <b class="mono"></b> <span class="small muted why"></span>';
            li.querySelector('.muted').textContent = kind;
            li.querySelector('b').textContent = item.name;
            const why = [key === 'changed' ? item.reason : '', item.cause ?? ''].filter(Boolean).join(' · ');
            li.querySelector('.why').textContent = why;
            if (key === 'unknown' && (item.kind === 'layer' || item.kind === 'style')) {
              if (item.kind === 'layer') {
                li.append(' ', this.reconcileButton(tx.adopt, `${GEOSERVER}/reconciliation/layers/${item.name}/adopt`, 'POST', tx.adopted(item.name)));
              }
              li.append(' ', this.reconcileButton(tx.remove, `${GEOSERVER}/reconciliation/${item.kind}s/${item.name}`, 'DELETE',
                tx.removed(item.name), tx.confirmRemove(kind, item.name)));
            }
            return li;
          }),
        );
        parts.push(group);
      }
      if (r.rules) {
        parts.push(this.rulesReport(r.rules, tx));
      }
      report.replaceChildren(...parts);
    }

    /** The GeoFence rules of the workspace (GP-335): how many, whether GeoFence holds them, what differs. */
    rulesReport(rules, tx) {
      const box = document.createElement('div');
      box.className = `group rules ${rules.outcome}`;
      box.setAttribute('role', 'group');
      box.setAttribute('aria-label', tx.rules);
      box.innerHTML = '<h4></h4><p class="small muted hint"></p><p class="outcome"></p><p class="small muted count"></p>';
      box.querySelector('h4').textContent = tx.rules;
      box.querySelector('.hint').textContent = tx.rulesHint;
      box.querySelector('.outcome').textContent = tx.rulesOutcome[rules.outcome] ?? rules.outcome;
      box.querySelector('.count').textContent = [tx.rulesCount(rules.rules), rules.writtenAt ? tx.rulesWritten(when(rules.writtenAt)) : '']
        .filter(Boolean).join(' · ');
      const list = (key, label, lines) => {
        if (!lines || lines.length === 0) {
          return;
        }
        const h = document.createElement('h4');
        h.textContent = `${label} (${lines.length})`;
        const ul = document.createElement('ul');
        ul.className = `items ${key}`;
        ul.replaceChildren(...lines.map((line) => {
          const li = document.createElement('li');
          li.className = 'mono';
          li.textContent = line;
          return li;
        }));
        box.append(h, ul);
      };
      if (rules.error) {
        const e = document.createElement('p');
        e.className = 'small muted cause';
        e.textContent = rules.error;
        box.append(e);
      }
      list('mismatches', tx.rulesMismatches, rules.mismatches);
      list('removed', tx.rulesRemoved, rules.removed);
      list('warnings', tx.rulesWarnings, rules.warnings);
      const actions = document.createElement('div');
      actions.className = 'actions';
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'rewrite';
      button.textContent = tx.rewrite;
      button.addEventListener('click', async () => {
        button.disabled = true;
        try {
          const response = await context.fetch(`${GEOSERVER}/rules`, { method: 'POST' });
          if (!response.ok) {
            box.append(explain(context, response.status, await problemOf(response)));
            return;
          }
          this.say(tx.rewritten);
          await this.reconcile(false);
        } finally {
          button.disabled = false;
        }
      });
      actions.append(button);
      box.append(actions);
      return box;
    }

    reconcileButton(label, url, method, done, confirmText) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.addEventListener('click', async () => {
        if (confirmText && !confirm(confirmText)) {
          return;
        }
        const r = await context.fetch(url, { method });
        if (!r.ok) {
          this.root.querySelector('.report').prepend(explain(context, r.status, await problemOf(r)));
          return;
        }
        this.say(done);
        changed();
        await this.reconcile(false);
      });
      return b;
    }
  }

  customElements.define('catalog-publications', Publications);
}
