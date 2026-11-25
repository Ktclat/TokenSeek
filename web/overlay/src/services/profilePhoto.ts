// Only the newly encoded square can enter the profile directory; source files and metadata stay out.
const size = 160
const maxEncodedLength = 48 * 1024
export const avatarTypes = 'image/jpeg,image/png,image/webp'

export function isProfilePhoto(value: unknown): value is string {
  return typeof value === 'string' && value.length <= maxEncodedLength
    && /^data:image\/jpeg;base64,\/9j\/[A-Za-z0-9+/]+=*$/u.test(value)
}

export async function makeProfilePhoto(file: File): Promise<string> {
  if (!file.size || file.size > 16 * 1024 * 1024) throw new Error('size')
  const signature = new Uint8Array(await file.slice(0, 12).arrayBuffer())
  const matches = (at: number, bytes: number[]) => bytes.every((value, i) => signature[at + i] === value)
  const jpeg = matches(0, [255, 216, 255])
  const png = matches(0, [137, 80, 78, 71, 13, 10, 26, 10])
  const webp = matches(0, [82, 73, 70, 70]) && matches(8, [87, 69, 66, 80])
  if (!jpeg && !png && !webp) throw new Error('format')

  const image = new Image()
  const url = URL.createObjectURL(file)
  const canvas = document.createElement('canvas')
  try {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('format'))
      image.src = url
    })
    const w = image.naturalWidth
    const h = image.naturalHeight
    if (!w || !h || w * h > 64_000_000) throw new Error('size')
    canvas.width = canvas.height = size
    const context = canvas.getContext('2d')
    if (!context) throw new Error('format')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, size, size)
    const edge = Math.min(w, h)
    context.imageSmoothingQuality = 'high'
    context.drawImage(image, (w - edge) / 2, (h - edge) / 2, edge, edge, 0, 0, size, size)
    const encoded = canvas.toDataURL('image/jpeg', 0.8)
    if (!isProfilePhoto(encoded)) throw new Error('format')
    return encoded
  } finally {
    URL.revokeObjectURL(url)
    image.removeAttribute('src')
    canvas.width = canvas.height = 0
  }
}
