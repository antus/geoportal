<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# The style grammar geoportal/style/v1

The style of every layer of Geoportal is a JSON document of this grammar (GP-009,
[ADR-0029](../adr/0029-layer-styles-are-documents-of-a-json-grammar.md)), not the internal state of an
editor. The style editor is a facade over it; the catalog keeps it and translates it into SLD for
GeoServer; the maps translate it into MapLibre styles for GeoLibre. Because it is plain JSON, a style
is copied between layers, maps and installations as a file.

| File | What |
|---|---|
| [`geoportal-style-v1.schema.json`](geoportal-style-v1.schema.json) | The JSON Schema (draft 2020-12) of a document |
| [`examples/`](examples) | Documents of every block and kind of value; the tests check that each is valid and translates |
| `extensions/ext-catalog-geodata/web/style-grammar.js` | The grammar in the browser: checks, change of type, the panel of the editor, the MapLibre adapter |
| `extensions/ext-catalog-geodata/src/main/java/.../style/` | The grammar in the catalog: checks on save, change of type, migration, SLD |

## A document

```json
{
  "grammar": "geoportal/style/v1",
  "type": "point",
  "config": { "shape": "triangle" },
  "style": {
    "fill": { "field": "livello", "graduated": { "breaks": [1.5, 3], "values": ["#2a9d8f", "#e9c46a", "#e76f51"] } },
    "fillOpacity": 1,
    "stroke": "#264653",
    "strokeWidth": 1,
    "size": { "field": "portata", "scaled": { "from": [0, 500], "to": [6, 24] } }
  },
  "label": { "text": { "template": "{nome} ({livello} m)" }, "color": "#264653", "size": 10 },
  "legend": { "title": "Stazioni idrometriche" }
}
```

Its blocks:

| Block | Required | What |
|---|---|---|
| `grammar` | yes | always `geoportal/style/v1`; a later grammar has another name |
| `type` | yes | `point`, `line`, `polygon`, or `mixed` for a layer of several geometries |
| `config` | for point, line, polygon | what is not a paint: the shape of a point, the dashes of a line |
| `style` | for point, line, polygon | the paint: colours, opacities, widths, sizes |
| `geometries` | for mixed | `point`, `line` and `polygon`, each with its `config` and `style` |
| `label` | no | a text drawn on each feature |
| `legend` | no | `title` (up to 200 characters) and `show` (true or false) of the legend |
| `$schema` | no | the schema, for editors that read it; it means nothing else |

Nothing else is allowed in a document or in its blocks: a property the grammar does not know is an
error, not something kept and ignored.

### config and style of each type

| Type | config | style |
|---|---|---|
| `point` | `shape`: `circle`, `square`, `triangle`, `star`, `cross` or `x` | `fill`, `fillOpacity`, `stroke`, `strokeWidth`, `size` |
| `line` | `dash` (optional): 2 to 8 numbers from 0.5 to 64, pixels of dash and gap alternately | `stroke`, `strokeOpacity`, `strokeWidth` |
| `polygon` | nothing | `fill`, `fillOpacity`, `stroke`, `strokeWidth` |

Every property of `style` is required. Colours are `#rrggbb`; opacities go from 0 to 1; `strokeWidth`
from 0 to 64 pixels; `size` (the side of the symbol of a point) from 1 to 64 pixels. The `stroke` of a
point or a polygon is its outline; that of a line is the line.

### label

| Property | Required | What |
|---|---|---|
| `text` | yes | a constant text (1 to 200 characters), `{"field": "nome"}`, or `{"template": "{nome} ({quota} m)"}` |
| `color` | yes | a colour value |
| `size` | yes | a value from 6 to 72 pixels |
| `halo` | no | `{"color": "#ffffff", "width": 1.5}`, width from 0 to 10 pixels |

A template is the expression of the grammar: its `{field}` are the values of the data, everything
else is written as it is; a brace is only ever part of a `{field}`.

## Values driven by the data

One rule: **where there is a field, the value comes from the data.** Every colour, opacity, width and
size, of `style` and of `label`, is a constant or an object with `field`:

