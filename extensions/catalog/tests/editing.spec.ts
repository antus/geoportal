// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { expect, test } from '@playwright/test';
import { api, bench, member, openApp, signIn, unique } from '@mosaikit/testing/playwright';

/**
 * GP-036 on the bench: the permission edit-layer-data, the profiles Editor and Rilevatore an organization
 * makes with it, and the refusals of the API of editing. With the GeoServer of tests/geoserver/compose.yaml
 * (GEOPORTAL_TEST_GEOSERVER=1) also the whole path in Italian: a manager assigns a published layer to a
 * surveyor in the page, the surveyor commits a WFS transaction through the catalog, the editor and the
 * surveyor on another layer are refused, GeoFence holds the transaction rule of the surveyor only, and
 * removing the assignment refuses the next transaction. What GeoServer answers to WFS-T sent to it
 * directly is proved by SurveyorEditingTest (mvn test -Pgeoserver).
 */

type Person = ReturnType<typeof member>;
interface Upload {
  id: string;
  title: string;
  technicalName: string;
  state: string;
  stateReason: string | null;
}
interface Profile {
  id: string;
  name: string;
  permissions: string[];
}
interface Mine {
  holds: boolean;
  layers: { layer: string; editable: boolean; dataRules: string | null }[];
}

const EXTENSION = 'dev.geoportal.catalog';
const PERMISSION = `${EXTENSION}:edit-layer-data`;
const EDITING = '/api/v1/ext/catalog/editing';
const UPLOADS = '/api/v1/ext/catalog/uploads';
const PUBLICATIONS = '/api/v1/ext/catalog/publications';
const RULES = '/api/v1/ext/catalog/data-rules';
const GEOSERVER = !!process.env.GEOPORTAL_TEST_GEOSERVER;
const GEOSERVER_URL = 'http://127.0.0.1:18680/geoserver';
const GS_AUTH = 'Basic ' + Buffer.from('gsadmin:catalog-test-admin').toString('base64');

// The owner of the data (a surveyor here), the administrator, an editor.
const surveyor = (): Person => member(0);
const manager = (): Person => member(1);
const editor = (): Person => member(2);
const organization = (): string => bench().organization.slug;
const permissions = (): string => `/api/v1/organizations/${organization()}/permissions`;
const exception = (person: Person, permission: string): string =>
  `${permissions()}/members/${person.user}/exceptions/${EXTENSION}:${permission}`;

async function administrator(person: Person, on: boolean): Promise<void> {
  const { status } = await api(bench().admin, `/api/v1/organizations/${organization()}/members/${person.user}`, {
    method: 'PUT',
    body: JSON.stringify({ roles: on ? ['organization-admin', 'organization-user'] : ['organization-user'] }),
  });
  expect(status).toBe(200);
}

/** Creates a profile of the organization, or changes the one with that name. */
async function profile(name: string, description: string, granted: string[]): Promise<Profile> {
  const all = (await api(manager(), `${permissions()}/profiles`)).body as Profile[];
  const existing = all.find((p) => p.name === name);
  const body = JSON.stringify({ name, description, defaultForRole: null, permissions: granted });
  const r = existing
    ? await api(manager(), `${permissions()}/profiles/${existing.id}`, { method: 'PUT', body })
    : await api(manager(), `${permissions()}/profiles`, { method: 'POST', body });
  expect(r.status, JSON.stringify(r.body)).toBeLessThan(300);
  return r.body as Profile;
}

async function give(person: Person, p: Profile | null): Promise<void> {
  const r = await api(manager(), `${permissions()}/members/${person.user}/profile`, {
    method: 'PUT',
    body: JSON.stringify({ profile: p?.id ?? null }),
  });
  expect(r.status, JSON.stringify(r.body)).toBe(200);
}

async function accountOf(person: Person): Promise<string> {
  return ((await api(person, '/api/v1/accounts/me')).body as { id: string }).id;
}

