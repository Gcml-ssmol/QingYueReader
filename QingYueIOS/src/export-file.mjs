const CHUNK_BYTES = 256 * 1024;
const MAX_BYTES = 105 * 1024 * 1024;
const ROOT = 'QingYueExports';

function safeName(value) {
  if (typeof value !== 'string') throw new TypeError('文件名必须是字符串');
  const leaf = value.normalize('NFC').replace(/[\\/]/g, '_')
    .replace(/[\u0000-\u001f\u007f<>:"|?*\u202a-\u202e\u2066-\u2069]/g, '_')
    .replace(/[. ]+$/g, '').trim();
  if (!leaf || leaf === '.' || leaf === '..') throw new Error('文件名无效');
  const match = /^(.*?)(\.[A-Za-z0-9]{1,16})$/.exec(leaf);
  const extension = match?.[2] || '';
  const stem = (match?.[1] || leaf).slice(0, 120 - extension.length).replace(/[. ]+$/g, '');
  if (!stem) throw new Error('文件名无效');
  return stem + extension;
}

function base64(bytes) {
  if (typeof Buffer !== 'undefined') return Buffer.from(bytes).toString('base64');
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

export function createExporter({filesystem, share, directory, makeId = () => crypto.randomUUID()}) {
  if (!filesystem?.mkdir || !filesystem?.writeFile || !filesystem?.appendFile ||
      !filesystem?.getUri || !filesystem?.rmdir || !share?.share || !directory ||
      typeof makeId !== 'function') throw new TypeError('导出器缺少文件系统或分享服务');
  let saving = false;

  async function saveBlob(blob, name) {
    if (saving) throw new Error('已有文件正在保存');
    saving = true;
    let folder, created = false;
    try {
      if (!(blob instanceof Blob)) throw new TypeError('导出内容必须是 Blob');
      if (blob.size > MAX_BYTES) throw new Error('导出文件超过 105 MB 上限');
      const filename = safeName(name);
      const id = String(makeId());
      if (!/^[A-Za-z0-9_-]{1,100}$/.test(id)) throw new Error('导出编号无效');
      folder = `${ROOT}/${id}`;
      try { await filesystem.mkdir({path: ROOT, directory, recursive: true}); }
      catch (error) {
        // Native Filesystem rejects even recursive mkdir when the root exists.
        // Never swallow permission/space failures or unique export ID collisions.
        if (error.code !== 'OS-PLUG-FILE-0010') throw error;
      }
      await filesystem.mkdir({path: folder, directory, recursive: false});
      created = true;
      const path = `${folder}/${filename}`;
      const first = new Uint8Array(await blob.slice(0, CHUNK_BYTES).arrayBuffer());
      await filesystem.writeFile({path, directory, data: base64(first)});
      for (let offset = CHUNK_BYTES; offset < blob.size; offset += CHUNK_BYTES) {
        const bytes = new Uint8Array(await blob.slice(offset, offset + CHUNK_BYTES).arrayBuffer());
        await filesystem.appendFile({path, directory, data: base64(bytes)});
      }
      const result = await filesystem.getUri({path, directory});
      if (typeof result?.uri !== 'string' || !result.uri.startsWith('file://'))
        throw new Error('无法取得已保存文件的位置');
      return {uri: result.uri, path, name: filename};
    } catch (error) {
      if (created) {
        try { await filesystem.rmdir({path: folder, directory, recursive: true}); }
        catch { /* Preserve the original save error. */ }
      }
      throw error;
    } finally {
      saving = false;
    }
  }

  async function shareFile(saved) {
    if (!saved || typeof saved.uri !== 'string' || !saved.uri.startsWith('file://') ||
        typeof saved.name !== 'string') throw new TypeError('无效的导出文件');
    return share.share({files: [saved.uri], title: saved.name, dialogTitle: '导出文件'});
  }

  return {saveBlob, shareFile};
}
