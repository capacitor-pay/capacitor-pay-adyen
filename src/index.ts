import { registerPlugin } from '@capacitor/core'
import type { AdyenPlugin } from './definitions'

const Adyen = registerPlugin<AdyenPlugin>('AdyenPlugin', {
  web: () => import('./web').then(m => new m.AdyenWeb()),
})

export * from './definitions'
export { Adyen }
