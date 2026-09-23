// 데모 환경에서 동일한 이름·생년월일 조합은 어느 브라우저에서도 같은 등록 요청 키를 만든다.
// 백엔드는 같은 키와 같은 입력을 재전송하면 기존 아이를 반환한다. 인증 수단이 아니므로 데모에서만 사용한다.
export function childRegistrationKey(name: string, birthDate: string): string {
  const source = `deni:demo-child-registration:v1|${name.trim().normalize('NFC')}|${birthDate}`
  let h1 = 1779033703
  let h2 = 3144134277
  let h3 = 1013904242
  let h4 = 2773480762
  for (let index = 0; index < source.length; index += 1) {
    const code = source.charCodeAt(index)
    h1 = h2 ^ Math.imul(h1 ^ code, 597399067)
    h2 = h3 ^ Math.imul(h2 ^ code, 2869860233)
    h3 = h4 ^ Math.imul(h3 ^ code, 951274213)
    h4 = h1 ^ Math.imul(h4 ^ code, 2716044179)
  }
  h1 = Math.imul(h3 ^ (h1 >>> 18), 597399067)
  h2 = Math.imul(h4 ^ (h2 >>> 22), 2869860233)
  h3 = Math.imul(h1 ^ (h3 >>> 17), 951274213)
  h4 = Math.imul(h2 ^ (h4 >>> 19), 2716044179)
  const hex = [h1, h2, h3, h4].map((value) => (value >>> 0).toString(16).padStart(8, '0')).join('')
  const variant = ((parseInt(hex[16], 16) & 0x3) | 0x8).toString(16)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`
}

export function generateId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  // crypto.randomUUID is only exposed in secure contexts (HTTPS or
  // localhost). Testing over a plain-HTTP LAN address (e.g. from a phone)
  // needs a fallback so pages don't crash on load.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}
