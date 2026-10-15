// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { expect, test, type Page } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';
import { FILE_MANAGER, GEODATA } from './composition.js';

/**
 * GP-339, the Geoportal pages of the console, in Italian:
 *
 * - the home page after sign-in shows the launcher of the Geoportal apps (a widget of the home page, MK-101)
 *   with the apps the person may open and none of the others: on this bench the catalog only, the apps of
 *   maps, dashboards and processes are not installed; restricted by the organization, it disappears for the
 *   people without its permission;
 * - with the GeoServer of tests/geoserver/compose.yaml (GEOPORTAL_TEST_GEOSERVER=1): the preview of a data rule
 *   in its dialog, before it is saved, and the page that says why a person cannot see the data of a layer.
 *   That both say what GeoServer answers the person on WMS and WFS is proved by AccessDiagnosisTest
 *   (mvn test -Pgeoserver), which writes the same rules and asks GeoServer as the person.
 */

type Person = ReturnType<typeof member>;
interface App {
  extensionId: string;
  appId: string;
  enabled: boolean;
  pinned: boolean;
  restricted?: boolean;
}

const organization = (): string => bench().organization.slug;
const APPS = (): string => `/api/v1/organizations/${organization()}/apps`;

/** The launcher on the home page. */
const launcher = (page: Page) => page.getByRole('list', { name: 'App di Geoportal' });

async function restrict(restricted: boolean): Promise<void> {
  const current = (await api(bench().admin, APPS())).body as App[];
  const body = current.map((a) => ({
    extensionId: a.extensionId,
    appId: a.appId,
    enabled: a.enabled,
    pinned: a.pinned,
    restricted: restricted && (a.extensionId === FILE_MANAGER || a.extensionId === GEODATA),
  }));
  const { status } = await api(bench().admin, APPS(), { method: 'PUT', body: JSON.stringify(body) });
  expect(status).toBe(200);
}

test.describe('GP-339 home', () => {
  test.use({ locale: 'it-IT' });
  test.describe.configure({ mode: 'serial' });

  // The exceptions of permissions are given by an administrator signed in to the organization.
  test.beforeAll(async () => {
    await role(member(1), true);
  });

  test.afterAll(async () => {
    await api(bench().admin, APPS(), { method: 'PUT', body: '[]' });
    await api(member(1),
      `/api/v1/organizations/${organization()}/permissions/members/${member().user}/exceptions/${FILE_MANAGER}:use-catalog`,
      { method: 'DELETE' });
    await role(member(1), false);
  });

  test('GP-339 after sign-in the home shows the launcher of the Geoportal apps the person may open', async ({ page }) => {
    await signIn(page, member());
    await page.goto(bench().baseUrl + '/');
    const apps = launcher(page);
    await expect(apps).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Le tue app' })).toBeVisible();
    // The catalog, from the file manager of mosaikit-ext; maps, dashboards and processes are not installed here.
    await expect(apps.getByRole('link')).toHaveCount(1);
    const catalog = apps.getByRole('link', { name: /^Catalogo/ });
    await expect(catalog).toHaveAttribute('href', '/app/catalog');
    await expect(apps.getByRole('link', { name: /^Mappe/ })).toHaveCount(0);
    await expect(apps.getByRole('link', { name: /^Cruscotti/ })).toHaveCount(0);
    await expect(apps.getByRole('link', { name: /^Processi/ })).toHaveCount(0);
    await page.screenshot({ path: 'test-results/gp-339-home.png', fullPage: true });
    // The tile opens the app in the shell.
    await catalog.click();
    await expect(page).toHaveURL(/\/app\/catalog$/);
  });

  test('GP-339 an app the organization restricts disappears from the launcher of who may not open it', async ({ page, browser }) => {
    await restrict(true);
    await signIn(page, member());
    await page.goto(bench().baseUrl + '/');
    await expect(page.getByText('Nessuna app di Geoportal è disponibile per te in questa organizzazione.')).toBeVisible();
    await expect(launcher(page)).toHaveCount(0);
    await page.screenshot({ path: 'test-results/gp-339-home-restricted.png', fullPage: true });

    // Granted the permission of the app of the catalog, the person has the tile again; the shell shows the same.
    const grant = await api(member(1),
      `/api/v1/organizations/${organization()}/permissions/members/${member().user}/exceptions/${FILE_MANAGER}:use-catalog`,
      { method: 'PUT' });
    expect(grant.status).toBe(200);
    const shown = ((await api(member(), '/api/v1/shell/apps')).body as App[]).map((a) => `${a.extensionId}/${a.appId}`);
    expect(shown).toContain(`${FILE_MANAGER}/catalog`);
    expect(shown).not.toContain(`${GEODATA}/geodata`);
    await page.reload();
    await expect(launcher(page).getByRole('link', { name: /^Catalogo/ })).toHaveAttribute('href', '/app/catalog');
    // The administrator, who holds every permission, keeps the tile of the restricted app.
    const other = await browser.newContext({ locale: 'it-IT' });
    try {
      const admin = await other.newPage();
      await signIn(admin, member(1));
      await admin.goto(bench().baseUrl + '/');
      await expect(launcher(admin).getByRole('link', { name: /^Catalogo/ })).toBeVisible();
    } finally {
      await other.close();
    }

    await restrict(false);
  });
});

