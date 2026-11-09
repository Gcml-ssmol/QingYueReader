const fs = require('fs');
const path = require('path');
// Reproduce the web-only horizontal font from the Android OCR font.
// Usage: node font-preparation-reference.cjs input.ttf output.ttf
if (process.argv.length !== 4) {
  console.error('Usage: node font-preparation-reference.cjs input.ttf output.ttf');
  process.exit(1);
}
const source = fs.readFileSync(path.resolve(process.argv[2]));
const entries = [];
for (let i = 0, n = source.readUInt16BE(4); i < n; i++) {
  const p = 12 + i * 16;
  const tag = source.toString('ascii', p, p + 4);
  if (tag === 'vhea' || tag === 'vmtx') continue;
  const start = source.readUInt32BE(p + 8), length = source.readUInt32BE(p + 12);
  entries.push({tag, data: Buffer.from(source.subarray(start, start + length))});
}
entries.sort((a,b) => a.tag.localeCompare(b.tag, 'en'));
const num = entries.length, pow = 2 ** Math.floor(Math.log2(num));
let offset = 12 + 16 * num;
const out = Buffer.alloc(offset + entries.reduce((s, e) => s + ((e.data.length + 3) & ~3), 0));
source.copy(out, 0, 0, 4);
out.writeUInt16BE(num, 4);
out.writeUInt16BE(pow * 16, 6);
out.writeUInt16BE(Math.log2(pow), 8);
out.writeUInt16BE(16 * (num - pow), 10);
function sum(data) { let s=0; for(let i=0;i<data.length;i+=4)s=(s+data.readUInt32BE(i))>>>0;return s; }
let headOffset;
for (let i=0;i<num;i++) {
  const e=entries[i], p=12+i*16;
  out.write(e.tag,p,4,'ascii');
  if(e.tag==='head'){e.data.writeUInt32BE(0,8);headOffset=offset;}
  out.writeUInt32BE(sum(Buffer.concat([e.data,Buffer.alloc((-e.data.length)&3)])),p+4);
  out.writeUInt32BE(offset,p+8);
  out.writeUInt32BE(e.data.length,p+12);
  e.data.copy(out,offset); offset+=(e.data.length+3)&~3;
}
out.writeUInt32BE((0xB1B0AFBA-sum(out))>>>0,headOffset+8);
const target=path.resolve(process.argv[3]);
fs.writeFileSync(target,out);
console.log('font bytes='+out.length,'output='+target);
