# Dashboards

The dashboards of the organization (a mock, until the dashboard designer arrives).

| Path | Content |
|---|---|
| `manifest.yaml` | What the extension is and brings: identity, frontend, contributions |
| `docs/requirements/` | The requirements this extension implements alone (`GP-nnn.md`); those that span extensions are in `docs/requirements/` of the repository |
| `web/index.js` | The app, a custom element that the shell renders at `/app/dashboards` |

## Build and test

A project of its own on the parent of Mosaikit extensions: Java 25, Maven and Node.js 24.

```bash
mvn package    # target/<name>-<version>.zip, the package to install
mvn verify     # also the tests of tests/, on a Mosaikit installation started for them
npm run dev    # Mosaikit with this extension, until Ctrl+C
```
