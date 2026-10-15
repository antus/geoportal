// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * The editing of the data of published layers (GP-036), in the details of a publication: who manages the
 * dataset of the layer assigns it to surveyors, sees whether each holds the permission to edit the data of
 * layers and removes an assignment; a surveyor sees that the layer is assigned to them and whether they may
 * edit it now. The texts follow the language of the person.
 */

const API = '/api/v1/ext/catalog-geodata/editing';

const TEXTS = {
  it: {
    title: 'Rilevatori',
    hint: 'Solo chi è assegnato al livello e ha il permesso «Modificare i dati dei livelli assegnati» (profilo Rilevatore) ne modifica i dati, nel catalogo e con il WFS-T di GeoServer. Togliere un’assegnazione vale dalla richiesta successiva.',
    none: 'Nessun rilevatore assegnato: nessuno modifica i dati di questo livello.',
    assign: 'Assegna',
    choose: 'Persona da assegnare',
    remove: 'Togli',
    removeLabel: (n) => `Togli l’assegnazione a ${n}`,
    assigned: (n) => `Il livello è assegnato a ${n}.`,
    removed: (n) => `${n} non è più assegnato al livello.`,
    holds: { true: 'Ha il permesso', false: 'Senza il permesso: non può modificare', null: 'Permesso da verificare al prossimo accesso' },
    level: (l) => (l ? `Livello sul dato: ${LEVELS_IT[l] ?? l}` : 'Nessun accesso al dato: vede i dati solo se condiviso'),
    mineEditable: 'Questo livello è assegnato a te: puoi modificarne i dati.',
    mineBlocked: 'Questo livello è assegnato a te, ma il tuo profilo non ha il permesso «Modificare i dati dei livelli assegnati».',
    mineWithin: 'Questo livello è assegnato a te e le regole sui dati ti limitano: puoi modificarne i dati con il WFS-T del servizio del livello (per esempio da QGIS), solo dentro la tua area e il tuo filtro.',
    mineRuled: 'Questo livello è assegnato a te, ma le regole sui dati ti nascondono delle colonne o non ti lasciano vedere nulla: non puoi modificarne i dati.',
    badge: 'Assegnato a te',
    noPeople: 'L’elenco delle persone non si può leggere: manca la credenziale «published-views».',
    failed: (s) => `Non è stato possibile completare l’operazione (${s}).`,
  },
  en: {
    title: 'Surveyors',
    hint: 'Only people assigned to the layer who hold the permission “Edit the data of assigned layers” (surveyor profile) edit its data, in the catalog and through WFS-T of GeoServer. Removing an assignment applies from the next request.',
    none: 'No surveyor assigned: nobody edits the data of this layer.',
    assign: 'Assign',
    choose: 'Person to assign',
    remove: 'Remove',
    removeLabel: (n) => `Remove the assignment of ${n}`,
    assigned: (n) => `The layer is assigned to ${n}.`,
    removed: (n) => `${n} is no longer assigned to the layer.`,
    holds: { true: 'Holds the permission', false: 'Without the permission: cannot edit', null: 'Permission checked at their next request' },
    level: (l) => (l ? `Level on the dataset: ${l}` : 'No access to the dataset: sees the data only if shared'),
    mineEditable: 'This layer is assigned to you: you can edit its data.',
    mineBlocked: 'This layer is assigned to you, but your profile lacks the permission “Edit the data of assigned layers”.',
    mineWithin: 'This layer is assigned to you and data rules limit you: you can edit its data through WFS-T of the service of the layer (from QGIS, for example), only within your area and filter.',
    mineRuled: 'This layer is assigned to you, but data rules hide columns from you or leave nothing visible: you cannot edit its data.',
    badge: 'Assigned to you',
    noPeople: 'The people cannot be read: the credential “published-views” is missing.',
    failed: (s) => `The operation could not be completed (${s}).`,
  },
};

const LEVELS_IT = { view: 'Vedere', read: 'Leggere e scaricare', edit: 'Modificare', 'edit-data': 'Modificare i dati', manage: 'Gestire' };

const texts = (context) => TEXTS[(context.locale ?? 'en').split('-')[0]] ?? TEXTS.en;

/** What the person edits: whether they hold the permission and their layers; the catalog confirms the permission. */
export async function myLayers(context) {
  const response = await context.fetch(API);
  return response.ok ? await response.json() : { holds: false, layers: [] };
}

/** The badge of a row of the list for a layer assigned to the person, or null. */
export function assignedBadge(context, mine, layer) {
  if (!mine?.layers?.some((l) => l.layer === layer)) {
    return null;
  }
  const badge = document.createElement('span');
  badge.className = 'pill assigned';
  badge.textContent = texts(context).badge;
  return badge;
}

