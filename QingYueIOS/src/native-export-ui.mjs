export function offerNativeFile(blob, name, {modal, el, button, toast, busy}) {
  let savedPromise;
  const save = () => savedPromise ||= window.QYNative.saveBlob(blob, name).catch(error => {
    savedPromise = undefined;
    throw error;
  });
  modal('导出文件', body => {
    body.append(el('p', name), el('p', '保存后可在系统“文件”App → 我的 iPad／iPhone → 晴阅 → QingYueExports 中找到副本。'));
    const status = el('p', '', 'muted');
    status.setAttribute('role', 'status');
    let working = false;
    async function perform(share) {
      if (working) return;
      working = true;
      saveButton.disabled = shareButton.disabled = true;
      try {
        const file = await busy('正在保存到 App 文件…', save);
        status.textContent = `已保存：${file.path}`;
        if (share) {
          try { await window.QYNative.shareFile(file); }
          catch (error) {
            // Dismissing the share sheet must not discard the saved export.
            if (error.name === 'AbortError' || /cancel/i.test(error.message || '')) {
              toast('已取消分享，文件副本仍已保存');
            } else throw new Error(`文件已保存，但系统分享未完成：${error.message || '请在“文件”App 中打开'}`);
          }
        } else toast('文件已保存到晴阅文件夹');
      } finally {
        working = false;
        saveButton.disabled = shareButton.disabled = false;
      }
    }
    const saveButton = button('保存到 App 文件', () => perform(false), 'primary');
    const shareButton = button('系统分享 / 存储到文件', () => perform(true));
    body.append(saveButton, shareButton, status);
  });
}
