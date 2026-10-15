// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * The uploaded data of the catalog (GP-936): the datasets uploaded to the organization that the
 * person sees, the upload dialog (screen geoportal:dlg-upload) with the space left, and for each
 * dataset rename, replace, download, share (the dialog of Mosaikit) and delete, offered by the level
 * of the person; the backend checks them again.
 */

import { CHANGED, openPublishDialog } from './publications.js';

const API = '/api/v1/ext/catalog/uploads';
const LEVELS = ['view', 'download', 'edit', 'edit-data', 'manage'];

const TEXTS = {
  it: {
    title: 'Dati caricati',
    subtitle: 'I file caricati nell’organizzazione: ognuno ha un titolo leggibile e un nome tecnico, che diventa il nome della tabella e del livello.',
    upload: 'Carica dati',
    count: (n) => (n === 1 ? '1 dato caricato' : `${n} dati caricati`),
    none: 'Nessun dato caricato.',
    error: (s) => `I dati caricati non si possono leggere (${s}).`,
    columns: ['Dato', 'Formato', 'Dimensione', 'Stato'],
    space: (used, limit) => (limit == null ? `Spazio usato: ${used}` : `Spazio: ${used} di ${limit}`),
    states: {
      uploaded: 'Archiviato, da caricare',
      loading: 'In caricamento',
      loaded: 'Caricato, da pubblicare',
      published: 'Pubblicato',
      failed: 'Non caricato',
    },
    codes: {
      'crs-missing': 'Il file non dichiara il suo sistema di riferimento: indica il codice EPSG e caricalo di nuovo.',
      'crs-unknown': 'Il sistema di riferimento del file non è riconosciuto: indica il codice EPSG e caricalo di nuovo.',
      'crs-mismatch': 'Le coordinate non sono nel sistema di riferimento dichiarato (sembrano metri, non gradi): indica il codice EPSG.',
      unreadable: 'Il file non si può leggere nel suo formato.',
      empty: 'Il file non contiene elementi con una geometria.',
      'geometry-invalid': 'Una geometria del file non è valida.',
      'several-layers': 'Il file contiene più livelli: scegli quello che questo dato carica.',
      'loader-unavailable': 'Il servizio che carica i file non risponde: il file è archiviato, riprova più tardi.',
      'loader-lost': 'Il servizio che carica i file si è riavviato durante il caricamento: carica di nuovo il file.',
      'loader-error': 'Il servizio che carica i file non è riuscito a completare il caricamento: riprova più tardi.',
      quota: 'Lo spazio dell’organizzazione non basta per i dati caricati.',
      'geodata-missing': 'Il database dei dati geografici non è configurato per l’organizzazione: il file è archiviato, non caricato.',
      'geodata-unavailable': 'Il database dei dati geografici non risponde: il file è archiviato, riprova più tardi.',
    },
    sources: { file: 'dichiarato nel file', format: 'definito dal formato', declared: 'dichiarato a mano' },
    reason: 'Motivo',
    crs: 'Sistema di riferimento',
    geometry: 'Geometria',
    withZ: (type) => `${type}, con quota (Z)`,
    raster: 'Immagine (Cloud Optimized GeoTIFF)',
    features: 'Elementi',
    pixels: 'Pixel',
    extent: 'Estensione (WGS84)',
    noExtent: 'Non calcolabile in modo affidabile',
    box: (w, s, e, n) => `O ${w} · S ${s} · E ${e} · N ${n}`,
    table: 'Tabella PostGIS',
    crsLabel: 'Codice EPSG (solo se il file non dichiara il sistema)',
    crsPlaceholder: 'per esempio EPSG:32632',
    reload: 'Carica di nuovo',
    loadingProgress: (p) => `In caricamento (${p}%)`,
    layer: 'Livello',
    layerLabel: 'Livello da caricare',
    layerOption: (l) => (l.features ? `${l.name} (${l.features === 1 ? '1 elemento' : `${l.features} elementi`})` : l.name),
    inProgress: (t) => `«${t}» è archiviato ed è in caricamento.`,
    reloaded: (t) => `«${t}» è stato caricato.`,
    notLoaded: (t, why) => `«${t}» è archiviato ma non caricato: ${why}`,
    levels: { view: 'Vedere', download: 'Scaricare', edit: 'Modificare', 'edit-data': 'Modificare i dati', manage: 'Gestire' },
    sample: 'Esempio',
    file: 'File',
    fileLabel: 'File da caricare',
    titleLabel: 'Titolo',
    descriptionLabel: 'Descrizione',
    technicalName: 'Nome tecnico',
    format: 'Formato',
    size: 'Dimensione',
    state: 'Stato',
    version: 'Versione',
    level: 'Il tuo livello',
    accepted: (formats) => `Formati accettati: ${formats.join(', ')}.`,
    maxFile: (max) => `Dimensione massima di un file: ${max}.`,
    tooLarge: (max) => `Il file è troppo grande: si possono caricare file fino a ${max}.`,
    send: 'Carica',
    cancel: 'Annulla',
    close: 'Chiudi',
    rename: 'Rinomina',
    save: 'Salva',
    replace: 'Sostituisci il file',
    download: 'Scarica',
    share: 'Condividi',
    remove: 'Elimina',
    publish: 'Pubblica',
    layers: 'Livelli pubblicati',
    confirmRemove: (t) => `Eliminare «${t}»? Il file e le condivisioni vanno persi.`,
    uploading: 'Caricamento in corso…',
    uploaded: (t) => `«${t}» è stato caricato.`,
    replaced: (t) => `Il file di «${t}» è stato sostituito.`,
    renamed: (t) => `Il dato ora si chiama «${t}».`,
    removed: (t) => `«${t}» è stato eliminato.`,
    quota: 'Lo spazio dell’organizzazione non basta per questo file.',
    forbidden: 'Non hai il permesso per farlo.',
    refused: (detail) => `Il file non è stato accettato: ${detail}`,
    failed: (s) => `Non è stato possibile completare l’operazione (${s}).`,
  },
  en: {
    title: 'Uploaded data',
    subtitle: 'The files uploaded to the organization: each has a readable title and a technical name, which becomes the name of its table and layer.',
    upload: 'Upload data',
    count: (n) => (n === 1 ? '1 uploaded dataset' : `${n} uploaded datasets`),
    none: 'No uploaded data.',
    error: (s) => `The uploaded data cannot be read (${s}).`,
    columns: ['Dataset', 'Format', 'Size', 'State'],
    space: (used, limit) => (limit == null ? `Space used: ${used}` : `Space: ${used} of ${limit}`),
    states: {
      uploaded: 'Stored, to be loaded',
      loading: 'Loading',
      loaded: 'Loaded, to be published',
      published: 'Published',
      failed: 'Not loaded',
    },
    codes: {
      'crs-missing': 'The file states no reference system: give its EPSG code and load it again.',
      'crs-unknown': 'The reference system of the file is not recognized: give its EPSG code and load it again.',
      'crs-mismatch': 'The coordinates are not in the stated reference system (they look like metres, not degrees): give its EPSG code.',
      unreadable: 'The file cannot be read as its format.',
      empty: 'The file holds no feature with a geometry.',
      'geometry-invalid': 'A geometry of the file is not valid.',
      'several-layers': 'The file holds several layers: choose the one this dataset loads.',
      'loader-unavailable': 'The service that loads files does not answer: the file is stored, try again later.',
      'loader-lost': 'The service that loads files restarted while loading: load the file again.',
      'loader-error': 'The service that loads files could not finish: try again later.',
      quota: 'The space of the organization is not enough for the loaded data.',
      'geodata-missing': 'The geodata database is not configured for the organization: the file is stored, not loaded.',
      'geodata-unavailable': 'The geodata database does not answer: the file is stored, try again later.',
    },
    sources: { file: 'stated by the file', format: 'defined by the format', declared: 'declared by hand' },
    reason: 'Reason',
    crs: 'Reference system',
    geometry: 'Geometry',
    withZ: (type) => `${type}, with height (Z)`,
    raster: 'Image (Cloud Optimized GeoTIFF)',
    features: 'Features',
    pixels: 'Pixels',
    extent: 'Extent (WGS84)',
    noExtent: 'Cannot be computed reliably',
    box: (w, s, e, n) => `W ${w} · S ${s} · E ${e} · N ${n}`,
    table: 'PostGIS table',
    crsLabel: 'EPSG code (only if the file does not state its system)',
    crsPlaceholder: 'for example EPSG:32632',
    reload: 'Load again',
    loadingProgress: (p) => `Loading (${p}%)`,
    layer: 'Layer',
    layerLabel: 'Layer to load',
    layerOption: (l) => (l.features ? `${l.name} (${l.features === 1 ? '1 feature' : `${l.features} features`})` : l.name),
    inProgress: (t) => `“${t}” is stored and loading.`,
    reloaded: (t) => `“${t}” was loaded.`,
    notLoaded: (t, why) => `“${t}” is stored but not loaded: ${why}`,
    levels: { view: 'View', download: 'Download', edit: 'Edit', 'edit-data': 'Edit the data', manage: 'Manage' },
    sample: 'Sample',
    file: 'File',
    fileLabel: 'File to upload',
    titleLabel: 'Title',
    descriptionLabel: 'Description',
    technicalName: 'Technical name',
    format: 'Format',
    size: 'Size',
    state: 'State',
    version: 'Version',
    level: 'Your level',
    accepted: (formats) => `Accepted formats: ${formats.join(', ')}.`,
    maxFile: (max) => `Largest file: ${max}.`,
    tooLarge: (max) => `The file is too large: files up to ${max} can be uploaded.`,
    send: 'Upload',
    cancel: 'Cancel',
    close: 'Close',
    rename: 'Rename',
    save: 'Save',
    replace: 'Replace the file',
    download: 'Download',
    share: 'Share',
    remove: 'Delete',
    publish: 'Publish',
    layers: 'Published layers',
    confirmRemove: (t) => `Delete “${t}”? The file and its sharing are lost.`,
    uploading: 'Uploading…',
    uploaded: (t) => `“${t}” was uploaded.`,
    replaced: (t) => `The file of “${t}” was replaced.`,
    renamed: (t) => `The dataset is now called “${t}”.`,
    removed: (t) => `“${t}” was deleted.`,
    quota: 'The space of the organization is not enough for this file.',
    forbidden: 'You are not allowed to do that.',
    refused: (detail) => `The file was not accepted: ${detail}`,
    failed: (s) => `The operation could not be completed (${s}).`,
  },
};

