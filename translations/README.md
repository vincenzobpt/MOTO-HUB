# MOTO-HUB translation catalogues

Each file in this directory is a complete Android string-resource catalogue
for one locale. The filename is deliberately explicit: `strings-it-IT.xml`,
`strings-pt-PT.xml`, and so on.

## Translator rules

1. Translate the text between the XML tags, not the `name` attribute.
2. Keep every placeholder exactly as written (`%1$s`, `%2$d`, etc.).
3. Keep technical names such as `GPS`, `GNSS`, `OSM`, and `Android Auto` unless
   the target language has an established equivalent.
4. Read the comment immediately above each string: it describes the screen,
   widget, or notification where the text is shown and any space constraints.
5. Escape XML characters (`&amp;`, `&lt;`, `&gt;`) when they occur in translated
   text. Do not add HTML or Markdown.

The English catalogue is the fallback and the source of truth for identifiers.
A translated catalogue may omit an identifier while it is being worked on;
Android will then fall back to English for that individual string.

The Italian and Portuguese files include the complete current catalogue. The
Italian catalogue is translated and ready for review; Portuguese entries still
marked `TODO` are intentionally copied from English so a translator can fill
them in incrementally.

The Android build maps the locale tags in these filenames to Android resource
qualifiers automatically (`it-IT` becomes `values-it`, `pt-PT` becomes
`values-pt-rPT`, `ko-KR` becomes `values-ko-rKR`). A new locale must be added to both this directory and
`app/src/main/res/xml/locales_config.xml`.
