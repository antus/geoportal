// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * Data rules on external services (GP-309), in the details of a service: a person sees which layers are
 * not available to them and why (a data rule the service cannot apply); who manages the service reads,
 * adds and removes the rules. An external server cannot be made to filter what it answers, so a rule
 * never filters: it makes the layer unavailable to its subject. The texts follow the language of the person.
 */

const API = '/api/v1/ext/catalog/service-rules';

const TEXTS = {
  it: {
    unavailableTitle: 'Non disponibili per te',
    unavailable: (layer) =>
      layer
        ? `Il livello ${layer} non è disponibile per te: una regola sui dati limita ciò che vedi, e questo servizio esterno non la può applicare.`
        : 'Questo servizio non è disponibile per te: una regola sui dati limita ciò che vedi, e il servizio esterno non la può applicare.',
    rules: 'Regole sui dati',
    rulesHint: 'Un servizio esterno non filtra i dati per Geoportal: una regola rende il livello non disponibile alla persona o al gruppo che nomina, e glielo spiega.',
    none: 'Nessuna regola sui dati.',
    layer: 'Livello',
    whole: 'Tutto il servizio',
    person: 'E-mail della persona',
    area: 'Area (WKT, nel sistema del servizio)',
    cql: 'Filtro delle righe (CQL)',
    add: 'Aggiungi la regola',
    remove: 'Togli',
    effect: 'Effetto: non disponibile',
    added: 'Regola aggiunta: il livello non è più disponibile per chi la regola nomina.',
    removed: 'Regola tolta.',
    failed: (s) => `Non è stato possibile salvare la regola (${s}).`,
  },
  en: {
    unavailableTitle: 'Not available to you',
    unavailable: (layer) =>
      layer
        ? `The layer ${layer} is not available to you: a data rule limits what you see, and this external service cannot apply it.`
        : 'This service is not available to you: a data rule limits what you see, and the external service cannot apply it.',
    rules: 'Data rules',
    rulesHint: 'An external service does not filter its data for Geoportal: a rule makes the layer unavailable to the person or team it names, and tells them why.',
    none: 'No data rule.',
    layer: 'Layer',
    whole: 'The whole service',
    person: 'E-mail of the person',
    area: 'Area (WKT, in the system of the service)',
    cql: 'Row filter (CQL)',
    add: 'Add the rule',
    remove: 'Remove',
    effect: 'Effect: unavailable',
    added: 'Rule added: the layer is no longer available to whom the rule names.',
    removed: 'Rule removed.',
    failed: (s) => `The rule could not be saved (${s}).`,
  },
};

const texts = (context) => TEXTS[(context.locale ?? 'en').split('-')[0]] ?? TEXTS.en;

/** What the person may not use of a service: [{layer, message}], layer null for the whole service. */
export async function unavailableOf(context, service) {
  const r = await context.fetch(`${API}/mine?service=${encodeURIComponent(service.id)}`);
  return r.ok ? await r.json() : [];
}

/** Whether a layer of the capabilities is unavailable to the person. */
export function isUnavailable(unavailable, name) {
  const local = (n) => n.slice(n.lastIndexOf(':') + 1).toLowerCase();
  return unavailable.some((u) => u.layer === null || u.layer === name || local(u.layer) === local(name));
}

/** The note that lists what is not available to the person, and why; null when everything is. */
export function unavailableNote(context, unavailable) {
  if (!unavailable || unavailable.length === 0) {
    return null;
  }
  const tx = texts(context);
  const box = document.createElement('div');
  box.className = 'unavailable';
  box.setAttribute('role', 'note');
  const h = document.createElement('h3');
  h.textContent = tx.unavailableTitle;
  const ul = document.createElement('ul');
  ul.replaceChildren(
    ...unavailable.map((u) => {
      const li = document.createElement('li');
      li.textContent = tx.unavailable(u.layer);
      return li;
    }),
  );
  box.append(h, ul);
  return box;
}

/** The rules of a service, for who manages it, with the form to add one. */
export function rulesSection(context, service, layers, onChange) {
  const tx = texts(context);
  const box = document.createElement('div');
  box.className = 'service-rules';
  box.setAttribute('role', 'group');
  box.setAttribute('aria-label', tx.rules);
  box.innerHTML = `<h3></h3><p class="muted small"></p><ul class="rules"></ul>
    <form class="rule-form">
      <label><span></span> <select name="layer"></select></label>
      <label><span></span> <input name="email" type="email" required /></label>
      <label><span></span> <input name="area" /></label>
      <label><span></span> <input name="cql" /></label>
      <button type="submit" class="primary"></button>
      <p class="small failure" role="alert"></p>
    </form>`;
  box.querySelector('h3').textContent = tx.rules;
  box.querySelector('p.muted').textContent = tx.rulesHint;
  const spans = box.querySelectorAll('form label > span');
  [tx.layer, tx.person, tx.area, tx.cql].forEach((label, i) => (spans[i].textContent = label));
  box.querySelector('form button').textContent = tx.add;
  const select = box.querySelector('select');
  const whole = document.createElement('option');
  whole.value = '';
  whole.textContent = tx.whole;
  select.append(
    whole,
    ...(layers ?? []).map((l) => {
      const o = document.createElement('option');
      o.value = l.name;
      o.textContent = l.title && l.title !== l.name ? `${l.title} (${l.name})` : l.name;
      return o;
    }),
  );
  if (layers?.length) {
    select.value = layers[0].name;
  }
  const list = box.querySelector('ul.rules');
  const failure = box.querySelector('.failure');
  const draw = (rules) => {
    if (rules.length === 0) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = tx.none;
      list.replaceChildren(li);
      return;
    }
    list.replaceChildren(
      ...rules.map((r) => {
        const li = document.createElement('li');
        li.innerHTML = '<b></b> · <span></span> · <span class="small muted"></span> <button type="button"></button>';
        li.querySelector('b').textContent = r.layer ?? tx.whole;
        li.querySelector('span').textContent = r.subject.name ?? r.subject.id;
        li.querySelector('.small').textContent = tx.effect;
        const remove = li.querySelector('button');
        remove.textContent = tx.remove;
        remove.addEventListener('click', async () => {
          const d = await context.fetch(`${API}/${r.id}`, { method: 'DELETE' });
          if (d.ok) {
            onChange(tx.removed);
            await refresh();
          }
        });
        return li;
      }),
    );
  };
  const refresh = async () => {
    const r = await context.fetch(`${API}?service=${encodeURIComponent(service.id)}`);
    if (r.ok) {
      draw(await r.json());
    }
  };
  box.querySelector('form').addEventListener('submit', async (event) => {
    event.preventDefault();
    failure.textContent = '';
    const form = event.target;
    const body = {
      service: service.id,
      layer: form.layer.value || null,
      subject: { kind: 'person', email: form.email.value },
      area: form.area.value || null,
      cql: form.cql.value || null,
    };
    const r = await context.fetch(API, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!r.ok) {
      let detail = '';
      try {
        detail = (await r.json()).detail ?? '';
      } catch {
        // no body
      }
      failure.textContent = `${tx.failed(r.status)} ${detail}`.trim();
      return;
    }
    form.email.value = '';
    form.area.value = '';
    form.cql.value = '';
    onChange(tx.added);
    await refresh();
  });
  void refresh();
  return box;
}
