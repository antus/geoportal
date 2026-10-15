# Geoportal theme

The name, the logo and the colors of Geoportal: the coloring "Geoportal" of the mockup of layout A,
in light and dark, and its compass rose in `web/` (`logo.svg` for light backgrounds, `logo-dark.svg`
for dark ones). What it leaves out comes from the default theme of Mosaikit.

An installation of Geoportal makes it its theme with `mosaikit.ui.theme=dev.geoportal.theme`: the
shell is then called Geoportal, in its bar, on the sign-in page and in the title of the page, with
the logo of Geoportal, also for people who choose another theme in their settings.

## Build and test

A project of its own on the parent of Mosaikit extensions: Java 25, Maven and Node.js 24.

```bash
mvn package    # target/<name>-<version>.zip, the package to install
mvn verify     # also the tests of tests/, on a Mosaikit installation started for them
npm run dev    # Mosaikit with this extension, until Ctrl+C
```
