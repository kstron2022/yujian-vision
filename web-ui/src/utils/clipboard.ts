/**
 * 复制文本到剪贴板（M3-B3 对接信息一键复制）。
 *
 * 优先 navigator.clipboard（仅安全上下文可用: HTTPS/localhost）, 否则回退 execCommand。
 * 判据只看 `navigator.clipboard` 是否存在, 不额外判 `window.isSecureContext`——非安全上下文
 * 浏览器本来就不暴露 clipboard, 多判一层只会让"暴露了但 writeText 被拒"的浏览器直接走不到
 * 首选路径; 真被拒时下面的 catch 会回退, 行为不变（反之则让两条分支都能被 e2e 覆盖）。
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 回退到 execCommand
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}
