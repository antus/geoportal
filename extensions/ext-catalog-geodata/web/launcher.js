// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * The launcher of the Geoportal apps on the home page of the console (GP-339, screen geoportal:home): a tile
 * for each of catalog, maps, dashboards and processes that the person may open, and none for the others. A
 * widget of the home page of Mosaikit (point home.widget, MK-101). What the person may open is what the app
 * bar of the shell shows them (GET /api/v1/shell/apps: the apps turned on in the organization, restricted
 * ones only for who holds their permission, MK-030 and MK-056), among the extensions active in the
 * organization (context.extensions, MK-133): the apps of dashboards and processes appear when their
 * extension is there, and disappear when it goes, without a reload.
 */

/**
 * The Geoportal apps, each with the extensions and apps of the app bar that can provide it, the first one
 * visible to the person giving the tile.
 */
export const GEOPORTAL_APPS = [
  {
    id: 'catalog',
    icon: 'catalog',
    sources: [
      { extension: 'io.github.mosaikit.ext.catalog', app: 'catalog', route: '/app/catalog' },
      { extension: 'dev.geoportal.ext.catalog.geodata', app: 'geodata', route: '/app/geodata' },
    ],
  },
  {
    id: 'maps',
    icon: 'maps',
    sources: [
      { extension: 'dev.geoportal.ext.maps', app: 'maps', route: '/app/maps' },
      { extension: 'dev.geoportal.maps', app: 'maps', route: '/app/maps' },
    ],
  },
  {
    id: 'dashboards',
    icon: 'dashboards',
    sources: [
      { extension: 'io.github.mosaikit.ext.dashboards', app: 'dashboards', route: '/app/dashboards' },
      { extension: 'dev.geoportal.dashboards', app: 'dashboards', route: '/app/dashboards' },
    ],
  },
  {
    id: 'processes',
    icon: 'processes',
    sources: [
      { extension: 'io.github.mosaikit.ext.processes', app: 'processes', route: '/app/processes' },
      { extension: 'dev.geoportal.processes', app: 'processes', route: '/app/processes' },
    ],
  },
];

const TEXTS = {
  it: {
    label: 'App di Geoportal',
    none: 'Nessuna app di Geoportal è disponibile per te in questa organizzazione.',
    failed: 'Le app non si possono leggere ora.',
    apps: {
      catalog: ['Catalogo', 'Dati, servizi, flussi e connessioni'],
      maps: ['Mappe', 'Mappe 2D e 3D'],
      dashboards: ['Cruscotti', 'Indicatori, grafici e tempo reale'],
      processes: ['Processi', 'A mano, a orario, su evento'],
    },
  },
  en: {
    label: 'Geoportal apps',
    none: 'No Geoportal app is available to you in this organization.',
    failed: 'The apps cannot be read now.',
    apps: {
      catalog: ['Catalog', 'Data, services, streams and connections'],
      maps: ['Maps', '2D and 3D maps'],
      dashboards: ['Dashboards', 'Indicators, charts and real time'],
      processes: ['Processes', 'By hand, on a schedule, on an event'],
    },
  },
};

const ICONS = {
  catalog: '<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>',
  maps: '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14"/>',
  dashboards: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  processes: '<circle cx="5" cy="6" r="2"/><circle cx="19" cy="6" r="2"/><circle cx="12" cy="18" r="2"/><path d="M7 6h10M6 8l5 8M18 8l-5 8"/>',
};

const STYLE = `
  :host { display: block; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
  a { display: flex; flex-direction: column; gap: 4px; padding: 14px; border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); color: var(--mk-fg); text-decoration: none; background: var(--mk-bg); height: 100%;
    box-sizing: border-box; }
  a:hover, a:focus-visible { border-color: var(--mk-accent); }
  a:focus-visible { outline: 2px solid var(--mk-accent); outline-offset: 2px; }
  svg { width: 22px; height: 22px; stroke: var(--mk-accent); fill: none; stroke-width: 1.8; stroke-linecap: round;
    stroke-linejoin: round; }
  b { font-size: 15px; }
  span { font-size: 13px; color: var(--mk-muted); }
  p { margin: 0; color: var(--mk-muted); font-size: 14px; }
`;