/**
 * The section of the details of a publication: the note for a surveyor of the layer, and for who manages its
 * dataset the surveyors with their assignment.
 *
 * @param say tells the page what happened
 */
export function editingSection(context, publication, mine, say) {
  const tx = texts(context);
  const box = document.createElement('div');
  box.className = 'editing';
  const assignedToMe = mine?.layers?.find((l) => l.layer === publication.name);
  if (assignedToMe) {
    const note = document.createElement('p');
    note.className = 'mine small';
    note.textContent = !mine.holds
      ? tx.mineBlocked
      : assignedToMe.dataRules === 'data-rules'
        ? tx.mineRuled
        : assignedToMe.dataRules === 'data-rules-service'
          ? tx.mineWithin
          : tx.mineEditable;
    box.append(note);
  }
  if (publication.level !== 'manage') {
    return box;
  }
  const head = document.createElement('h4');
  head.id = `surveyors-${publication.name}`;
  head.textContent = tx.title;
  const hint = document.createElement('p');
  hint.className = 'muted small';
  hint.textContent = tx.hint;
  const list = document.createElement('ul');
  list.className = 'items surveyors';
  list.setAttribute('aria-labelledby', head.id);
  const form = document.createElement('div');
  form.className = 'actions assign';
  form.innerHTML = '<label class="small"><span class="muted"></span> <select name="person"></select></label> <button type="button" class="primary"></button>';
  form.querySelector('span').textContent = tx.choose;
  form.querySelector('button').textContent = tx.assign;
  const failure = document.createElement('p');
  failure.className = 'small failure';
  failure.setAttribute('role', 'alert');
  box.append(head, hint, list, form, failure);

  const base = `${API}/layers/${encodeURIComponent(publication.name)}/assignments`;
  let people = [];
  const draw = (surveyors) => {
    if (surveyors.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = tx.none;
      list.replaceChildren(li);
    } else {
      list.replaceChildren(
        ...surveyors.map((s) => {
          const li = document.createElement('li');
          li.dataset.account = s.account;
          const name = s.displayName ? `${s.displayName} (${s.username})` : s.username;
          li.innerHTML = '<b></b> <span class="pill holds"></span> <span class="small muted level"></span> <button type="button" class="remove"></button>';
          li.querySelector('b').textContent = name;
          const holds = li.querySelector('.holds');
          holds.textContent = tx.holds[String(s.holds)];
          holds.classList.add(s.holds === true ? 'ok' : s.holds === false ? 'failed' : 'unknown');
          li.querySelector('.level').textContent = tx.level(s.level);
          const remove = li.querySelector('.remove');
          remove.textContent = tx.remove;
          remove.setAttribute('aria-label', tx.removeLabel(name));
          remove.addEventListener('click', async () => {
            remove.disabled = true;
            const r = await context.fetch(`${base}/${s.account}`, { method: 'DELETE' });
            if (!r.ok) {
              failure.textContent = tx.failed(r.status);
              remove.disabled = false;
              return;
            }
            say(tx.removed(name));
            await refresh();
          });
          return li;
        }),
      );
    }
    const select = form.querySelector('select');
    const taken = new Set(surveyors.map((s) => s.account));
    const free = people.filter((p) => !taken.has(p.account));
    select.replaceChildren(
      ...free.map((p) => {
        const o = document.createElement('option');
        o.value = p.account;
        o.textContent = p.displayName ? `${p.displayName} (${p.username})` : p.username;
        return o;
      }),
    );
    form.querySelector('button').disabled = free.length === 0;
  };
  const refresh = async () => {
    failure.textContent = '';
    const r = await context.fetch(base);
    if (!r.ok) {
      failure.textContent = tx.failed(r.status);
      return;
    }
    draw(await r.json());
  };
  form.querySelector('button').addEventListener('click', async () => {
    const select = form.querySelector('select');
    const account = select.value;
    if (!account) {
      return;
    }
    const label = select.selectedOptions[0]?.textContent ?? account;
    const button = form.querySelector('button');
    button.disabled = true;
    const r = await context.fetch(`${base}/${account}`, { method: 'PUT' });
    button.disabled = false;
    if (!r.ok) {
      failure.textContent = tx.failed(r.status);
      return;
    }
    say(tx.assigned(label));
    draw(await r.json());
  });
  void (async () => {
    const r = await context.fetch(`${API}/people`);
    if (r.ok) {
      people = await r.json();
    }
    await refresh();
    if (!r.ok) {
      failure.textContent = tx.noPeople;
    }
  })();
  return box;
}
