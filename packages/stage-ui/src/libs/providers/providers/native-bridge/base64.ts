/** Decodes base64 from a Capacitor plugin result into bytes. */
export function decodeBase64(value: string): ArrayBuffer {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++)
    bytes[i] = binary.charCodeAt(i)

  return bytes.buffer
}

/** Encodes bytes as base64 for a Capacitor plugin call. */
export function encodeBase64(bytes: Uint8Array): string {
  // Chunks keep `String.fromCharCode` under the engine argument limit.
  const chunkSize = 0x8000
  let binary = ''
  for (let i = 0; i < bytes.length; i += chunkSize)
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize))

  return btoa(binary)
}