const language = (context) => (context.locale ?? 'en').split('-')[0];

/**
 * The tiles of the launcher: for each Geoportal app, the first of its sources that the app bar of the
 * person shows and whose extension is active, with the route its extension declares when known.
 *
 * @param {{extensionId: string, appId: string}[]} visible the apps of the app bar of the person
 * @param {(extension: string) => boolean} isActive whether an extension is active in the organization
 * @param {Map<string, string>} [routes] the route of each app, by `<extension>/<app>`, from the shell
 * @returns {{id: string, icon: string, extension: string, route: string}[]}
 */
export function launcherTiles(visible, isActive, routes = new Map()) {
  const shown = new Set(visible.map((a) => `${a.extensionId}/${a.appId}`));
  const tiles = [];
  for (const app of GEOPORTAL_APPS) {
    const source = app.sources.find((s) => shown.has(`${s.extension}/${s.app}`) && isActive(s.extension));
    if (source) {
      tiles.push({
        id: app.id,
        icon: app.icon,
        extension: source.extension,
        route: routes.get(`${source.extension}/${source.app}`) ?? source.route,
      });
    }
  }
  return tiles;
}

/** The routes of the apps of the active frontends, from the list the shell reads (GET /api/v1/shell/extensions). */
function routesOf(frontends) {
  const routes = new Map();
  for (const f of Array.isArray(frontends) ? frontends : []) {
    for (const c of f.contributions ?? []) {
      if ((c.point === 'rail.app' || c.point === 'launcher.app') && typeof c.attributes?.route === 'string') {
        routes.set(`${f.id}/${c.id}`, c.attributes.route);
      }
    }
  }
  return routes;
}

/** Defines the element of the widget, once. */
export function defineLauncher(context) {
  if (customElements.get('geoportal-launcher')) {
    return;
  }
  class Launcher extends HTMLElement {
    connectedCallback() {
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `<style>${STYLE}</style><div class="body"></div>`;
      this.visible = undefined;
      this.routes = new Map();
      this.stop = context.extensions?.subscribe?.(() => this.render());
      void this.load();
    }

    disconnectedCallback() {
      this.stop?.();
      this.stop = undefined;
    }

    async load() {
      try {
        const [apps, frontends] = await Promise.all([
          context.fetch('/api/v1/shell/apps'),
          context.fetch('/api/v1/shell/extensions').catch(() => undefined),
        ]);
        this.visible = apps.ok ? await apps.json() : null;
        if (frontends?.ok) {
          this.routes = routesOf(await frontends.json());
        }
      } catch {
        this.visible = null;
      }
      this.render();
    }

    render() {
      const t = TEXTS[language(context)] ?? TEXTS.en;
      const body = this.shadowRoot.querySelector('.body');
      if (this.visible === undefined) {
        return;
      }
      if (this.visible === null) {
        body.innerHTML = '<p role="status"></p>';
        body.querySelector('p').textContent = t.failed;
        return;
      }
      const isActive = (id) => context.extensions?.isActive?.(id) ?? true;
      const tiles = launcherTiles(this.visible, isActive, this.routes);
      if (tiles.length === 0) {
        body.innerHTML = '<p role="status"></p>';
        body.querySelector('p').textContent = t.none;
        return;
      }
      const list = document.createElement('ul');
      list.setAttribute('aria-label', t.label);
      for (const tile of tiles) {
        const [title, description] = t.apps[tile.id];
        const item = document.createElement('li');
        item.innerHTML = `<a><svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[tile.icon]}</svg><b></b><span></span></a>`;
        const link = item.querySelector('a');
        link.href = tile.route;
        link.dataset.app = tile.id;
        link.querySelector('b').textContent = title;
        link.querySelector('span').textContent = description;
        link.setAttribute('aria-label', `${title}: ${description}`);
        link.addEventListener('click', (event) => {
          if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
          }
          // Opened in the shell, as the links of its app bar: without loading the page again.
          event.preventDefault();
          history.pushState(null, '', tile.route);
          window.dispatchEvent(new PopStateEvent('popstate'));
        });
        list.append(item);
      }
      body.replaceChildren(list);
    }
  }
  customElements.define('geoportal-launcher', Launcher);
}
