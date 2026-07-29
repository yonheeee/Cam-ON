// PNG 리사이즈 (일회성 에셋 스크립트).
//
// 손모양 라인아트가 장당 700~900KB(1200~1450px)인데 화면에는 100px 이하로 그린다. 배포 첫 로딩에
// 6MB를 얹을 이유가 없어서 줄인다. 새 의존성(sharp/jimp)을 넣지 않으려고 Node 내장 zlib만 쓴다 —
// 8bit·non-interlaced·colorType 2(RGB)/6(RGBA)만 다루면 되므로 그 범위만 구현했다.
//
// 사용법: node scripts/resize-pngs.mjs <디렉터리> [최대변 px]
//
// ponytail: 박스 평균 축소만 구현(감마 보정 없음). 라인아트라 눈에 안 띈다 — 사진을 줄일 일이
// 생기면 sRGB→linear 변환을 넣거나 그때 sharp를 도입할 것.
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { inflateSync, deflateSync } from 'node:zlib';

const SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i += 1) {
    c ^= buf[i];
    for (let k = 0; k < 8; k += 1) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunks(buf) {
  const out = [];
  let p = 8;
  while (p < buf.length) {
    const len = buf.readUInt32BE(p);
    out.push({ type: buf.toString('ascii', p + 4, p + 8), data: buf.subarray(p + 8, p + 8 + len) });
    p += 12 + len;
  }
  return out;
}

/** PNG → {width, height, rgba} (rgba는 Uint8Array, 4채널) */
function decode(buf) {
  if (!buf.subarray(0, 8).equals(SIG)) throw new Error('PNG 시그니처 아님');
  const cs = chunks(buf);
  const ihdr = cs.find((c) => c.type === 'IHDR').data;
  const width = ihdr.readUInt32BE(0);
  const height = ihdr.readUInt32BE(4);
  const [bitDepth, colorType, , , interlace] = [ihdr[8], ihdr[9], ihdr[10], ihdr[11], ihdr[12]];
  if (bitDepth !== 8 || interlace !== 0 || (colorType !== 2 && colorType !== 6)) {
    throw new Error(`미지원 PNG (bit=${bitDepth} color=${colorType} interlace=${interlace})`);
  }
  const ch = colorType === 6 ? 4 : 3;
  const raw = inflateSync(Buffer.concat(cs.filter((c) => c.type === 'IDAT').map((c) => c.data)));

  const stride = width * ch;
  const px = Buffer.alloc(height * stride);
  let prev = Buffer.alloc(stride); // 첫 행의 위쪽은 0으로 취급
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const cur = px.subarray(y * stride, (y + 1) * stride);
    line.copy(cur);
    for (let i = 0; i < stride; i += 1) {
      const a = i >= ch ? cur[i - ch] : 0; // 왼쪽
      const b = prev[i]; // 위
      const c = i >= ch ? prev[i - ch] : 0; // 좌상
      if (filter === 1) cur[i] = (cur[i] + a) & 0xff;
      else if (filter === 2) cur[i] = (cur[i] + b) & 0xff;
      else if (filter === 3) cur[i] = (cur[i] + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        cur[i] = (cur[i] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff;
      } else if (filter !== 0) throw new Error(`미지원 필터 ${filter}`);
    }
    prev = cur;
  }

  // 항상 RGBA로 정규화 (RGB 입력은 알파 255)
  const rgba = new Uint8Array(width * height * 4);
  for (let i = 0, j = 0; i < width * height; i += 1, j += ch) {
    rgba[i * 4] = px[j];
    rgba[i * 4 + 1] = px[j + 1];
    rgba[i * 4 + 2] = px[j + 2];
    rgba[i * 4 + 3] = ch === 4 ? px[j + 3] : 255;
  }
  return { width, height, rgba };
}

/** 박스 평균 축소. 알파를 곱해 섞어서(premultiply) 투명 픽셀의 색이 새어나오지 않게 한다. */
function resize(src, w, h, dw, dh) {
  const dst = new Uint8Array(dw * dh * 4);
  for (let y = 0; y < dh; y += 1) {
    const y0 = Math.floor((y * h) / dh);
    const y1 = Math.max(y0 + 1, Math.floor(((y + 1) * h) / dh));
    for (let x = 0; x < dw; x += 1) {
      const x0 = Math.floor((x * w) / dw);
      const x1 = Math.max(x0 + 1, Math.floor(((x + 1) * w) / dw));
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      for (let sy = y0; sy < y1; sy += 1) {
        for (let sx = x0; sx < x1; sx += 1) {
          const i = (sy * w + sx) * 4;
          const al = src[i + 3] / 255;
          r += src[i] * al;
          g += src[i + 1] * al;
          b += src[i + 2] * al;
          a += src[i + 3];
          n += 1;
        }
      }
      const o = (y * dw + x) * 4;
      const am = a / n;
      const un = am > 0 ? 255 / am : 0;
      dst[o] = Math.min(255, Math.round((r / n) * un));
      dst[o + 1] = Math.min(255, Math.round((g / n) * un));
      dst[o + 2] = Math.min(255, Math.round((b / n) * un));
      dst[o + 3] = Math.round(am);
    }
  }
  return dst;
}

function encode(rgba, w, h) {
  const stride = w * 4;
  const raw = Buffer.alloc(h * (stride + 1));
  for (let y = 0; y < h; y += 1) {
    raw[y * (stride + 1)] = 0; // 필터 None — zlib이 충분히 줄여준다
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    SIG,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const dir = process.argv[2];
const maxSide = Number(process.argv[3] ?? 256);
if (!dir) {
  console.error('사용법: node scripts/resize-pngs.mjs <디렉터리> [최대변 px]');
  process.exit(1);
}

let before = 0;
let after = 0;
for (const name of readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.png'))) {
  const path = join(dir, name);
  const src = readFileSync(path);
  const { width, height, rgba } = decode(src);
  const scale = maxSide / Math.max(width, height);
  if (scale >= 1) {
    console.log(`${name}: ${width}x${height} — 이미 ${maxSide}px 이하, 건너뜀`);
    continue;
  }
  const dw = Math.max(1, Math.round(width * scale));
  const dh = Math.max(1, Math.round(height * scale));
  const out = encode(resize(rgba, width, height, dw, dh), dw, dh);
  writeFileSync(path, out);
  before += src.length;
  after += out.length;
  console.log(
    `${name}: ${width}x${height} ${(src.length / 1024) | 0}KB → ${dw}x${dh} ${(out.length / 1024) | 0}KB`,
  );
}
if (before) {
  console.log(`\n합계 ${(before / 1024) | 0}KB → ${(after / 1024) | 0}KB (${(100 - (after / before) * 100).toFixed(1)}% 감소)`);
}
