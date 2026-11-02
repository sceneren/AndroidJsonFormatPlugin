// Small, explicit downstream fixes. Keep original index.html untouched for provenance.
export function patchCore(source) {
    function replaceOnce(before, after) {
        if (source.split(before).length !== 2) throw new Error('Upstream patch anchor changed: ' + before);
        source = source.replace(before, after);
    }
    replaceOnce('  var PREFIX_PATTERNS = [', String.raw`  var PREFIX_PATTERNS = [
    // AS table selection with date and/or pid columns hidden (ApiCodec chunk output).
    {
      re: /^(?:(\d{4}-\d{2}-\d{2}|\d{2}-\d{2})\s+)?(\d{2}:\d{2}:\d{2}[.,]\d{3})\s+(?:(\d+)-(\d+)\s+)?(\S+)\s+(\S+)\s+([VDIWEFA])\s{2,}/,
      map: function (m) { return { ts: (m[1] ? m[1] + ' ' : '') + m[2], pid: m[3] || '', tid: m[4] || '', tag: m[5], pkg: m[6], level: m[7] }; }
    },`);
    replaceOnce('      var content = stripWrapper(p ? line.slice(p.matched) : line);', String.raw`      var content = p ? line.slice(p.matched) : line;
      var part = null;
      // A transport marker is metadata only directly after a recognized log prefix.
      // Consume exactly one separator so whitespace within a split string survives.
      if (p) {
        var chunk = /^\[(\d+)\/(\d+)\](?:[ \t]|$)/.exec(content);
        if (chunk && Number(chunk[1]) >= 1 && Number(chunk[1]) <= Number(chunk[2])) {
          part = { index: Number(chunk[1]), total: Number(chunk[2]) };
          content = content.slice(chunk[0].length);
        }
      }
      content = stripWrapper(content);`);
    replaceOnce('            if (st.inStr) {', String.raw`            // Numbered logger chunks are slices of one payload, not separate lines.
            // Preserve token bytes across consecutive chunks (numbers, literals, etc.).
            // Selection can start at the bare first payload, without its [1/N] prefix.
            var contiguousChunk = part && part.index > 1 && (last.part
              ? part.total === last.part.total && part.index === last.part.index + 1
              : part.index === 2);
            if (st.inStr || contiguousChunk) {`);
    replaceOnce('            last.content += sep + content;', '            last.content += sep + content;\n            last.part = part;');
    replaceOnce('        content: content', '        part: part,\n        content: content');
    replaceOnce("        var line = '    ' + ann + 'val '", String.raw`        var line = (ann ? '    ' + ann.trim() + '\n' : '') + '    val '`);
    return source;
}
