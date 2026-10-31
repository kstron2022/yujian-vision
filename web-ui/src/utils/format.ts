/** 国标协议版本统一显示格式: 存储值(2016/2022) → GB/T28181-xxxx（全站显示一律走这里） */
export function formatProtocolVersion(v?: string | null): string {
  if (!v) return '-';
  return `GB/T28181-${v}`;
}

/** 时间统一显示: ISO 字符串(2026-09-07T01:23:45) 或 epoch 毫秒 → yyyy-MM-dd HH:mm:ss */
export function fmtDateTime(v?: string | number | null): string {
  if (v === null || v === undefined || v === '') return '-';
  if (typeof v === 'number') {
    const d = new Date(v);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  }
  return String(v).replace('T', ' ').slice(0, 19);
}

/** 密码复杂度校验: ≥8 位且同时含字母和数字（A 阶段决策） */
export function isValidPassword(v: string): boolean {
  return /^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(v);
}