| Value | Meaning |
|---|---|
| `"#e76f51"`, `0.7` | the constant |
| `{"field": "colore"}` | the value of the attribute, as it is |
| `{"field": "uso", "categories": [{"equals": "verde", "value": "#2a9d8f", "label": "Verde"}], "otherwise": "#dddddd"}` | the value of the category whose `equals` (a text, a number, true or false) is the attribute; `otherwise` for the others and for features without a value |
| `{"field": "pop", "graduated": {"breaks": [1000, 5000], "values": ["#fee", "#f88", "#c00"], "labels": ["piccoli", "medi", "grandi"]}}` | the value of the class the attribute falls in: below the first break, between two breaks (the lower one included), from the last one; features without a number take the first class |
| `{"field": "portata", "scaled": {"from": [0, 500], "to": [6, 24]}}` | a linear interpolation of numbers or colours, held at the ends |

The outputs (`value`, `otherwise`, `values`, `to`) are constants of the same kind as the property.
`label` of a category and `labels` of the classes are the texts of the legend (up to 100 characters).

### What the schema cannot say

The implementations check these too, and refuse a document that breaks them:

- the `breaks` of a graduated value are increasing, from 1 to 50 of them;
- there are as many `values` as classes (breaks + 1), and as many `labels` when there are labels;
- no two categories have the same `equals`;
- `from` of a scaled value goes from the lower number to the higher.

A problem is reported with where it is, as a JSON pointer (`/style/fill/graduated/breaks`), and what
is expected.

## Changing the type

Changing the type of a document keeps what has the same meaning in the new type. The **colour** of a
symbol is the fill of a point or a polygon and the stroke of a line; its **opacity** goes with the
colour; its **weight** is the size of a point and the stroke width of a line or a polygon.

| From → to | What becomes what |
|---|---|
| point → line | colour, opacity and weight: the size of the point becomes the width of the line |
| line → point | colour, opacity; the width becomes the size (at least 1); the outline is the colour, 1 px wide; a circle |
| point ↔ polygon | fill, its opacity, outline and its width; a point gets size 8 and a circle |
| line → polygon | the colour fills it and outlines it, the width is the outline |
| polygon → line | colour, opacity and weight: the fill becomes the line, the outline width its width |
| any → mixed | each geometry gets the symbol of its own type, the original stays as it is |
| mixed → any | the symbol of that geometry |

`label` and `legend` always stay; values driven by the data move with what they mean, their numbers
held in the range of the new property. The catalog uses the same rules when a layer is drawn on data
of another geometry than its style: a point style on a layer of lines draws the line it becomes.

## Translations

**SLD 1.0, for GeoServer** (the catalog): a constant is a literal; a field is an `ogc:PropertyName`; a
scaled value is the function `Interpolate`; categories and graduated classes become one rule per
class, with the filter of the class and its legend text as the title of the rule (several classified
values make the rules of their combinations, at most 500); the label is a `TextSymbolizer`, along the
line for lines. A layer of mixed geometries has the rules of each geometry, told apart by
`dimension(geom)`. The legend title is the title of the style.

**MapLibre, for GeoLibre** (`toMapLibre` of `style-grammar.js`): a field is `get`, categories
`case`, graduated classes `step`, a scaled value `interpolate`; a circle is a `circle` layer, the other
shapes a `symbol` layer with the image `geoportal-shape-<shape>` that `shapeImage(shape)` draws as a
signed distance field; a polygon is a `fill` layer and a `line` layer for the outline; a mixed layer
filters each geometry with `geometry-type`. Dashes are converted from pixels to line widths. Other
engines (deck.gl) are adapters of the same kind, registered in `adapters`.

Both draw the fill of a point at least 5% opaque and a line at least half a pixel wide, as the catalog
did before the grammar.

## The styles of before the grammar

Until GP-009 the catalog kept six values for every geometry: `fill`, `fillOpacity`, `stroke`,
`strokeWidth`, `pointSize`, `pointShape`. The migration V14 of the catalog turns each of them into a
`mixed` document that keeps every value (the line takes the stroke, opaque) and translates into the
same SLD for every geometry; the catalog and the editor read the old form as the same document, and
the API still takes it from clients that send it.

## The editor

The style editor of the publishing dialog shows the type, the fields of that type (the colour,
opacity and width of a line; fill, outline and size of a point; and so on) and the label (an
attribute, its colour and size), and edits the document under them; the document is shown as JSON
next to the SLD it becomes, and exported or imported as a file. Editor → document → editor gives back
the same values. A document with values the panel does not show (driven by the data, a template,
geometries of a mixed style that differ) is edited as JSON, and the editor says so.

## Versions

A change that a document of v1 could not satisfy (a property becoming required, a value changing
meaning) is a new grammar, `geoportal/style/v2`, with its schema and a migration from v1. An optional
property may be added to v1 when the schema and both implementations learn it in the same change.
