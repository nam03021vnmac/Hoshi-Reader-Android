import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import * as pdfjs from '../../main/assets/hoshi-web/pdfjs/pdf.min.mjs';

const vendor = new URL('../../main/assets/hoshi-web/pdfjs/', import.meta.url);
pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdf.worker.min.mjs', vendor).href;

// Generated fixture: no ignored local testdata or external downloads.
function pdfData({ japanese = false, rotation = 0 } = {}) {
  const stream = `BT /F1 12 Tf 20 100 Td ${japanese ? '<732B>' : '(Hoshi PDF)'} Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Rotate ${rotation} /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>`,
    japanese
      ? '<< /Type /Font /Subtype /Type0 /BaseFont /HeiseiMin-W3 /Encoding /UniJIS-UTF16-H /DescendantFonts [6 0 R] >>'
      : '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ];
  if (japanese) objects.push(
    '<< /Type /Font /Subtype /CIDFontType0 /BaseFont /HeiseiMin-W3 /CIDSystemInfo << /Registry (Adobe) /Ordering (Japan1) /Supplement 6 >> /FontDescriptor 7 0 R /DW 1000 >>',
    '<< /Type /FontDescriptor /FontName /HeiseiMin-W3 /Flags 4 /FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 900 /Descent -200 /CapHeight 700 /StemV 80 >>',
  );
  let data = '%PDF-1.7\n';
  const offsets = [0];
  objects.forEach((object, index) => {
    offsets.push(data.length);
    data += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = data.length;
  data += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  data += offsets.slice(1).map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  data += `trailer\n<< /Size ${offsets.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new Uint8Array(Buffer.from(data, 'ascii'));
}

test('vendored API and worker agree on version and extract generated PDF text', async () => {
  assert.equal(pdfjs.version, '4.10.38');
  assert.equal(pdfjs.build, 'f9bea397f');
  const task = pdfjs.getDocument({ data: pdfData({ rotation: 90 }), isEvalSupported: false, useSystemFonts: true });
  try {
    const pdf = await task.promise;
    assert.equal(pdf.numPages, 1);
    const page = await pdf.getPage(1);
    const viewport = page.getViewport({ scale: pdfjs.PixelsPerInch.PDF_TO_CSS_UNITS });
    assert.equal(viewport.rotation, 90);
    assert.equal(viewport.width, 400);
    const text = await page.getTextContent();
    assert.equal(text.items.map((item) => item.str || '').join(''), 'Hoshi PDF');
  } finally {
    await task.destroy();
  }
});

test('vendored packed Japanese CMaps resolve to Unicode offline', async () => {
  const task = pdfjs.getDocument({
    data: pdfData({ japanese: true }),
    cMapUrl: fileURLToPath(new URL('cmaps/', vendor)),
    cMapPacked: true,
    isEvalSupported: false,
    useSystemFonts: true,
  });
  try {
    const pdf = await task.promise;
    const page = await pdf.getPage(1);
    const text = await page.getTextContent();
    assert.equal(text.items.map((item) => item.str || '').join(''), '\u732b');
  } finally {
    await task.destroy();
  }
});

test('standard fonts and their third-party license files are available for offline packaging', () => {
  for (const name of [
    'FoxitDingbats.pfb', 'FoxitFixed.pfb', 'FoxitFixedBold.pfb', 'FoxitFixedBoldItalic.pfb',
    'FoxitFixedItalic.pfb', 'FoxitSerif.pfb', 'FoxitSerifBold.pfb', 'FoxitSerifBoldItalic.pfb',
    'FoxitSerifItalic.pfb', 'FoxitSymbol.pfb', 'LiberationSans-Bold.ttf',
    'LiberationSans-BoldItalic.ttf', 'LiberationSans-Italic.ttf', 'LiberationSans-Regular.ttf',
    'LICENSE_FOXIT', 'LICENSE_LIBERATION',
  ]) assert.ok(fs.statSync(new URL(`standard_fonts/${name}`, vendor)).size > 0, name);
  assert.ok(fs.statSync(new URL('cmaps/LICENSE', vendor)).size > 0);
});