const STYLE = `
  :host { display: block; }
  section { display: flex; flex-direction: column; gap: 12px; }
  .head { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
  .head > div { flex: 1 1 320px; }
  h2 { margin: 0; font-size: 20px; }
  h3 { margin: 0; font-size: 17px; }
  p { margin: 4px 0 0; }
  .muted { color: var(--mk-muted); }
  .small { font-size: 13px; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  button { font: inherit; border: 1px solid var(--mk-line); background: none; color: var(--mk-fg);
    border-radius: var(--mk-radius); padding: 6px 12px; cursor: pointer; }
  button.primary { background: var(--mk-accent); border-color: var(--mk-accent); color: var(--mk-accent-fg, #fff); }
  button.danger { color: var(--mk-danger, #c0392b); border-color: currentColor; }
  button:disabled { opacity: .6; cursor: default; }
  .wrap { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; align-items: start; }
  .wrap.open { grid-template-columns: minmax(0, 1fr) minmax(280px, 380px); }
  table { width: 100%; border-collapse: collapse; background: var(--mk-surface); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--mk-muted);
    padding: 10px 12px; border-bottom: 1px solid var(--mk-line); }
  td { padding: 10px 12px; border-top: 1px solid var(--mk-line); vertical-align: middle; }
  tr.row { cursor: pointer; }
  tr.row:hover td, tr.row[aria-selected=true] td { background: var(--mk-bg); }
  .t { display: flex; flex-direction: column; gap: 2px; }
  .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--mk-line); white-space: nowrap; }
  aside[hidden], label[hidden] { display: none; }
  aside { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 16px;
    display: flex; flex-direction: column; gap: 10px; align-self: start; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; }
  dt { color: var(--mk-muted); font-size: 13px; }
  dd { margin: 0; overflow-wrap: anywhere; }
  .actions { display: flex; flex-wrap: wrap; gap: 6px; }
  form { display: flex; flex-direction: column; gap: 10px; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--mk-muted); }
  select, input, textarea { font: inherit; color: var(--mk-fg); background: var(--mk-bg); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); padding: 8px 10px; }
  dialog { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); background: var(--mk-surface);
    color: var(--mk-fg); padding: 20px; width: min(520px, calc(100vw - 32px)); }
  dialog::backdrop { background: rgb(0 0 0 / 40%); }
  .row-end { display: flex; gap: 8px; justify-content: flex-end; }
  [role=alert]:empty, .note:empty { display: none; }
  [role=alert] { color: var(--mk-danger, #c0392b); margin: 0; }
`;

