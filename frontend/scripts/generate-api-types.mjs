// Génère src/app/core/api-types.ts à partir de docs/openapi.json (exporté depuis le backend).
// Usage : node scripts/generate-api-types.mjs
import { readFileSync, writeFileSync } from 'node:fs';

const spec = JSON.parse(readFileSync(new URL('../../docs/openapi.json', import.meta.url)));
const schemas = spec.components.schemas;

function tsType(s) {
  if (!s) return 'unknown';
  if (s.$ref) return s.$ref.split('/').pop();
  if (s.enum) return s.enum.map((v) => JSON.stringify(v)).join(' | ');
  if (s.oneOf || s.anyOf) return (s.oneOf || s.anyOf).map(tsType).join(' | ');
  switch (s.type) {
    case 'string':
      // Les montants (BigDecimal) sont sérialisés en nombres entiers XOF ; les dates restent des chaînes ISO.
      return 'string';
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'array':
      return `${wrap(tsType(s.items))}[]`;
    case 'object':
      if (s.properties) return inline(s);
      if (s.additionalProperties && s.additionalProperties !== true)
        return `Record<string, ${tsType(s.additionalProperties)}>`;
      return 'Record<string, unknown>';
    default:
      return 'unknown';
  }
}
const wrap = (t) => (t.includes('|') ? `(${t})` : t);
function inline(s) {
  const req = new Set(s.required || []);
  const props = Object.entries(s.properties || {}).map(
    ([k, v]) => `${k}${req.has(k) ? '' : '?'}: ${tsType(v)};`,
  );
  return `{ ${props.join(' ')} }`;
}

let out = '/* Fichier généré par scripts/generate-api-types.mjs : ne pas modifier à la main. */\n/* eslint-disable */\n\n';
for (const [name, s] of Object.entries(schemas).sort(([a], [b]) => a.localeCompare(b))) {
  if (s.type === 'object' || s.properties) {
    const req = new Set(s.required || []);
    out += `export interface ${name} {\n`;
    for (const [k, v] of Object.entries(s.properties || {})) {
      out += `  ${/^[a-zA-Z_$][\w$]*$/.test(k) ? k : JSON.stringify(k)}${req.has(k) ? '' : '?'}: ${tsType(v)};\n`;
    }
    out += '}\n\n';
  } else {
    out += `export type ${name} = ${tsType(s)};\n\n`;
  }
}
writeFileSync(new URL('../src/app/core/api-types.ts', import.meta.url), out);
console.log(`${Object.keys(schemas).length} types générés`);