async function granted(person: Person): Promise<string> {
  return JSON.stringify((await api(person, '/api/v1/shell/permissions')).body);
}

let editorProfile: Profile;
let surveyorProfile: Profile;

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  await administrator(manager(), true);
  await administrator(surveyor(), false);
  await administrator(editor(), false);
});

test.afterAll(async () => {
  await give(surveyor(), null);
  await give(editor(), null);
  await administrator(manager(), false);
});

test('GP-036 the permission to edit the data of assigned layers is declared in Italian and off until a profile holds it', async () => {
  const declared = JSON.stringify((await api(manager(), permissions())).body);
  expect(declared).toContain(PERMISSION);
  expect(declared).toContain('Modificare i dati dei livelli assegnati');
  expect(await granted(surveyor())).not.toContain(PERMISSION);
  expect(((await api(surveyor(), EDITING)).body as Mine).holds).toBe(false);

  // The two profiles an organization makes: the editor publishes without touching data, the surveyor edits
  // the data of the layers assigned to them.
  editorProfile = await profile('Editor', 'Pubblica mappe e servizi senza modificare i dati di origine',
    [`${EXTENSION}:use-catalog`, `${EXTENSION}:publish`].filter((p) => declared.includes(p)));
  surveyorProfile = await profile('Rilevatore', 'Modifica i dati dei soli livelli assegnati',
    [`${EXTENSION}:use-catalog`, PERMISSION].filter((p) => declared.includes(p)));
  expect(editorProfile.permissions).not.toContain(PERMISSION);
  expect(surveyorProfile.permissions).toContain(PERMISSION);
  await give(editor(), editorProfile);
  await give(surveyor(), surveyorProfile);
  expect(await granted(surveyor())).toContain(PERMISSION);
  expect(await granted(editor())).not.toContain(PERMISSION);
  expect(((await api(surveyor(), EDITING)).body as Mine)).toEqual({ holds: true, layers: [] });
  expect(((await api(editor(), EDITING)).body as Mine).holds).toBe(false);
});

test('GP-036 the API of editing refuses what is not published, what the person does not manage, and a body that is not a transaction', async () => {
  const tx = '<wfs:Transaction service="WFS" version="1.1.0" xmlns:wfs="http://www.opengis.net/wfs"/>';
  const post = (person: Person, layer: string, body: string) =>
    api(person, `${EDITING}/layers/${layer}/transaction`, { method: 'POST', headers: { 'content-type': 'text/xml' }, body });
  expect((await post(surveyor(), 'nessuno', tx)).status).toBe(404);
  expect((await post(editor(), 'nessuno', tx)).status).toBe(404);
  expect((await api(editor(), `${EDITING}/layers/nessuno/assignments`)).status).toBe(404);
  expect((await api(editor(), `${EDITING}/layers/nessuno/assignments/${crypto.randomUUID()}`, { method: 'PUT' })).status).toBe(404);
  // Only who manages a published layer reads the people of the organization.
  expect((await api(editor(), `${EDITING}/people`)).status).toBe(403);
});

