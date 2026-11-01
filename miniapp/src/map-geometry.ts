/** Map coordinates are GCJ-02. Browser GPS is WGS-84 and must be converted first. */
export interface Coordinate { longitude: number; latitude: number }
export interface Pixel { x: number; y: number }
const MAX_MERCATOR_LATITUDE = 85.05112878
const PI = Math.PI

export function parseCoordinate(value?: string | null): Coordinate | null {
  if (typeof value !== 'string') return null
  const pieces = value.split(',')
  if (pieces.length !== 2 || pieces.some(piece => !piece.trim())) return null
  const longitude = Number(pieces[0]), latitude = Number(pieces[1])
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude) || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) return null
  return { longitude, latitude }
}
export const formatCoordinate = ({ longitude, latitude }: Coordinate): string => `${longitude.toFixed(6)},${latitude.toFixed(6)}`
export function project(coordinate: Coordinate, zoom: number): Pixel {
  const size = 256 * 2 ** zoom
  const latitude = Math.max(-MAX_MERCATOR_LATITUDE, Math.min(MAX_MERCATOR_LATITUDE, coordinate.latitude)) * PI / 180
  return { x: (coordinate.longitude + 180) / 360 * size, y: (1 - Math.log(Math.tan(latitude) + 1 / Math.cos(latitude)) / PI) / 2 * size }
}
export function unproject(pixel: Pixel, zoom: number): Coordinate {
  const size = 256 * 2 ** zoom
  const x = ((pixel.x % size) + size) % size
  const y = Math.max(0, Math.min(size, pixel.y))
  return { longitude: x / size * 360 - 180, latitude: Math.atan(Math.sinh(PI * (1 - 2 * y / size))) * 180 / PI }
}
export function mapPoint(coordinate: Coordinate, center: Coordinate, zoom: number, width: number, height: number): Pixel {
  const point = project(coordinate, zoom), middle = project(center, zoom), world = 256 * 2 ** zoom
  let dx = point.x - middle.x
  if (dx > world / 2) dx -= world
  if (dx < -world / 2) dx += world
  return { x: width / 2 + dx, y: height / 2 + point.y - middle.y }
}
export function pointCoordinate(point: Pixel, center: Coordinate, zoom: number, width: number, height: number): Coordinate {
  const middle = project(center, zoom)
  return unproject({ x: middle.x + point.x - width / 2, y: middle.y + point.y - height / 2 }, zoom)
}

/** Standard GCJ-02 transformation; locations outside mainland China pass through. */
export function wgs84ToGcj02(coordinate: Coordinate): Coordinate {
  const { longitude, latitude } = coordinate
  if (longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271) return { ...coordinate }
  const x = longitude - 105, y = latitude - 35
  let dLatitude = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  dLatitude += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  dLatitude += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3
  dLatitude += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3
  let dLongitude = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  dLongitude += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3
  dLongitude += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3
  dLongitude += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3
  const radian = latitude / 180 * PI
  const magic = 1 - 0.00669342162296594323 * Math.sin(radian) ** 2
  const squareRoot = Math.sqrt(magic)
  dLatitude = dLatitude * 180 / ((6378245 * (1 - 0.00669342162296594323)) / (magic * squareRoot) * PI)
  dLongitude = dLongitude * 180 / (6378245 / squareRoot * Math.cos(radian) * PI)
  return { longitude: longitude + dLongitude, latitude: latitude + dLatitude }
}