/** Defines the element <catalog-uploads>, which the catalog places under its datasets. */
export function defineUploads(context) {
  if (customElements.get('catalog-uploads')) {
    return;
  }
  const language = () => (context.locale ?? 'en').split('-')[0];
  const t = () => TEXTS[language()] ?? TEXTS.en;
  const bytes = (n) => {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = n;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    const digits = unit === 0 || value >= 10 ? 0 : 1;
    return `${new Intl.NumberFormat(context.locale, { maximumFractionDigits: digits }).format(value)} ${units[unit]}`;
  };
  const allows = (data, level) => LEVELS.indexOf(data.level) >= LEVELS.indexOf(level);

  /**
   * The message of a refused call, in the language of the person. A body too large is refused by the
   * catalog with maxFile, or before it by the gateway or the kernel, then with the limit the page knows.
   */
  async function problem(response, maxFile) {
    const texts = t();
    let body = {};
    try {
      body = await response.json();
    } catch {
      // not JSON
    }
    if (response.status === 413) {
      const max = body.maxFile ?? maxFile;
      return max ? texts.tooLarge(bytes(max)) : texts.tooLarge('?');
    }
    if (response.status === 403 && body.quota) {
      return texts.quota;
    }
    if (response.status === 403) {
      return texts.forbidden;
    }
    if (response.status === 400) {
      return texts.refused(body.detail ?? '');
    }
    return texts.failed(response.status);
  }

  class Uploads extends HTMLElement {
    connectedCallback() {
      this.items = [];
      this.space = undefined;
      this.chosen = undefined;
      this.editing = false;
      const texts = t();
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `
        <style>${STYLE}</style>
        <section aria-labelledby="uploads-title">
          <div class="head">
            <div><h2 id="uploads-title">${texts.title}</h2><p class="muted small">${texts.subtitle}</p></div>
            <span class="muted small space"></span>
            <button type="button" class="primary open-upload" hidden>${texts.upload}</button>
          </div>
          <p class="muted note" aria-live="polite"></p>
          <div class="wrap"><table><thead><tr>${texts.columns.map((c) => `<th>${c}</th>`).join('')}</tr></thead>
            <tbody></tbody></table><aside hidden></aside></div>
        </section>
        <dialog aria-labelledby="upload-title">
          <form method="dialog">
            <h3 id="upload-title">${texts.upload}</h3>
            <label>${texts.fileLabel}<input type="file" name="file" required /></label>
            <p class="muted small formats"></p>
            <p class="muted small max-file"></p>
            <label>${texts.titleLabel}<input name="title" maxlength="200" /></label>
            <label>${texts.descriptionLabel}<textarea name="description" rows="3" maxlength="2000"></textarea></label>
            <label>${texts.crsLabel}<input name="crs" maxlength="40" placeholder="${texts.crsPlaceholder}" /></label>
            <p class="muted small space"></p>
            <p role="alert"></p>
            <div class="row-end"><button type="button" class="cancel">${texts.cancel}</button>
              <button type="submit" class="primary">${texts.send}</button></div>
          </form>
        </dialog>`;
      this.root = root;
      root.querySelector('.open-upload').addEventListener('click', () => this.openUpload());
      const dialog = root.querySelector('dialog');
      dialog.querySelector('.cancel').addEventListener('click', () => dialog.close());
      dialog.querySelector('input[type=file]').addEventListener('change', (event) => {
        const file = event.target.files?.[0];
        const title = dialog.querySelector('input[name=title]');
        const alert = dialog.querySelector('[role=alert]');
        alert.textContent = file && this.tooLarge(file) ? t().tooLarge(bytes(this.space.maxFile)) : '';
        if (file && !title.value) {
          title.value = file.name.replace(/\.[^.]+$/, '').replace(/[_\-.]+/g, ' ').trim().replace(/^./, (c) => c.toUpperCase());
        }
      });
      dialog.querySelector('form').addEventListener('submit', (event) => {
        event.preventDefault();
        void this.send(dialog);
      });
      // A publication changed the state of a dataset (GP-114).
      this.onPublished = () => void this.load();
      window.addEventListener(CHANGED, this.onPublished);
      void this.load(new URLSearchParams(location.search).get('dataset'));
    }

    disconnectedCallback() {
      window.removeEventListener(CHANGED, this.onPublished);
    }

    async load(open) {
      const [list, space] = await Promise.all([context.fetch(API), context.fetch(`${API}/space`)]);
      if (!list.ok) {
        this.say(t().error(list.status));
        return;
      }
      this.items = await list.json();
      this.space = space.ok ? await space.json() : undefined;
      this.poll();
      if (open) {
        this.chosen = this.items.find((d) => d.id === open);
      } else if (this.chosen) {
        this.chosen = this.items.find((d) => d.id === this.chosen.id);
      }
      this.render();
    }

    /** While a dataset is loading by the loader of the catalog, asks again every few seconds. */
    poll() {
      clearTimeout(this.polling);
      if (this.isConnected && this.items.some((d) => d.state === 'loading')) {
        this.polling = setTimeout(() => void this.load(), 2000);
      }
    }

    disconnectedCallback() {
      clearTimeout(this.polling);
    }

    stateText(d, texts) {
      return d.state === 'loading' && d.progress != null ? texts.loadingProgress(d.progress) : (texts.states[d.state] ?? d.state);
    }

    say(text) {
      this.root.querySelector('.note').textContent = text;
    }

    spaceText() {
      return this.space ? t().space(bytes(this.space.used), this.space.limit == null ? null : bytes(this.space.limit)) : '';
    }

    render() {
      const texts = t();
      this.root.querySelector('.open-upload').hidden = !this.space?.canUpload;
      this.root.querySelectorAll('.space').forEach((el) => (el.textContent = this.spaceText()));
      if (this.space) {
        this.root.querySelector('.formats').textContent = texts.accepted(this.space.formats);
        this.root.querySelector('.max-file').textContent = this.space.maxFile ? texts.maxFile(bytes(this.space.maxFile)) : '';
      }
      this.root.querySelector('tbody').replaceChildren(...this.items.map((d) => this.row(d, texts)));
      this.say(this.published ?? (this.items.length === 0 ? texts.none : texts.count(this.items.length)));
      this.published = undefined;
      this.renderDetail();
    }

    row(d, texts) {
      const row = document.createElement('tr');
      row.className = 'row';
      row.tabIndex = 0;
      row.setAttribute('aria-selected', String(d.id === this.chosen?.id));
      row.innerHTML = `<td><div class="t"><b></b><span class="mono muted"></span></div></td>
        <td></td><td class="muted"></td><td><span class="pill"></span></td>`;
      row.querySelector('b').textContent = d.title;
      row.querySelector('.mono').textContent = d.technicalName + (d.sample ? ` · ${texts.sample}` : '');
      row.children[1].textContent = d.formatTitle;
      row.children[2].textContent = bytes(d.size);
      row.querySelector('.pill').textContent = this.stateText(d, texts);
      const open = () => {
        this.chosen = d;
        this.editing = false;
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
      const texts = t();
      const aside = this.root.querySelector('aside');
      const d = this.chosen;
      aside.hidden = !d;
      this.root.querySelector('.wrap').classList.toggle('open', Boolean(d));
      if (!d) {
        return;
      }
      aside.setAttribute('aria-label', d.title);
      aside.innerHTML = `<div class="row-end"><button type="button" class="close">${texts.close}</button></div>
        <h3></h3><p class="muted desc"></p><dl></dl><div class="actions"></div><p role="alert"></p>`;
      aside.querySelector('h3').textContent = d.title;
      aside.querySelector('.desc').textContent = d.description;
      aside.querySelector('dl').replaceChildren(
        ...[
          [texts.technicalName, d.technicalName],
          [texts.file, d.fileName],
          [texts.format, d.formatTitle],
          [texts.size, bytes(d.size)],
          [texts.version, String(d.version)],
          [texts.state, this.stateText(d, texts)],
          ...(d.stateCode ? [[texts.reason, texts.codes[d.stateCode] ?? d.stateCode, d.stateReason]] : []),
          ...this.loadedRows(d, texts),
          [texts.level, texts.levels[d.level] ?? d.level],
        ].flatMap(([label, value, detail]) => {
          const dt = document.createElement('dt');
          dt.textContent = label;
          const dd = document.createElement('dd');
          dd.textContent = value;
          if (label === texts.technicalName || label === texts.table) {
            dd.className = 'mono';
          }
          if (detail) {
            const small = document.createElement('span');
            small.className = 'muted small reason-detail';
            small.textContent = detail;
            dd.append(document.createElement('br'), small);
          }
          return [dt, dd];
        }),
      );
      aside.querySelector('.close').addEventListener('click', () => {
        this.chosen = undefined;
        this.render();
      });
      const actions = aside.querySelector('.actions');
      const action = (label, run, extra = '') => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        if (extra) {
          button.className = extra;
        }
        button.addEventListener('click', run);
        actions.append(button);
        return button;
      };
      if (allows(d, 'download')) {
        action(texts.download, () => void this.download(d));
      }
      if (allows(d, 'edit')) {
        action(texts.rename, () => {
          this.editing = true;
          this.renderRename(aside, d);
        });
      }
      if (allows(d, 'edit-data')) {
        const input = document.createElement('input');
        input.type = 'file';
        input.hidden = true;
        input.setAttribute('aria-label', texts.replace);
        input.addEventListener('change', () => {
          const file = input.files?.[0];
          if (file) {
            void this.replace(d, file);
          }
        });
        actions.append(input);
        action(texts.replace, () => input.click());
        if (d.state === 'failed' || d.state === 'uploaded') {
          this.renderReload(aside, d);
        }
      }
      // GP-114: a loaded vector is published as a GeoServer service, by who manages it and may publish.
      // GP-309: a loaded raster too, as a coverage of its Cloud Optimized GeoTIFF.
      if (allows(d, 'manage') && this.space?.canPublish && (d.loaded?.table || d.loaded?.geometryType === 'Raster')
          && (d.state === 'loaded' || d.state === 'published')) {
        action(texts.publish, () =>
          openPublishDialog(context, this.root, d, {
            onDone: (message) => {
              this.published = message; // kept when the list is drawn again after the change
              this.say(message);
            },
          }), 'primary');
      }
      if (allows(d, 'manage')) {
        action(texts.share, () =>
          context.events.publish('shell.share', { extension: context.extension.id, type: 'dataset', id: d.id }),
        );
        action(texts.remove, () => void this.remove(d), 'danger');
      }
      if (this.editing) {
        this.renderRename(aside, d);
      }
    }

    /** What the loading produced: reference system, geometry, features, extent and where it is. */
    loadedRows(d, texts) {
      const l = d.loaded;
      if (!l) {
        return [];
      }
      const number = (n, digits) => new Intl.NumberFormat(context.locale, { maximumFractionDigits: digits }).format(n);
      const raster = l.geometryType === 'Raster';
      const rows = [
        [texts.crs, `${l.crs} · ${texts.sources[l.crsSource] ?? l.crsSource}`],
        [texts.geometry, raster ? texts.raster : l.z ? texts.withZ(l.geometryType) : l.geometryType],
        [raster ? texts.pixels : texts.features, number(l.features, 0)],
        [texts.extent, l.extent ? texts.box(...l.extent.map((v) => number(v, 4))) : texts.noExtent],
      ];
      if (d.layer) {
        rows.push([texts.layer, d.layer]);
      }
      if (l.table) {
        rows.push([texts.table, l.table]);
      }
      return rows;
    }

    /** Loads the file again, with the EPSG code of a file that states none. */
    renderReload(aside, d) {
      const texts = t();
      const form = document.createElement('form');
      form.className = 'reload';
      form.innerHTML = `<label class="layer" hidden>${texts.layerLabel}<select name="layer"></select></label>
        <label>${texts.crsLabel}<input name="crs" maxlength="40" placeholder="${texts.crsPlaceholder}" /></label>
        <div class="row-end"><button type="submit">${texts.reload}</button></div>`;
      if (d.layers?.length > 1) {
        form.querySelector('.layer').hidden = false;
        form.querySelector('select').replaceChildren(
          ...d.layers.map((l) => {
            const option = document.createElement('option');
            option.value = l.name;
            option.textContent = texts.layerOption(l);
            option.selected = l.name === d.layer;
            return option;
          }),
        );
      }
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        const layer = d.layers?.length > 1 ? form.querySelector('select').value : '';
        void this.reload(d, form.querySelector('input[name=crs]').value.trim(), layer);
      });
      aside.querySelector('.actions').after(form);
    }

    async reload(d, crs, layer) {
      const params = new URLSearchParams({ ...(crs ? { crs } : {}), ...(layer ? { layer } : {}) });
      const query = params.size ? `?${params}` : '';
      const response = await context.fetch(`${API}/${d.id}/load${query}`, { method: 'POST' });
      await this.after(response, (changed) => this.outcome(changed, t().reloaded));
    }

    /**
     * What to say of a dataset just stored: done, or not loaded with the reason of the file. A dataset
     * waiting for the geodata database was stored all the same: its detail says why it waits.
     */
    outcome(d, done) {
      const texts = t();
      if (d.state === 'loading') {
        return texts.inProgress(d.title);
      }
      if (d.state === 'failed') {
        return texts.notLoaded(d.title, texts.codes[d.stateCode] ?? d.stateReason ?? '');
      }
      return done(d.title);
    }

    renderRename(aside, d) {
      const texts = t();
      aside.querySelector('form.rename')?.remove();
      const form = document.createElement('form');
      form.className = 'rename';
      form.innerHTML = `<label>${texts.titleLabel}<input name="title" maxlength="200" required /></label>
        <label>${texts.descriptionLabel}<textarea name="description" rows="3" maxlength="2000"></textarea></label>
        <div class="row-end"><button type="button" class="cancel">${texts.cancel}</button>
          <button type="submit" class="primary">${texts.save}</button></div>`;
      form.querySelector('input').value = d.title;
      form.querySelector('textarea').value = d.description;
      form.querySelector('.cancel').addEventListener('click', () => {
        this.editing = false;
        form.remove();
      });
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        void this.rename(d, form.querySelector('input').value, form.querySelector('textarea').value);
      });
      aside.querySelector('.actions').after(form);
      form.querySelector('input').focus();
    }

    async openUpload() {
      const dialog = this.root.querySelector('dialog');
      dialog.querySelector('form').reset();
      dialog.querySelector('[role=alert]').textContent = '';
      dialog.showModal();
    }

    async send(dialog) {
      const texts = t();
      const file = dialog.querySelector('input[type=file]').files?.[0];
      if (!file) {
        return;
      }
      const alert = dialog.querySelector('[role=alert]');
      // Refused here, before sending a body the gateway or the kernel would cut off (issue #40).
      if (this.tooLarge(file)) {
        alert.textContent = texts.tooLarge(bytes(this.space.maxFile));
        return;
      }
      const submit = dialog.querySelector('button[type=submit]');
      submit.disabled = true;
      alert.textContent = '';
      const query = new URLSearchParams({ name: file.name });
      const title = dialog.querySelector('input[name=title]').value.trim();
      const description = dialog.querySelector('textarea').value.trim();
      if (title) {
        query.set('title', title);
      }
      if (description) {
        query.set('description', description);
      }
      const crs = dialog.querySelector('input[name=crs]').value.trim();
      if (crs) {
        query.set('crs', crs);
      }
      try {
        const response = await context.fetch(`${API}?${query}`, {
          method: 'POST',
          headers: { 'Content-Type': file.type || 'application/octet-stream' },
          body: file,
        });
        if (!response.ok) {
          alert.textContent = await problem(response, this.space?.maxFile);
          return;
        }
        const created = await response.json();
        dialog.close();
        this.chosen = created;
        await this.load();
        this.say(this.outcome(created, texts.uploaded));
      } finally {
        submit.disabled = false;
      }
    }

    async rename(d, title, description) {
      const response = await context.fetch(`${API}/${d.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, description }),
      });
      await this.after(response, (changed) => t().renamed(changed.title));
    }

    /** Whether the file is larger than the installation accepts. */
    tooLarge(file) {
      return Boolean(this.space?.maxFile) && file.size > this.space.maxFile;
    }

    async replace(d, file) {
      if (this.tooLarge(file)) {
        const alert = this.root.querySelector('aside [role=alert]');
        if (alert) {
          alert.textContent = t().tooLarge(bytes(this.space.maxFile));
        }
        return;
      }
      const response = await context.fetch(`${API}/${d.id}/content?${new URLSearchParams({ name: file.name })}`, {
        method: 'PUT',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      });
      await this.after(response, (changed) => this.outcome(changed, t().replaced));
    }

    async remove(d) {
      if (!confirm(t().confirmRemove(d.title))) {
        return;
      }
      const response = await context.fetch(`${API}/${d.id}`, { method: 'DELETE' });
      if (response.ok) {
        this.chosen = undefined;
      }
      await this.after(response, () => t().removed(d.title));
    }

    async download(d) {
      const response = await context.fetch(`${API}/${d.id}/content`);
      if (!response.ok) {
        this.root.querySelector('aside [role=alert]').textContent = await problem(response);
        return;
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = d.fileName;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    }

    /** Reloads after a change, and says what happened or why it was refused. */
    async after(response, message) {
      if (!response.ok) {
        const alert = this.root.querySelector('aside [role=alert]');
        if (alert) {
          alert.textContent = await problem(response, this.space?.maxFile);
        }
        return;
      }
      const changed = response.status === 204 ? undefined : await response.json();
      this.editing = false;
      await this.load();
      this.say(message(changed));
    }
  }

  customElements.define('catalog-uploads', Uploads);
}
