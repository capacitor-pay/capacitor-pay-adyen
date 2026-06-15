/** Which Adyen environment to process Tap to Pay payments against. */
export type AdyenEnvironment = 'test' | 'live'

export interface AdyenSetupOptions {
  /** "test" or "live" - which Adyen environment Tap to Pay payments are processed against. */
  environment: AdyenEnvironment
  /** The Adyen merchant account that will receive these payments. */
  merchantAccount: string
  /**
   * URL of your backend endpoint that returns Tap to Pay SDK initialization data for this
   * device, obtained server-side via Adyen's Management API (device registration / SDK data
   * endpoint). The plugin calls this endpoint itself whenever the SDK needs to (re)initialize -
   * no Adyen API key is ever stored in the app.
   */
  sdkDataUrl: string
  /** Optional extra headers sent with the sdkData request, e.g. for your app's auth. */
  headers?: Record<string, string>
}

export interface AdyenTapToPayStatus {
  /** Whether this device supports Tap to Pay (NFC, OS version, and device registration). */
  available: boolean
  /** Whether the one-time Tap to Pay activation flow has completed on this device. */
  activated: boolean
}

export interface AdyenCheckoutOptions {
  /** Amount to charge, in the major currency unit (e.g. 12.50 for €12.50). */
  amount: number
  /** ISO 4217 currency code, e.g. "EUR". */
  currency: string
  /**
   * Unique reference for this transaction, sent as the Terminal API
   * `SaleData.SaleTransactionID.TransactionID`. Used to reconcile the payment later via the
   * Adyen Customer Area or the `/payments/details` endpoint.
   */
  reference: string
}

export interface AdyenCheckoutResult {
  success: boolean
  /** Adyen's PSP reference for this payment (`POIData.POITransactionID.TransactionID`). */
  pspReference?: string
  /** Result of the Terminal API `PaymentResponse.Response.Result`, e.g. "Success" or "Failure". */
  resultCode?: string
  /** Full Terminal API `SaleToPOIResponse` JSON returned by the SDK. */
  response?: Record<string, unknown>
}

export interface AdyenPlugin {
  /** Configure the SDK for this device. Call once on startup, before checkTapToPay/checkout. */
  setup: (options: AdyenSetupOptions) => Promise<{ success: boolean }>
  /** Check whether Tap to Pay is available on this device and whether it's been activated. */
  checkTapToPay: () => Promise<AdyenTapToPayStatus>
  /**
   * Run the one-time Tap to Pay activation flow (device registration plus OS-level onboarding,
   * e.g. Apple's "Tap to Pay on iPhone" setup screen). Only needs to succeed once per device.
   */
  activateTapToPay: () => Promise<{ success: boolean }>
  /**
   * Build a Terminal API payment request for `amount`/`currency`/`reference` and process it via
   * Tap to Pay - the cardholder taps their card or device against the phone to complete the
   * payment. Requires `activateTapToPay` to have completed previously on this device.
   */
  checkout: (options: AdyenCheckoutOptions) => Promise<AdyenCheckoutResult>
  /** Cancel an in-progress `checkout` (e.g. while waiting for a card tap). */
  cancelCheckout: () => Promise<{ success: boolean }>
}
