import { WebPlugin } from '@capacitor/core'
import type {
  AdyenCheckoutOptions,
  AdyenCheckoutResult,
  AdyenPlugin,
  AdyenSetupOptions,
  AdyenTapToPayStatus,
} from './definitions'

export class AdyenWeb extends WebPlugin implements AdyenPlugin {
  async setup(_options: AdyenSetupOptions): Promise<{ success: boolean }> {
    throw this.unavailable('Adyen Tap to Pay is not available on web.')
  }

  async checkTapToPay(): Promise<AdyenTapToPayStatus> {
    throw this.unavailable('Adyen Tap to Pay is not available on web.')
  }

  async activateTapToPay(): Promise<{ success: boolean }> {
    throw this.unavailable('Adyen Tap to Pay is not available on web.')
  }

  async checkout(_options: AdyenCheckoutOptions): Promise<AdyenCheckoutResult> {
    throw this.unavailable('Adyen Tap to Pay is not available on web.')
  }

  async cancelCheckout(): Promise<{ success: boolean }> {
    throw this.unavailable('Adyen Tap to Pay is not available on web.')
  }
}
