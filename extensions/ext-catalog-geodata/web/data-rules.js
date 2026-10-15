// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * The data rules of a published layer (GP-308): what a person or a team sees of its data, by area, rows
 * and columns. Shown in the details of a publication to those who manage its dataset. An area is drawn on
 * the map of the layer, in the reference system of its data, or written as WKT; the row filter is CQL,
 * checked by GeoServer when the rule is saved. Rules that apply to the same person intersect. The texts
 * follow the language of the person.
 */

const API = '/api/v1/ext/catalog-geodata/data-rules';

const TEXTS = {
  it: {
    title: 'Regole sui dati',
    hint: 'Limitano quello che una persona o un gruppo vede di questo livello: un’area, le righe che rispettano un filtro, le colonne visibili. Se più regole valgono per la stessa persona, vede solo ciò che tutte permettono.',
    none: 'Nessuna regola: chi può vedere il dato lo vede tutto.',
    add: 'Nuova regola',
    edit: 'Modifica',
    remove: 'Elimina',
    confirmRemove: (s) => `Eliminare la regola per ${s}?`,
    removed: 'La regola è stata eliminata e le regole di accesso riscritte.',
    saved: 'La regola è stata salvata e le regole di accesso riscritte.',
    person: 'Persona',
    team: 'Gruppo',
    unknown: 'sconosciuto',
    subject: 'Per chi',
    kind: 'Tipo di soggetto',
    email: 'Indirizzo e-mail della persona',
    chooseTeam: 'Gruppo',
    description: 'Descrizione (facoltativa)',
    area: 'Area',
    areaHint: (srs) => `Disegna l’area sulla mappa (un clic per vertice) oppure scrivila in WKT, nel sistema di riferimento del dato (${srs}).`,
    clearDrawing: 'Cancella il disegno',
    mapAlt: 'Mappa del livello su cui disegnare l’area',
    wkt: 'Area in WKT',
    cql: 'Filtro sulle righe (CQL)',
    cqlHint: 'Per esempio: stato = \'attivo\' AND comune = \'Valverde\'. GeoServer lo verifica al salvataggio.',
    hidden: 'Colonne nascoste',
    summaryArea: 'area limitata',
    summaryRows: (c) => `righe: ${c}`,
    summaryHidden: (c) => `colonne nascoste: ${c}`,
    newTitle: 'Nuova regola sui dati',
    editTitle: 'Modifica la regola sui dati',
    save: 'Salva',
    cancel: 'Annulla',
    geofence: {
      written: 'GeoFence ha le nuove regole.',
      unchanged: 'GeoFence ha già queste regole.',
      mismatch: 'Attenzione: GeoFence ha regole diverse da quelle generate, vedi la riconciliazione.',
      failed: 'Attenzione: le regole non si sono potute scrivere in GeoServer, vedi la riconciliazione.',
    },
    errors: {
      invalid: 'La regola non è valida.',
      'invalid-area': 'L’area non è valida.',
      'invalid-filter': 'Il filtro sulle righe non è valido.',
      'credential-missing': 'Manca una credenziale del catalogo (geoserver, geodata o published-views): chiedi a un amministratore.',
    },
    failed: (s) => `Non è stato possibile completare l’operazione (${s}).`,
    loadFailed: (s) => `Le regole sui dati non si possono leggere (${s}).`,
  },
  en: {
    title: 'Data rules',
    hint: 'They limit what a person or a team sees of this layer: an area, the rows that match a filter, the visible columns. When several rules apply to the same person, they see only what all of them allow.',
    none: 'No rule: whoever may see the data sees all of it.',
    add: 'New rule',
    edit: 'Edit',
    remove: 'Delete',
    confirmRemove: (s) => `Delete the rule for ${s}?`,
    removed: 'The rule was deleted and the access rules written again.',
    saved: 'The rule was saved and the access rules written again.',
    person: 'Person',
    team: 'Team',
    unknown: 'unknown',
    subject: 'For whom',
    kind: 'Kind of subject',
    email: 'E-mail address of the person',
    chooseTeam: 'Team',
    description: 'Description (optional)',
    area: 'Area',
    areaHint: (srs) => `Draw the area on the map (one click per vertex) or write it as WKT, in the reference system of the data (${srs}).`,
    clearDrawing: 'Clear the drawing',
    mapAlt: 'Map of the layer to draw the area on',
    wkt: 'Area as WKT',
    cql: 'Row filter (CQL)',
    cqlHint: 'For example: stato = \'attivo\' AND comune = \'Valverde\'. GeoServer checks it when the rule is saved.',
    hidden: 'Hidden columns',
    summaryArea: 'limited area',
    summaryRows: (c) => `rows: ${c}`,
    summaryHidden: (c) => `hidden columns: ${c}`,
    newTitle: 'New data rule',
    editTitle: 'Edit the data rule',
    save: 'Save',
    cancel: 'Cancel',
    geofence: {
      written: 'GeoFence holds the new rules.',
      unchanged: 'GeoFence already holds these rules.',
      mismatch: 'Warning: GeoFence holds other rules than those generated, see the reconciliation.',
      failed: 'Warning: the rules could not be written into GeoServer, see the reconciliation.',
    },
    errors: {
      invalid: 'The rule is not valid.',
      'invalid-area': 'The area is not valid.',
      'invalid-filter': 'The row filter is not valid.',
      'credential-missing': 'A credential of the catalog is missing (geoserver, geodata or published-views): ask an administrator.',
    },
    failed: (s) => `The operation could not be completed (${s}).`,
    loadFailed: (s) => `The data rules cannot be read (${s}).`,
  },
};