test.describe('on GeoServer', () => {
  test.skip(!GEOSERVER, 'set GEOPORTAL_TEST_GEOSERVER=1 with tests/geoserver/compose.yaml up');
  test.setTimeout(240_000);

  let parcels: Upload;
  let roads: Upload;
  let layer: string;
  let other: string;
  let ws: string;
  let ns: string;

  async function credential(name: string, value: string | null): Promise<void> {
    const path = `/api/v1/organizations/${organization()}/secrets`;
    const existing = await api(manager(), `${path}/${name}`);
    if (value === null) {
      if (existing.status === 200) {
        await api(manager(), `${path}/${name}`, { method: 'DELETE' });
      }
      return;
    }
    const body = JSON.stringify({ name, kind: 'password', value, description: `GP-036 ${name}`, extension: EXTENSION });
    const { status } =
      existing.status === 200
        ? await api(manager(), `${path}/${name}`, { method: 'PUT', body })
        : await api(manager(), path, { method: 'POST', body });
    expect(status).toBeLessThan(300);
  }

  async function upload(name: string): Promise<Upload> {
    const points = JSON.stringify({
      type: 'FeatureCollection',
      features: [{ type: 'Feature', properties: { nome: 'Centrale' }, geometry: { type: 'Point', coordinates: [12.5018, 41.9009] } }],
    });
    const r = await api(manager(), `${UPLOADS}?${new URLSearchParams({ name, title: name.replace(/\..*$/, '') })}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: points,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    const d = r.body as Upload;
    expect(d.state, d.stateReason ?? '').toBe('loaded');
    // Shared with the organization at download: both read it, neither manages it.
    const g = await api(manager(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${d.id}/grants`, {
      method: 'PUT',
      body: JSON.stringify({ subject: { kind: 'organization' }, level: 'download' }),
    });
    expect(g.status, JSON.stringify(g.body)).toBeLessThan(300);
    return d;
  }

  async function publish(d: Upload, name: string): Promise<void> {
    const r = await api(manager(), PUBLICATIONS, { method: 'POST', body: JSON.stringify({ dataset: d.id, layer: name }) });
    expect(r.status, JSON.stringify(r.body)).toBe(201);
  }

  const insert = (name: string, nome: string): string =>
    `<wfs:Transaction service="WFS" version="1.1.0" xmlns:wfs="http://www.opengis.net/wfs" xmlns:gml="http://www.opengis.net/gml" xmlns:o="${ns}">` +
    `<wfs:Insert><o:${name}><o:nome>${nome}</o:nome><o:geom><gml:Point srsName="EPSG:4326"><gml:pos>41.9 12.5</gml:pos></gml:Point></o:geom></o:${name}></wfs:Insert></wfs:Transaction>`;

  const transaction = (person: Person, name: string, nome: string) =>
    api(person, `${EDITING}/layers/${name}/transaction`, {
      method: 'POST',
      headers: { 'content-type': 'text/xml' },
      body: insert(name, nome),
    });

  interface Rule {
    workspace: string | null;
    userName: string | null;
    layer: string | null;
    request: string | null;
    access: string;
    layerDetails?: { cqlFilterRead: string | null; cqlFilterWrite: string | null } | null;
  }

  async function transactionRules(): Promise<Rule[]> {
    const all = (await (await fetch(`${GEOSERVER_URL}/rest/geofence/rules.json`, { headers: { authorization: GS_AUTH } })).json()) as {
      rules: Rule[];
    };
    return all.rules.filter((r) => r.workspace === ws && r.request === 'TRANSACTION');
  }

  test.beforeAll(async () => {
    await credential('geoserver', 'gsadmin:catalog-test-admin');
    await credential('geodata', 'geoserver:catalog-test-db');
    const s = bench().settings;
    await credential('published-views', `${s['quarkus.datasource.username']}:${s['quarkus.datasource.password']}`);
    parcels = await upload(`${unique('particelle').toLowerCase()}.geojson`);
    roads = await upload(`${unique('strade').toLowerCase()}.geojson`);
    layer = `gp036_${Date.now()}`;
    other = `${layer}_altro`;
    await publish(parcels, layer);
    await publish(roads, other);
    ws = ((await api(manager(), '/api/v1/ext/catalog/geoserver/alignment')).body as { workspace: string }).workspace;
    const id = ws.slice(4).replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
    ns = `urn:geoportal:organization:${id}`;
  });

  test.afterAll(async () => {
    for (const d of [parcels, roads]) {
      if (d) {
        await api(manager(), `${UPLOADS}/${d.id}`, { method: 'DELETE' });
      }
    }
    await credential('published-views', null);
  });

  test.describe('in Italian', () => {
    test.use({ locale: 'it-IT' });

    test('GP-036 a manager assigns a layer to a surveyor in the page; the surveyor edits it, the editor and the other layer are refused, and removing it applies at the next request', async ({ page }) => {
      // Before any assignment nobody writes, and GeoFence has no transaction rule but the denial.
      expect((await transaction(surveyor(), layer, 'prima')).status).toBe(403);
      expect((await transactionRules()).filter((r) => r.access === 'ALLOW')).toEqual([]);

      await signIn(page, manager());
      await openApp(page, 'Catalogo');
      await page.getByRole('row', { name: new RegExp(layer) }).first().click();
      const panel = page.getByLabel(layer, { exact: true });
      await expect(panel.getByRole('heading', { name: 'Rilevatori' })).toBeVisible();
      await expect(panel).toContainText('Nessun rilevatore assegnato');
      await panel.getByLabel('Persona da assegnare').selectOption(await accountOf(surveyor()));
      await panel.getByRole('button', { name: 'Assegna' }).click();
      const surveyors = panel.getByRole('list', { name: 'Rilevatori' });
      await expect(surveyors).toContainText(surveyor().user);
      await expect(surveyors).toContainText('Livello sul dato: Scaricare');
      await page.screenshot({ path: 'test-results/gp-036-assigned.png', fullPage: true });

      // The surveyor: the catalog learns that their profile holds the permission, and GeoFence lets them write.
      const mine = (await api(surveyor(), EDITING)).body as Mine;
      expect(mine).toMatchObject({ holds: true, layers: [{ layer, editable: true }] });
      const ok = await transaction(surveyor(), layer, 'rilevata');
      expect(ok.status, JSON.stringify(ok.body)).toBe(200);
      expect(String(ok.body)).toContain('totalInserted>1');
      const rules = await transactionRules();
      expect(rules.filter((r) => r.access === 'ALLOW')).toEqual([
        expect.objectContaining({ userName: surveyor().user, layer, request: 'TRANSACTION' }),
      ]);
      // On another layer of the organization, and the editor on the assigned one.
      const elsewhere = await transaction(surveyor(), other, 'abusiva');
      expect(elsewhere.status).toBe(403);
      expect(elsewhere.body).toMatchObject({ code: 'not-assigned' });
      const byEditor = await transaction(editor(), layer, 'editor');
      expect(byEditor.status).toBe(403);
      expect(byEditor.body).toMatchObject({ code: 'permission' });
      // Nor through the file of the dataset, even with the level edit-data on it.
      const g = await api(manager(), `/api/v1/sharing/resources/${EXTENSION}/dataset/${parcels.id}/grants`, {
        method: 'PUT',
        body: JSON.stringify({ subject: { kind: 'person', id: await accountOf(editor()) }, level: 'edit-data' }),
      });
      expect(g.status, JSON.stringify(g.body)).toBeLessThan(300);
      const reload = await api(editor(), `${UPLOADS}/${parcels.id}/load`, { method: 'POST' });
      expect(reload.status, JSON.stringify(reload.body)).toBe(403);
      expect(JSON.stringify(reload.body)).toContain('edit-layer-data');

      // In the page of the surveyor, the layer is theirs.
      const surveyorPage = await page.context().browser()!.newPage({ locale: 'it-IT' });
      try {
        await signIn(surveyorPage, surveyor());
        await openApp(surveyorPage, 'Catalogo');
        const row = surveyorPage.getByRole('row', { name: new RegExp(layer) }).first();
        await expect(row).toContainText('Assegnato a te');
        await row.click();
        await expect(surveyorPage.getByLabel(layer, { exact: true })).toContainText('Questo livello è assegnato a te: puoi modificarne i dati.');
        await surveyorPage.screenshot({ path: 'test-results/gp-036-surveyor.png', fullPage: true });
      } finally {
        await surveyorPage.close();
      }

      // Removed in the page: the next request of the surveyor, in the same session, is refused.
      // The button names the surveyor by display name and user name.
      await surveyors.getByRole('listitem').filter({ hasText: surveyor().user }).getByRole('button', { name: /^Togli l’assegnazione a / }).click();
      await expect(page.getByText('non è più assegnato al livello')).toBeVisible();
      const after = await transaction(surveyor(), layer, 'dopo');
      expect(after.status).toBe(403);
      expect(after.body).toMatchObject({ code: 'not-assigned' });
      expect((await transactionRules()).filter((r) => r.access === 'ALLOW')).toEqual([]);
      const audit = await api(manager(), `/api/v1/organizations/${organization()}/audit-events?action=${EXTENSION}:layer.unassigned`);
      expect(JSON.stringify(audit.body)).toContain(layer);
    });

    test('GP-036 GP-308 a surveyor limited by a data rule writes within it through GeoServer: the catalog refuses its own transaction with the reason, and the page says where to edit', async ({ page }) => {
      const account = await accountOf(surveyor());
      const assigned = await api(manager(), `${EDITING}/layers/${layer}/assignments/${account}`, { method: 'PUT' });
      expect(assigned.status, JSON.stringify(assigned.body)).toBe(200);
      const created = await api(manager(), RULES, {
        method: 'POST',
        body: JSON.stringify({ layer, subject: { kind: 'person', email: surveyor().user }, cql: "nome <> 'bloccata'" }),
      });
      expect(created.status, JSON.stringify(created.body)).toBe(201);
      const ruleId = (created.body as { rule: { id: string } }).rule.id;
      try {
        // The API: the layer is editable within the rule, through the WFS service of the layer only.
        const mine = (await api(surveyor(), EDITING)).body as Mine;
        expect(mine).toMatchObject({ holds: true, layers: [{ layer, editable: true, dataRules: 'data-rules-service' }] });
        const refused = await transaction(surveyor(), layer, 'dal catalogo');
        expect(refused.status).toBe(403);
        expect(refused.body).toMatchObject({ code: 'data-rules-service' });
        expect(JSON.stringify(refused.body)).toContain('WFS service of the layer');

        // GeoFence: the transaction of the surveyor carries the row filter of the rule as write filter.
        const allowed = (await transactionRules()).filter((r) => r.access === 'ALLOW');
        expect(allowed).toEqual([expect.objectContaining({ userName: surveyor().user, layer, request: 'TRANSACTION' })]);
        expect(allowed[0].layerDetails?.cqlFilterWrite).toBe("nome <> 'bloccata'");

        // The page of the surveyor says where and within what they edit.
        await signIn(page, surveyor());
        await openApp(page, 'Catalogo');
        await page.getByRole('row', { name: new RegExp(layer) }).first().click();
        await expect(page.getByLabel(layer, { exact: true })).toContainText(
          'le regole sui dati ti limitano: puoi modificarne i dati con il WFS-T del servizio del livello',
        );
        await page.screenshot({ path: 'test-results/gp-036-data-rules.png', fullPage: true });

        // A rule that hides a column stops writes everywhere, and says so.
        const hiding = await api(manager(), `${RULES}/${ruleId}`, {
          method: 'PUT',
          body: JSON.stringify({ layer, subject: { kind: 'person', email: surveyor().user }, hidden: ['nome'] }),
        });
        expect(hiding.status, JSON.stringify(hiding.body)).toBe(200);
        const ruled = (await api(surveyor(), EDITING)).body as Mine;
        expect(ruled).toMatchObject({ layers: [{ layer, editable: false, dataRules: 'data-rules' }] });
        expect((await transaction(surveyor(), layer, 'nascosta')).body).toMatchObject({ code: 'data-rules' });
        expect((await transactionRules()).filter((r) => r.access === 'ALLOW')).toEqual([]);
      } finally {
        await api(manager(), `${RULES}/${ruleId}`, { method: 'DELETE' });
        await api(manager(), `${EDITING}/layers/${layer}/assignments/${account}`, { method: 'DELETE' });
      }
    });
  });
});
