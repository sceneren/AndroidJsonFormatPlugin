const fs = require('fs');
const path = require('path');
const Module = require('module');
const filename = path.resolve(__dirname, '../src/main/resources/web/test.cjs');
const test = new Module(filename, module);
test.filename = filename;
test.paths = module.paths;
test._compile(fs.readFileSync(path.resolve(__dirname, '../vendor/upstream-test.cjs'), 'utf8'), filename);