const STYLE = `
  .data-rules { display: flex; flex-direction: column; gap: 8px; border-top: 1px solid var(--mk-line); padding-top: 10px; }
  .data-rules ul.rules { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  .data-rules ul.rules li { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between;
    border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 8px 10px; }
  .data-rules .who { font-weight: 600; }
  dialog.data-rule textarea { font: inherit; font-size: 12px; color: var(--mk-fg); background: var(--mk-bg);
    border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 7px 9px; min-height: 60px; }
  dialog.data-rule .draw { position: relative; width: 100%; max-width: 480px; aspect-ratio: 1 / 1; border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; background: var(--mk-bg); cursor: crosshair; }
  dialog.data-rule .draw img, dialog.data-rule .draw svg { position: absolute; inset: 0; width: 100%; height: 100%; }
  dialog.data-rule .columns { display: flex; flex-wrap: wrap; gap: 6px 14px; }
  dialog.data-rule .columns label { flex-direction: row; align-items: center; gap: 6px; color: var(--mk-fg); }
  dialog.data-rule fieldset { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 10px 12px; margin: 0;
    display: flex; flex-direction: column; gap: 8px; }
  dialog.data-rule legend { font-size: 13px; font-weight: 600; }
  dialog.data-rule .error { color: var(--mk-danger, #c0392b); }
`;

const language = (context) => (context.locale ?? 'en').split('-')[0];
const textsOf = (context) => TEXTS[language(context)] ?? TEXTS.en;

async function problemOf(response) {
  try {
    return await response.json();
  } catch {
    return {};
  }
}

/**
 * The section of the data rules of a publication, for the details of the publication.
 *
 * @param {object} context the context of the extension
 * @param {{name: string}} publication the published layer
 * @param {(message: string) => void} say where to say what happened
 */