// With GeoServer ---------------------------------------------------------------------------------

const EXTENSION = GEODATA;
const UPLOADS = '/api/v1/ext/catalog-geodata/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog-geodata/publications';
const RULES = '/api/v1/ext/catalog-geodata/data-rules';
const DIAGNOSIS = '/api/v1/ext/catalog-geodata/access-diagnosis';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;

const owner = (): Person => member(0);
const manager = (): Person => member(1);
const colleague = (): Person => member(2);
const stranger = (): Person => member(3);
const exception = (person: Person, permission: string): string =>
  `/api/v1/organizations/${organization()}/permissions/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

/** Three stations of a test town (Centrale, Nord, Sud) in EPSG:32633, with a reserved column. */
const STATIONS = JSON.stringify({
  type: 'FeatureCollection',
  crs: { type: 'name', properties: { name: 'urn:ogc:def:crs:EPSG::32633' } },
  features: [
    ['Centrale', 292719.039, 4641793.153],
    ['Nord', 295196.583, 4642777.113],
    ['Sud', 291273.415, 4638645.888],
  ].map(([nome, x, y]) => ({
    type: 'Feature',
    properties: { nome, riservato: `segreto ${String(nome)}` },
    geometry: { type: 'Point', coordinates: [x, y] },
  })),
});

async function role(person: Person, admin: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: admin ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

async function credential(name: string, value: string | null): Promise<void> {
  const path = `/api/v1/organizations/${organization()}/secrets`;
  const existing = await api(manager(), `${path}/${name}`);
  if (value === null) {
    if (existing.status === 200) {
      await api(manager(), `${path}/${name}`, { method: 'DELETE' });
    }
    return;
  }
  const body = JSON.stringify({ name, kind: 'password', value, description: `GP-339 ${name}`, extension: EXTENSION });
  const { status } =
    existing.status === 200
      ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
      : await api(manager(), path, { method: 'POST', body });
  expect(status).toBeLessThan(300);
}

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

/** A call of the gate of the catalog as a person. */
async function gate(person: Person, path: string): Promise<Response> {
  return fetch(bench().baseUrl + path, {
    headers: {
      authorization: 'Basic ' + Buffer.from(`${person.user}:${person.password}`).toString('base64'),
      'X-Mosaikit-Organization': organization(),
    },
  });
}

interface Answer {
  total: number;
  matched: number;
  columns: string[];
  visible: string[];
  hidden: string[];
  sample: Record<string, unknown>[];
}

test.describe('GP-339 on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);
  test.use({ locale: 'it-IT' });

  let dataset: { id: string; state: string; stateReason: string | null };
  let layer: string;

  test.beforeAll(async () => {
    await role(colleague(), false);
    await role(stranger(), false);
    await role(manager(), true);
    for (const p of ['upload-data', 'publish']) {
      expect((await api(manager(), exception(owner(), p), { method: 'PUT' })).status).toBe(200);
    }
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
    const s = bench().settings;
    await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);
    const uploaded = await api(owner(), `${UPLOADS}?${new URLSearchParams({ name: `${unique('stazioni').toLowerCase()}.geojson` })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: STATIONS,
    });
    expect(uploaded.status, JSON.stringify(uploaded.body)).toBe(201);
    dataset = uploaded.body as typeof dataset;
    expect(dataset.state, dataset.stateReason ?? '').toBe('loaded');
    layer = `gp339_${Date.now()}`;
    const published = await api(owner(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: dataset.id, layer }) });
    expect(published.status, JSON.stringify(published.body)).toBe(201);
    const grant = await api(owner(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${dataset.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(colleague()) }, level: 'read' }),
    });
    expect(grant.status).toBeLessThan(300);
  });

  test.afterAll(async () => {
    if (dataset) {
      await api(owner(), `${UPLOADS}/${dataset.id}`, { method: 'DELETE' });
    }
    for (const p of ['upload-data', 'publish']) {
      await api(manager(), exception(owner(), p), { method: 'DELETE' });
    }
    await credential('published-views', null);
    await credential('geodata', null);
    await role(manager(), false);
  });

  test('GP-339 the manager edits a data rule and the preview shows the features and columns the subject will receive from WFS', async ({ page }) => {
    // The preview, as the page asks it: nothing is saved.
    const preview = await api(owner(), `${RULES}/preview`, {
      method: 'POST',
      body: JSON.stringify({ layer, subject: { kind: 'person', email: colleague().user }, cql: "nome <> 'Sud'", hidden: ['riservato'] }),
    });
    expect(preview.status, JSON.stringify(preview.body)).toBe(200);
    const p = preview.body as { allowed: boolean; cause: string; answer: Answer };
    expect(p.allowed).toBe(true);
    expect(p.cause).toBe('data-rules');
    expect(p.answer.total).toBe(3);
    expect(p.answer.matched).toBe(2);
    expect(p.answer.visible).toEqual(['nome']);
    expect(p.answer.hidden).toEqual(['riservato']);
    expect(p.answer.sample.map((r) => r.nome).sort()).toEqual(['Centrale', 'Nord']);
    expect(JSON.stringify(p.answer.sample)).not.toContain('segreto');
    expect(((await api(owner(), `${RULES}?layer=${layer}`)).body as { rules: unknown[] }).rules).toEqual([]);
    // A person without a grant receives nothing, with or without the rule; only who manages the dataset previews.
    const none = await api(owner(), `${RULES}/preview`, {
      method: 'POST',
      body: JSON.stringify({ layer, subject: { kind: 'person', email: stranger().user }, cql: "nome <> 'Sud'" }),
    });
    expect((none.body as { allowed: boolean; cause: string }).cause).toBe('no-grant');
    expect((await api(colleague(), `${RULES}/preview`, {
      method: 'POST', body: JSON.stringify({ layer, subject: { kind: 'person', email: colleague().user } }),
    })).status).toBe(403);

    // In the page: the dialog of a new rule shows the preview as the rule is written.
    await signIn(page, manager());
    await openApp(page, 'Dati geografici');
    await page.locator('tr.row', { hasText: layer }).click();
    const section = page.getByRole('region', { name: 'Regole sui dati' });
    await section.getByRole('button', { name: 'Nuova regola' }).click();
    const dialog = page.getByRole('dialog', { name: 'Nuova regola sui dati' });
    await dialog.getByLabel('Indirizzo e-mail della persona').fill(colleague().user);
    await dialog.getByLabel('Filtro sulle righe (CQL)').fill("nome <> 'Sud'");
    await dialog.getByLabel('riservato').check();
    await dialog.getByRole('button', { name: 'Aggiorna l’anteprima' }).click();
    const shown = dialog.getByRole('complementary');
    await expect(shown).toContainText('Che cosa riceve da WFS la persona');
    await expect(shown).toContainText('Elementi2 di 3');
    await expect(shown).toContainText('Colonne1 di 2 · senza riservato');
    const table = shown.getByRole('table', { name: 'I primi 2 elementi che riceve' });
    await expect(table).toContainText('Centrale');
    await expect(table).toContainText('Nord');
    await expect(table).not.toContainText('Sud');
    await expect(table).not.toContainText('segreto');
    await page.screenshot({ path: 'test-results/gp-339-preview.png', fullPage: true });
    // The filter changes: the preview follows before the rule is saved.
    await dialog.getByLabel('Filtro sulle righe (CQL)').fill("nome = 'Nessuno'");
    await dialog.getByRole('button', { name: 'Aggiorna l’anteprima' }).click();
    await expect(shown).toContainText('Elementi0 di 3');
    await expect(shown).toContainText('Con questa regola non riceve alcun elemento.');
    await dialog.getByLabel('Filtro sulle righe (CQL)').fill("nome <> 'Sud'");
    await dialog.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByText('La regola è stata salvata e le regole di accesso riscritte.')).toBeVisible();

    // Edited again: the dialog opens with the preview of the saved rule.
    await section.getByRole('button', { name: `Modifica ${colleague().user}` }).click();
    const edit = page.getByRole('dialog', { name: 'Modifica la regola sui dati' });
    await expect(edit.getByRole('complementary')).toContainText('Elementi2 di 3');
    await edit.getByRole('button', { name: 'Annulla' }).click();
  });

  test('GP-339 the diagnosis names the missing grant or the data rule that hides the data', async ({ page }) => {
    const diagnose = async (person: Person) =>
      (await api(manager(), `${DIAGNOSIS}?${new URLSearchParams({ layer, person: person.user })}`)).body as {
        verdict: string;
        missing: string | null;
        level: string | null;
        services: { service: string; allowed: boolean; cause: string }[];
        answer: Answer | null;
        rules: { via: string; cql: string | null; hidden: string[]; alone: number | null; hides: boolean }[];
      };
    // The colleague: read, limited by the rule of the test before.
    const limited = await diagnose(colleague());
    expect(limited.verdict).toBe('rules-limit');
    expect(limited.level).toBe('read');
    expect(limited.services).toEqual([
      { service: 'WMS', allowed: true, cause: 'data-rules' },
      { service: 'WFS', allowed: true, cause: 'data-rules' },
    ]);
    expect(limited.answer?.matched).toBe(2);
    expect(limited.answer?.hidden).toEqual(['riservato']);
    expect(limited.rules).toEqual([expect.objectContaining({ via: 'person', cql: "nome <> 'Sud'", alone: 2, hides: true })]);
    // A person without a grant: the grant is missing, and the gate of the catalog refuses them as GeoServer does.
    const nobody = await diagnose(stranger());
    expect(nobody.verdict).toBe('no-grant');
    expect(nobody.missing).toBe('read');
    expect(nobody.services.every((s) => !s.allowed && s.cause === 'no-grant')).toBe(true);
    const refused = await gate(stranger(), `${PUBLICATIONS}/${layer}/ogc/wms?service=WMS&request=GetCapabilities`);
    expect(refused.status).toBeGreaterThanOrEqual(400);
    // The owner sees everything.
    expect((await diagnose(owner())).verdict).toBe('full');
    // Only who manages the dataset asks.
    expect((await api(colleague(), `${DIAGNOSIS}?${new URLSearchParams({ layer, person: owner().user })}`)).status).toBe(403);

    // A rule that hides every feature from the colleague is named as the cause.
    const rules = (await api(owner(), `${RULES}?layer=${layer}`)).body as { rules: { id: string }[] };
    const changed = await api(owner(), `${RULES}/${rules.rules[0].id}`, {
      method: 'PUT',
      body: JSON.stringify({ layer, subject: { kind: 'person', email: colleague().user }, cql: "nome = 'Nessuno'", hidden: ['riservato'] }),
    });
    expect(changed.status, JSON.stringify(changed.body)).toBe(200);

    // In the page.
    await signIn(page, manager());
    await openApp(page, 'Dati geografici');
    await page.getByRole('tab', { name: 'Perché non vede i dati' }).click();
    await expect(page).toHaveURL(/\/app\/geodata\/accesso$/);
    const view = page.getByRole('region', { name: 'Perché non vede i dati' });
    await view.getByLabel('Persona (e-mail)').fill(colleague().user);
    await view.getByLabel('Livello').selectOption(layer);
    await view.getByRole('button', { name: 'Verifica' }).click();
    await expect(view.locator('.verdict')).toHaveText(`Le regole sui dati nascondono a ${colleague().user} tutti gli elementi.`);
    const answered = view.getByRole('region', { name: 'Che cosa risponde GeoServer' });
    await expect(answered).toContainText('Dati (WFS)sì · con i limiti delle regole sui dati');
    await expect(answered).toContainText('Elementi ricevuti0 di 3');
    const applying = view.getByRole('region', { name: 'Regole sui dati che valgono per la persona' });
    await expect(applying).toContainText('Per la persona');
    await expect(applying).toContainText('Nasconde dati');
    await expect(applying).toContainText("righe: nome = 'Nessuno'");
    await expect(applying).toContainText('da sola lascia 0 elementi di 3');
    await expect(page).toHaveURL(new RegExp(`/app/geodata/accesso\\?layer=${layer}&person=`));
    await page.screenshot({ path: 'test-results/gp-339-diagnosis-rules.png', fullPage: true });

    // The address gives the same diagnosis; another person: the missing grant.
    await page.goto(`${bench().baseUrl}/app/geodata/accesso?${new URLSearchParams({ layer, person: stranger().user })}`);
    const again = page.getByRole('region', { name: 'Perché non vede i dati' });
    await expect(again.locator('.verdict')).toHaveText(`Manca il permesso: nessuna condivisione del dato raggiunge ${stranger().user}.`);
    await expect(again).toContainText('serve il livello Leggere e scaricare');
    await expect(again.getByRole('region', { name: 'Che cosa risponde GeoServer' })).toContainText('Mappa (WMS)no · nessun permesso');
    await page.screenshot({ path: 'test-results/gp-339-diagnosis-grant.png', fullPage: true });
  });
});
