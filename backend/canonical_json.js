/**
 * Wasti AI OS - Canonical JSON Serializer for Node.js
 * 
 * Generates bit-for-bit identical JSON strings to Python's json.dumps(obj, sort_keys=True).
 * 
 * Rules:
 * 1. Recursively sorts dictionary/object keys in lexicographical order.
 * 2. Formats key-value pairs with ": " (colon + space) and items with ", " (comma + space).
 * 3. Escapes quotes, backslashes, control characters (< 0x20), and non-ASCII characters (>= 0x7F)
 *    using standard \uXXXX hexadecimal notation matching Python's default ensure_ascii=True.
 * 4. Outputs booleans as "true"/"false", null as "null", and finite numbers in standard format.
 */

function escapeStringPythonStyle(str) {
  let out = '"';
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    if (code === 34) { // "
      out += '\\"';
    } else if (code === 92) { // \
      out += '\\\\';
    } else if (code === 8) { // \b
      out += '\\b';
    } else if (code === 12) { // \f
      out += '\\f';
    } else if (code === 10) { // \n
      out += '\\n';
    } else if (code === 13) { // \r
      out += '\\r';
    } else if (code === 9) { // \t
      out += '\\t';
    } else if (code < 32 || code >= 127) {
      out += '\\u' + code.toString(16).padStart(4, '0');
    } else {
      out += str[i];
    }
  }
  out += '"';
  return out;
}

function canonicalJsonString(val) {
  if (val === null) return 'null';
  if (typeof val === 'boolean') return val ? 'true' : 'false';
  if (typeof val === 'number') {
    if (!Number.isFinite(val)) return 'null';
    return String(val);
  }
  if (typeof val === 'string') {
    return escapeStringPythonStyle(val);
  }
  if (Array.isArray(val)) {
    return '[' + val.map(canonicalJsonString).join(', ') + ']';
  }
  if (typeof val === 'object') {
    const keys = Object.keys(val).sort();
    const pairs = keys.map(k => escapeStringPythonStyle(k) + ': ' + canonicalJsonString(val[k]));
    return '{' + pairs.join(', ') + '}';
  }
  return JSON.stringify(val);
}

module.exports = {
  canonicalJsonString,
  escapeStringPythonStyle
};