export function dataRulesSection(context, publication, say) {
  const t = textsOf(context);
  const section = document.createElement('section');
  section.className = 'data-rules';
  section.setAttribute('aria-label', t.title);
  section.innerHTML = `<style>${STYLE}</style><h4></h4><p class="muted small hint"></p><p class="muted small status" role="status"></p>
    <ul class="rules"></ul><div class="actions"><button type="button" class="add"></button></div>`;
  section.querySelector('h4').textContent = t.title;
  section.querySelector('.hint').textContent = t.hint;
  section.querySelector('.add').textContent = t.add;
  let info;

  const subjectName = (r) => (r.subject.name ?? t.unknown);
  const summary = (r) => [r.area ? t.summaryArea : '', r.cql ? t.summaryRows(r.cql) : '',
    r.hidden?.length ? t.summaryHidden(r.hidden.join(', ')) : ''].filter(Boolean).join(' · ');

  const load = async () => {
    const response = await context.fetch(`${API}?${new URLSearchParams({ layer: publication.name })}`);
    const status = section.querySelector('.status');
    if (!response.ok) {
      const problem = await problemOf(response);
      status.textContent = t.errors[problem.code] ?? problem.detail ?? t.loadFailed(response.status);
      section.querySelector('.add').disabled = true;
      return;
    }
    info = await response.json();
    section.querySelector('.add').disabled = false;
    status.textContent = info.rules.length === 0 ? t.none : '';
    section.querySelector('ul.rules').replaceChildren(...info.rules.map((r) => {
      const li = document.createElement('li');
      li.innerHTML = '<div><div class="who"></div><div class="small muted what"></div><div class="small muted note"></div></div><div class="actions"></div>';
      li.querySelector('.who').textContent = `${r.subject.kind === 'team' ? t.team : t.person}: ${subjectName(r)}`;
      li.querySelector('.what').textContent = summary(r);
      li.querySelector('.note').textContent = r.description ?? '';
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.textContent = t.edit;
      edit.setAttribute('aria-label', `${t.edit} ${subjectName(r)}`);
      edit.addEventListener('click', () => openDialog(r));
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'danger';
      remove.textContent = t.remove;
      remove.setAttribute('aria-label', `${t.remove} ${subjectName(r)}`);
      remove.addEventListener('click', async () => {
        if (!confirm(t.confirmRemove(subjectName(r)))) {
          return;
        }
        const response = await context.fetch(`${API}/${r.id}`, { method: 'DELETE' });
        if (!response.ok) {
          say(t.failed(response.status));
          return;
        }
        const answer = await response.json();
        say(`${t.removed} ${t.geofence[answer.geofence?.outcome] ?? ''}`.trim());
        await load();
      });
      li.querySelector('.actions').append(edit, remove);
      return li;
    }));
  };

  const openDialog = (rule) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'data-rule';
    dialog.setAttribute('aria-label', rule ? t.editTitle : t.newTitle);
    const columns = info.columns.filter((c) => !c.geometry);
    dialog.innerHTML = `<style>${STYLE}</style><form method="dialog">
      <h3></h3>
      <fieldset class="who"><legend></legend>
        <label><span class="l-kind"></span><select name="kind"><option value="person"></option><option value="team"></option></select></label>
        <label class="email"><span class="l-email"></span><input name="email" type="email" autocomplete="off"></label>
        <label class="team"><span class="l-team"></span><select name="team"></select></label>
      </fieldset>
      <label><span class="l-description"></span><input name="description" maxlength="500"></label>
      <fieldset><legend class="l-area"></legend><p class="small muted area-hint"></p>
        <div class="draw"><img alt=""><svg viewBox="0 0 512 512" preserveAspectRatio="none"><polygon fill="rgba(26,143,255,.25)" stroke="#1a8fff" stroke-width="2"></polygon></svg></div>
        <div class="actions"><button type="button" class="clear"></button></div>
        <label><span class="l-wkt"></span><textarea name="area" spellcheck="false"></textarea></label>
      </fieldset>
      <label><span class="l-cql"></span><input name="cql" spellcheck="false"><span class="small muted cql-hint"></span></label>
      <fieldset><legend class="l-hidden"></legend><div class="columns"></div></fieldset>
      <p class="error" role="alert"></p>
      <div class="row-end"><button type="button" class="cancel"></button><button type="submit" class="primary confirm"></button></div>
    </form>`;
    const q = (s) => dialog.querySelector(s);
    q('h3').textContent = rule ? t.editTitle : t.newTitle;
    q('.who legend').textContent = t.subject;
    q('.l-kind').textContent = t.kind;
    q('option[value=person]').textContent = t.person;
    q('option[value=team]').textContent = t.team;
    q('.l-email').textContent = t.email;
    q('.l-team').textContent = t.chooseTeam;
    q('.l-description').textContent = t.description;
    q('.l-area').textContent = t.area;
    q('.area-hint').textContent = t.areaHint(info.srs);
    q('.clear').textContent = t.clearDrawing;
    q('.l-wkt').textContent = t.wkt;
    q('.l-cql').textContent = t.cql;
    q('.cql-hint').textContent = t.cqlHint;
    q('.l-hidden').textContent = t.hidden;
    q('.cancel').textContent = t.cancel;
    q('.confirm').textContent = t.save;
    q('select[name=team]').replaceChildren(...info.teams.map((team) => {
      const o = document.createElement('option');
      o.value = team.id;
      o.textContent = team.name;
      return o;
    }));
    q('.columns').replaceChildren(...columns.map((c) => {
      const label = document.createElement('label');
      label.innerHTML = '<input type="checkbox" name="hidden"><span class="mono"></span>';
      label.querySelector('input').value = c.name;
      label.querySelector('span').textContent = c.name;
      return label;
    }));
    const kind = q('select[name=kind]');
    const showKind = () => {
      q('label.email').hidden = kind.value !== 'person';
      q('label.team').hidden = kind.value !== 'team';
    };
    kind.addEventListener('change', showKind);
    if (rule) {
      kind.value = rule.subject.kind;
      if (rule.subject.kind === 'team') {
        q('select[name=team]').value = rule.subject.id;
      } else {
        q('input[name=email]').value = rule.subject.name ?? '';
      }
      q('input[name=description]').value = rule.description ?? '';
      q('textarea[name=area]').value = rule.area ?? '';
      q('input[name=cql]').value = rule.cql ?? '';
      for (const box of dialog.querySelectorAll('input[name=hidden]')) {
        box.checked = (rule.hidden ?? []).includes(box.value);
      }
    }
    showKind();

    // Drawing: the map of the layer in the reference system of its data, a vertex per click.
    const bbox = info.bbox;
    const draw = q('.draw');
    const polygon = q('polygon');
    const points = [];
    if (bbox) {
      const [minx, miny, maxx, maxy] = bbox;
      const pad = Math.max(maxx - minx, maxy - miny) * 0.05 || 1;
      const size = Math.max(maxx - minx, maxy - miny) + 2 * pad;
      const cx = (minx + maxx) / 2;
      const cy = (miny + maxy) / 2;
      const view = [cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2];
      const img = q('img');
      img.alt = t.mapAlt;
      img.src = `/api/v1/ext/catalog-geodata/publications/${encodeURIComponent(publication.name)}/ogc/wms?${new URLSearchParams({
        service: 'WMS', version: '1.1.1', request: 'GetMap', layers: publication.name, styles: '', srs: info.srs,
        bbox: view.join(','), width: '512', height: '512', format: 'image/png', transparent: 'true',
      })}`;
      const toWkt = () => {
        if (points.length < 3) {
          return;
        }
        const ring = [...points, points[0]].map(([x, y]) => `${x} ${y}`).join(', ');
        q('textarea[name=area]').value = `POLYGON((${ring}))`;
      };
      draw.addEventListener('click', (event) => {
        const box = draw.getBoundingClientRect();
        const fx = (event.clientX - box.left) / box.width;
        const fy = (event.clientY - box.top) / box.height;
        const round = (v) => Math.round(v * 1e6) / 1e6;
        points.push([round(view[0] + fx * (view[2] - view[0])), round(view[3] - fy * (view[3] - view[1]))]);
        polygon.setAttribute('points', points.map(([x, y]) =>
          `${((x - view[0]) / (view[2] - view[0])) * 512},${((view[3] - y) / (view[3] - view[1])) * 512}`).join(' '));
        toWkt();
      });
      q('.clear').addEventListener('click', () => {
        points.length = 0;
        polygon.setAttribute('points', '');
        q('textarea[name=area]').value = '';
      });
    } else {
      draw.hidden = true;
      q('.clear').hidden = true;
    }

    q('.cancel').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => dialog.remove());
    const submit = async () => {
      const error = q('.error');
      error.textContent = '';
      const body = {
        layer: publication.name,
        subject: kind.value === 'team'
          ? { kind: 'team', id: q('select[name=team]').value }
          : { kind: 'person', email: q('input[name=email]').value.trim() },
        description: q('input[name=description]').value,
        area: q('textarea[name=area]').value.trim() || null,
        cql: q('input[name=cql]').value.trim() || null,
        hidden: [...dialog.querySelectorAll('input[name=hidden]:checked')].map((b) => b.value),
      };
      q('.confirm').disabled = true;
      try {
        const response = await context.fetch(rule ? `${API}/${rule.id}` : API, {
          method: rule ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        });
        if (!response.ok) {
          const problem = await problemOf(response);
          error.textContent = [t.errors[problem.code] ?? t.failed(response.status), problem.detail].filter(Boolean).join(' ');
          return;
        }
        const answer = await response.json();
        dialog.close();
        say(`${t.saved} ${t.geofence[answer.geofence?.outcome] ?? ''}`.trim());
        await load();
      } finally {
        q('.confirm').disabled = false;
      }
    };
    q('form').addEventListener('submit', (event) => {
      event.preventDefault();
      void submit();
    });
    section.getRootNode().append(dialog);
    dialog.showModal();
  };

  section.querySelector('.add').addEventListener('click', () => openDialog(null));
  void load();
  return section;
}
