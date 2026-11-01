// Code-owned line icons, rendered as PNG for native WeChat <image> compatibility.
// Run with sharp available in NODE_PATH; generated assets are checked in.
import { createRequire } from 'node:module'
import { copyFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
const require = createRequire(import.meta.url)
const sharp = require('sharp')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const shapes = {
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-8H9v8H4a1 1 0 0 1-1-1Z"/>',
  orders: '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 7h6M9 11h6M9 15h4"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2 21v-2a7 7 0 0 1 14 0v2M16 4a3.5 3.5 0 0 1 0 7M18 14a6 6 0 0 1 4 5v2"/>',
  car: '<path d="m5 6-2 7v6h2v-3h14v3h2v-6l-2-7a2 2 0 0 0-2-1H7a2 2 0 0 0-2 1ZM3 12h18M7 12V9h10v3"/><path d="M6 15h1M17 15h1"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  'map-pin': '<path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 0 1 14 0Z"/><circle cx="12" cy="10" r="2.5"/>',
  'chevron-right': '<path d="m9 5 7 7-7 7"/>',
  'chevron-left': '<path d="m15 5-7 7 7 7"/>',
  'chevron-down': '<path d="m5 9 7 7 7-7"/>',
  phone: '<path d="M5 3h4l2 5-3 2a15 15 0 0 0 6 6l2-3 5 2v4a2 2 0 0 1-2 2A18 18 0 0 1 3 5a2 2 0 0 1 2-2Z"/>',
  message: '<path d="M21 11a9 9 0 0 1-9 9H4l-2 2 1-7a9 9 0 1 1 18-4Z"/><path d="M8 10h8M8 14h5"/>',
  address: '<path d="M4 4h15v17H4Z M7 1v6M16 1v6"/><circle cx="11.5" cy="11" r="2"/><path d="M8 17v-1a3.5 3.5 0 0 1 7 0v1"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m9 11 6-4M9 13l6 4"/>',
  wallet: '<path d="M20 8V5a1 1 0 0 0-1-1H5a3 3 0 0 0 0 6h16v10H5a3 3 0 0 1-3-3V7M21 13h-5v4h5"/>',
  settings: '<path d="m10 2-1 3-3 1-3 1 1 4v2l-1 4 3 1 3 1 1 3h4l1-3 3-1 3-1-1-4v-2l1-4-3-1-3-1-1-3Z"/><circle cx="12" cy="12" r="3"/>',
  shield: '<path d="m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6Z"/><path d="m8 12 3 3 5-6"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9 9a3 3 0 0 1 6 0c0 2-3 2-3 5M12 17h.01"/>',
  logout: '<path d="M9 3H4v18h5M9 12h12m-5-5 5 5-5 5"/>',
  plus: '<path d="M12 4v16M4 12h16"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  location: '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>',
  close: '<path d="m5 5 14 14M19 5 5 19"/>',
  check: '<path d="m4 12 5 5L20 6"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 2v6M17 2v6M3 11h18M7 15h3M14 15h3"/>',
  refresh: '<path d="M20 7v5h-5M4 17v-5h5M20 12a8 8 0 0 0-14-5M4 12a8 8 0 0 0 14 5"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
  edit: '<path d="m16 3 5 5-13 13H3v-5ZM13 6l5 5"/>',
  bell: '<path d="M4 17h16l-2-4V9a6 6 0 0 0-12 0v4ZM9 21h6"/>',
  'arrow-up-right': '<path d="M6 18 18 6M6 6h12v12"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 4V3H3v13h1"/>',
  'check-circle': '<circle cx="12" cy="12" r="9"/><path d="m7 12 3 3 7-7"/>',
  accessibility: '<circle cx="12" cy="3.5" r="1.5"/><path d="M4 8h16M12 8v6M12 14l-5 7M12 14l5 7"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>',
}
const colors = { normal: '#1f2329', muted: '#646a73', brand: '#16845b', white: '#ffffff', danger: '#d92d20' }
const output = path.join(root, 'miniapp/src/static/icons')
await mkdir(output, { recursive: true })
for (const [name, shape] of Object.entries(shapes)) {
  for (const [tone, color] of Object.entries(colors)) {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${shape}</svg>`
    await sharp(Buffer.from(svg)).png().toFile(path.join(output, `${name}-${tone}.png`))
  }
}
// A generic, code-owned share image. It contains no operator identity or uploaded brand artwork.
const brandLogo = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 128 128"><rect width="128" height="128" rx="24" fill="#edf6f1"/><g transform="translate(24 24) scale(3.333333)" fill="none" stroke="#16845b" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${shapes.car}</g></svg>`
await sharp(Buffer.from(brandLogo)).png().toFile(path.join(root, 'miniapp/src/static/brand-logo.png'))
await copyFile(path.join(root, 'miniapp/src/static/brand-logo.png'), path.join(root, 'backend/src/main/resources/brand-logo.png'))
console.log(`Generated ${Object.keys(shapes).length} icons in ${Object.keys(colors).length} tones.`)
