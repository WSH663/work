import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

// Run with `node scripts/check-map-geometry.mjs` from miniapp/. No browser or API keys needed.
const source = await readFile(new URL('../src/map-geometry.ts', import.meta.url), 'utf8')
const javascript = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ES2022 } }).outputText
const { parseCoordinate, formatCoordinate, wgs84ToGcj02, project, unproject, mapPoint, pointCoordinate } = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString('base64')}`)
const close = (actual, expected, tolerance = 1e-7) => assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} differs from ${expected}`)

assert.equal(parseCoordinate('0,'), null)
assert.equal(parseCoordinate('NaN,38'), null)
assert.equal(parseCoordinate('181,38'), null)
assert.equal(parseCoordinate('114,91'), null)
assert.deepEqual(parseCoordinate('114.5,38'), { longitude: 114.5, latitude: 38 })
assert.equal(formatCoordinate({ longitude: 114.5, latitude: 38 }), '114.500000,38.000000')

// Known mainland transformation and passthrough guard against GPS/map offset mistakes.
const beijing = wgs84ToGcj02({ longitude: 116.404, latitude: 39.915 })
close(beijing.longitude, 116.41024449916938)
close(beijing.latitude, 39.91640428150164)
assert.deepEqual(wgs84ToGcj02({ longitude: -0.1276, latitude: 51.5072 }), { longitude: -0.1276, latitude: 51.5072 })

const center = { longitude: 116.404, latitude: 39.915 }
for (const zoom of [1, 10, 15, 17]) {
  const roundtrip = unproject(project(center, zoom), zoom)
  close(roundtrip.longitude, center.longitude)
  close(roundtrip.latitude, center.latitude)
  for (const point of [{ x: 0, y: 0 }, { x: 187.5, y: 125 }, { x: 350, y: 220 }]) {
    const coordinate = pointCoordinate(point, center, zoom, 375, 250)
    const pixel = mapPoint(coordinate, center, zoom, 375, 250)
    close(pixel.x, point.x)
    close(pixel.y, point.y)
  }
}
// A rightward drag moves the center west; clicking right of center selects east.
const middle = project(center, 15)
assert.ok(unproject({ x: middle.x - 70, y: middle.y }, 15).longitude < center.longitude)
assert.ok(pointCoordinate({ x: 250, y: 125 }, center, 15, 375, 250).longitude > center.longitude)
assert.ok(Number.isFinite(project({ longitude: 0, latitude: 90 }, 15).y))
const acrossDateLine = mapPoint({ longitude: -179.99, latitude: 0 }, { longitude: 179.99, latitude: 0 }, 15, 375, 250)
assert.ok(acrossDateLine.x > 187.5 && acrossDateLine.x < 800)
console.log('Map geometry verified: coordinate validation, WGS84→GCJ02, Mercator roundtrips, click/drag direction, poles and date-line wrapping.')
