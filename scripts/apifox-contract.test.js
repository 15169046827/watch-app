const test = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync, existsSync } = require('node:fs')
const { join } = require('node:path')

const root = join(__dirname, '..')
const json = relative => JSON.parse(readFileSync(join(root, relative), 'utf8'))
const layout = json('docs/api/apifox-layout.json')
const wolaiOpenapi = json('docs/api/wolai-openapi.json')
const edgeUpdateOpenapi = json('docs/api/edge-update-openapi.json')
const manifest = json('docs/api/apifox-manifest.json')
const operations = json('docs/api/apifox-operations.json')
const helper = readFileSync(join(root, 'scripts/apifox-mcp.ps1'), 'utf8')
const sync = readFileSync(join(root, 'scripts/sync-apifox-api.ps1'), 'utf8')

test('catalog covers every maintained communication class', () => {
  assert.equal(layout.folders.length, 24)
  assert.equal(layout.interfaces.length, 40)
  assert.equal(layout.documents.length, 2)
  assert.equal(new Set(layout.interfaces.map(item => item.path)).size, 40)
  const protocols = new Set(layout.interfaces.map(item => item.protocol))
  for (const expected of ['BLE GATT', 'CLI', 'Tauri IPC', 'CLI JSON', 'CLI JSONL', 'Worker IPC', 'WebSocket', 'File JSON', 'iCalendar', 'Intent URI', 'Android']) {
    assert.ok(protocols.has(expected), `missing ${expected}`)
  }
  assert.deepEqual(layout.httpCatalogs.map(item => item.key), ['wolai', 'edge-update'])
})

test('Wolai integration is represented as real HTTP endpoints', () => {
  assert.equal(wolaiOpenapi.servers[0].url, 'https://openapi.wolai.com/v1')
  assert.deepEqual(Object.keys(wolaiOpenapi.paths).sort(), ['/blocks', '/blocks/{pageId}/children', '/databases/{databaseId}'].sort())
  assert.ok(wolaiOpenapi.paths['/blocks'].post)
  assert.ok(wolaiOpenapi.paths['/blocks/{pageId}/children'].get)
  assert.ok(wolaiOpenapi.paths['/databases/{databaseId}'].get)
  assert.equal(wolaiOpenapi.components.securitySchemes.bearerAuth.scheme, 'bearer')
  assert.doesNotMatch(JSON.stringify(wolaiOpenapi), /Bearer\s+[A-Za-z0-9_-]{16,}/)
})

test('Edge updater dependencies are represented as real HTTP endpoints', () => {
  assert.deepEqual(Object.keys(edgeUpdateOpenapi.paths).sort(), [
    '/packages/{shard1}/{shard2}/{digest}/{filename}', '/pypi/edge-tts/json'
  ].sort())
  assert.equal(edgeUpdateOpenapi.paths['/pypi/edge-tts/json'].get.servers[0].url, 'https://pypi.org')
  assert.equal(edgeUpdateOpenapi.paths['/packages/{shard1}/{shard2}/{digest}/{filename}'].get.servers[0].url, 'https://files.pythonhosted.org')
  assert.doesNotMatch(JSON.stringify(edgeUpdateOpenapi), /Authorization|Bearer|token/i)
})

test('project binding and write operations are narrowly scoped', () => {
  assert.equal(manifest.projectId, 8948095)
  assert.equal(manifest.branchId, 8751743)
  assert.equal(manifest.moduleId, 8741710)
  assert.equal(operations.projectId, 8948095)
  assert.ok(operations.operations.every(item => !/delete/i.test(`${item.method} ${item.purpose}`)))
  assert.match(helper, /https:\/\/api\.apifox\.com\/mcp/)
  assert.match(helper, /MaximumRedirection 0/)
  assert.match(helper, /ApiFox_MCP_TOKEN/)
  assert.doesNotMatch(helper, /ApiFox_MCP_TOKEN\s*=\s*['"][^'"]+['"]/);
  assert.match(sync, /param\(\[switch\]\$Apply, \[switch\]\$RecoverImportedHttp\)/)
  assert.match(sync, /entityId = \$matches\[0\]\.entityId/)
  assert.match(sync, /Write outcome is unclear|creation outcome is unclear/i)
})

test('every cited repository source exists', () => {
  const required = [
    'X3VoiceLedger/docs/BLE_SYNC.md',
    'X3VoiceLedger/docs/DATA_LIFECYCLE.md',
    'X3WolaiNotes/docs/DATA_LIFECYCLE.md',
    'X3OfflineReader/specification/OPEN_FORMAT.md',
    'X3OfflineReader/specification/DATA_LIFECYCLE.md',
    'X3Schedule/specification/PROTOCOL.md',
    'X3Schedule/specification/BACKUP.md',
    'X3Schedule/domain/src/main/java/com/canace/x3schedule/domain/Ics.java',
    'X3VoiceLedger/phone/src/main/java/com/canace/x3voiceledger/phone/YimuImporter.java',
    'X3OfflineReader/builder/src/main/java/com/canace/x3offlinereader/builder/BuilderBridge.java',
    'X3OfflineReader/builder/src/main/java/com/canace/x3offlinereader/builder/BuilderMain.java',
    'X3OfflineReader/builder/edge/updater.py'
  ]
  for (const relative of required) assert.ok(existsSync(join(root, relative)), relative)
})
