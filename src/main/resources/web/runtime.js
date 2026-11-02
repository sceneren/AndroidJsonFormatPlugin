// Rhino has BigInt/typed arrays, but not the browser TextDecoder or getBigInt64 API.
// These shims preserve the original binary-Protobuf decoder, including 64-bit precision.
if (typeof TextDecoder === 'undefined') {
    var TextDecoder = function () {};
    TextDecoder.prototype.decode = function (bytes) {
        var encoded = '';
        for (var i = 0; i < bytes.length; i++) encoded += '%' + ('0' + bytes[i].toString(16)).slice(-2);
        return decodeURIComponent(encoded); // throws on invalid UTF-8, like fatal:true
    };
}
if (!DataView.prototype.getBigInt64) {
    DataView.prototype.getBigInt64 = function (offset, littleEndian) {
        var low = this.getUint32(offset + (littleEndian ? 0 : 4), littleEndian);
        var high = this.getInt32(offset + (littleEndian ? 4 : 0), littleEndian);
        return BigInt(high) * BigInt('4294967296') + BigInt(low);
    };
}

function convertLog(raw, opts) {
    var parsed = L2K.parseLog(raw), byTag = new Map(), groups = [], count = 0;
    parsed.entries.forEach(function (entry) {
        var tag = entry.tag || '(无 tag)';
        if (!byTag.has(tag)) byTag.set(tag, []);
        byTag.get(tag).push(entry);
    });
    byTag.forEach(function (entries, tag) {
        var nodes = [];
        entries.forEach(function (entry) { nodes = nodes.concat(L2K.extractNodes(entry.content)); });
        if (!nodes.length && entries.length > 1) {
            var flat = L2K.mergeFlatProto(entries.map(function (entry) { return entry.content; }));
            if (flat) nodes.push(flat);
        }
        if (nodes.length) { count += nodes.length; groups.push({tag:tag, nodes:nodes}); }
    });
    var parts = [];
    groups.forEach(function (group) {
        if (groups.length > 1) parts.push('// ---- ' + group.tag + ' (' + group.nodes.length + ' 个) ----');
        group.nodes.forEach(function (node) { parts.push(L2K.stringifyNode(node, '  ', 0)); });
    });
    return {json:parts.join('\n\n'), kotlin:count ? L2K.genKotlin(groups, opts) : '',
        groups:groups, count:count, stats:parsed.stats};
}
