import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const size = 256
const rows = []
for (let y = 0; y < size; y++) {
  const row = [0]
  for (let x = 0; x < size; x++) {
    const inner = x > 48 && x < 208 && y > 48 && y < 208
    row.push(...(inner ? [255, 255, 255] : [36, 87, 197]))
  }
  rows.push(Buffer.from(row))
}
const raw = Buffer.concat(rows)
const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}
const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
])
const out = resolve(dirname(fileURLToPath(import.meta.url)), '../resources/icon.png')
mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, png)
console.log(`wrote ${out} (${png.length} bytes)`)

const ico = Buffer.alloc(6 + 16)
ico.writeUInt16LE(0, 0); ico.writeUInt16LE(1, 2); ico.writeUInt16LE(1, 4)
ico[6] = 0; ico[7] = 0            // 256 px is encoded as 0
ico[8] = 0; ico[9] = 0            // colour count, reserved
ico.writeUInt16LE(1, 10); ico.writeUInt16LE(32, 12)
ico.writeUInt32LE(png.length, 14); ico.writeUInt32LE(22, 18)
const icoOut = resolve(dirname(fileURLToPath(import.meta.url)), '../resources/icon.ico')
writeFileSync(icoOut, Buffer.concat([ico, png]))
console.log(`wrote ${icoOut} (${ico.length + png.length} bytes)`)
