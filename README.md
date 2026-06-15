# capacitor-pay-adyen

Adyen provider plugin for [`capacitor-pay`](https://github.com/Spanu18/capacitor-pay), wrapping Adyen's **Tap to Pay** SDKs for iOS and Android. Turns the phone itself into the card terminal (NFC) - no external card reader hardware needed. Can also be used standalone without `capacitor-pay`.

## ⚠️ Verification status

This is the least-verified of the three `capacitor-pay-*` plugins:

- `capacitor-pay-sumup` was compiled and verified against the real SumUp SDK (javap-checked).
- `capacitor-pay-stripe` was written against documented Stripe Terminal SDK patterns but not compiled (no cached artifacts available).
- **`capacitor-pay-adyen` was written against general Adyen Tap to Pay integration patterns** (configuration object → initialize → activate → process a Terminal API payment request → get a Terminal API response). Adyen's Tap to Pay SDKs are less consistently documented in public sources than Stripe Terminal's, so confidence in the exact native API surface is **lower** than for the Stripe plugin.

What you can rely on as-is: the **TypeScript layer** (`src/`) builds and type-checks cleanly, and the **Terminal API (Nexo) JSON** built/parsed in the native `buildPaymentRequest`/`parsePaymentResponse` helpers - `SaleToPOIRequest`/`SaleToPOIResponse` with `MessageHeader`/`PaymentRequest`/`PaymentResponse`/`POIData` - which is a stable, documented protocol shared across all of Adyen's POS terminals and Tap to Pay SDKs.

What needs verification against a real Adyen Tap to Pay integration (sample app / official docs), flagged with `NOTE:` comments at the top of each file:

- `ios/Plugin/AdyenPlugin.swift` - the `AdyenTapToPay` import and `TapToPay.Configuration` / `TapToPay.initialize` / `TapToPay.checkAvailability` / `TapToPay.activate` / `TapToPay.processPaymentRequest` / `TapToPay.cancelCurrentPayment` / `AdyenTapToPay.Cancelable` calls are placeholders for whatever Adyen's actual iOS API is named.
- `android/src/main/java/com/capacitorpay/adyen/AdyenPlugin.java` - the `com.adyen.tapToPay` package, `TapToPay.getInstance()`, `TapToPayConfiguration`, `TapToPayEnvironment`, `TapToPayCallback<T>` and `Cancelable` types are placeholders for whatever Adyen's actual Android API is named.
- `CapacitorPayAdyen.podspec` - the `Adyen/TapToPay` CocoaPods subspec name/version; Adyen's iOS SDK is primarily distributed via Swift Package Manager, so Tap to Pay may need to be added as an SPM dependency in the consuming app instead.
- `android/build.gradle` - the `com.adyen.tapToPay:tap-to-pay-android` Maven coordinates and repository; Tap to Pay artifacts are often gated behind Adyen account onboarding rather than published on Maven Central.

Treat this plugin as an architectural skeleton (TS API, plugin registration, Terminal API request/response shapes, setup/activation/checkout flow) to fill in against a real Adyen test account, the same way `capacitor-pay-sumup/test-app` was used to iron out the SumUp plugin's compile errors.

## How this differs from `capacitor-pay-sumup` / `capacitor-pay-stripe`

There's no separate "discover/connect a reader" step - the phone *is* the reader. The flow is:

1. `setup()` - one-time SDK configuration.
2. `activateTapToPay()` - one-time per-device activation/onboarding (analogous to Apple's "Tap to Pay on iPhone" setup screen).
3. `checkTapToPay()` - check availability/activation status before showing a "pay" button.
4. `checkout()` - build and process a single Terminal API payment request; the cardholder taps their card/device against the phone.

## Install

```bash
npm install capacitor-pay-adyen
npx cap sync
```

## Backend requirement: Tap to Pay SDK initialization data

Like Stripe's connection tokens, Adyen's Tap to Pay SDK needs to be initialized with device-specific data that must be obtained **server-side** (via Adyen's Management API, using your Adyen API key) - never embed an Adyen API key in the app.

Expose a backend endpoint that performs this server-side call and returns the result as:

```json
{ "sdkData": "..." }
```

Pass this endpoint's URL as `sdkDataUrl` in `setup()`. The plugin calls it itself (with any `headers` you provide, e.g. for your app's auth) whenever the SDK needs to (re)initialize.

## iOS setup

Add to your `ios/App/Podfile`:

```ruby
pod 'CapacitorPayAdyen', :path => '../../node_modules/capacitor-pay-adyen'
```

Then run `pod install`. **If this fails to resolve `Adyen/TapToPay`** (see verification status above), add Adyen's iOS SDK as a Swift Package Manager dependency to your Xcode project directly instead, following Adyen's Tap to Pay on iPhone integration guide.

### Tap to Pay on iPhone requirements

- The `com.apple.developer.proximity-reader.payment.acceptance` entitlement (granted by Apple on request).
- iPhone XS or later, iOS 16.7+.
- `NSLocationWhenInUseUsageDescription` in `Info.plist` (used to verify the device is in a supported region).

## Android setup

The Tap to Pay for Android SDK may require a separate Adyen-hosted Maven repository (see the `NOTE:` in `android/build.gradle`) - check Adyen's integration guide for the URL and add it to your app's repositories if `mavenCentral()` alone doesn't resolve the dependency.

Like the other `capacitor-pay-*` plugins, consuming apps need core library desugaring enabled in `android/app/build.gradle`:

```groovy
android {
    compileOptions {
        coreLibraryDesugaringEnabled true
    }
}
dependencies {
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.5'
}
```

### Tap to Pay on Android requirements

- Android 11 (API 30) or later, NFC-capable device.
- A device/account enabled for Tap to Pay by Adyen.

## API

<docgen-index>

* [`setup(...)`](#setup)
* [`checkTapToPay()`](#checktaptopay)
* [`activateTapToPay()`](#activatetaptopay)
* [`checkout(...)`](#checkout)
* [`cancelCheckout()`](#cancelcheckout)

</docgen-index>

### setup(...)

```ts
setup(options: AdyenSetupOptions) => Promise<{ success: boolean }>
```

Configure the SDK for this device. Call once on startup, before `checkTapToPay`/`checkout`.

| Param         | Type                                                        |
| ------------- | -------------------------------------------------------------- |
| **`options`** | <code><a href="#adyensetupoptions">AdyenSetupOptions</a></code> |

---

### checkTapToPay()

```ts
checkTapToPay() => Promise<AdyenTapToPayStatus>
```

Check whether Tap to Pay is available on this device and whether it's been activated.

---

### activateTapToPay()

```ts
activateTapToPay() => Promise<{ success: boolean }>
```

Run the one-time Tap to Pay activation flow (device registration plus OS-level onboarding, e.g. Apple's "Tap to Pay on iPhone" setup screen). Only needs to succeed once per device.

---

### checkout(...)

```ts
checkout(options: AdyenCheckoutOptions) => Promise<AdyenCheckoutResult>
```

Build a Terminal API payment request for `amount`/`currency`/`reference` and process it via Tap to Pay - the cardholder taps their card or device against the phone to complete the payment. Requires `activateTapToPay` to have completed previously on this device.

| Param         | Type                                                            |
| ------------- | ------------------------------------------------------------------ |
| **`options`** | <code><a href="#adyencheckoutoptions">AdyenCheckoutOptions</a></code> |

---

### cancelCheckout()

```ts
cancelCheckout() => Promise<{ success: boolean }>
```

Cancel an in-progress `checkout` (e.g. while waiting for a card tap).

---

### Interfaces

#### AdyenSetupOptions

| Prop                 | Type                                                  | Description                                                                                                                                  |
| -------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`environment`**    | <code><a href="#adyenenvironment">AdyenEnvironment</a></code> | "test" or "live" - which Adyen environment Tap to Pay payments are processed against.                                              |
| **`merchantAccount`** | <code>string</code>                                  | The Adyen merchant account that will receive these payments.                                                                                |
| **`sdkDataUrl`**     | <code>string</code>                                  | URL of your backend endpoint that returns Tap to Pay SDK initialization data, e.g. `{ "sdkData": "..." }`.                                  |
| **`headers`**        | <code>Record&lt;string, string&gt;</code>            | Optional extra headers sent with the sdkData request, e.g. for your app's auth.                                                             |

#### AdyenTapToPayStatus

| Prop            | Type                  | Description                                                              |
| --------------- | --------------------- | --------------------------------------------------------------------------- |
| **`available`** | <code>boolean</code> | Whether this device supports Tap to Pay (NFC, OS version, registration). |
| **`activated`** | <code>boolean</code> | Whether the one-time Tap to Pay activation flow has completed.           |

#### AdyenCheckoutOptions

| Prop            | Type                 | Description                                                                                          |
| --------------- | -------------------- | --------------------------------------------------------------------------------------------------------- |
| **`amount`**    | <code>number</code> | Amount to charge, in the major currency unit (e.g. 12.50 for €12.50).                               |
| **`currency`**  | <code>string</code> | ISO 4217 currency code, e.g. "EUR".                                                                  |
| **`reference`** | <code>string</code> | Unique reference for this transaction (Terminal API `SaleData.SaleTransactionID.TransactionID`), used to reconcile the payment later. |

#### AdyenCheckoutResult

| Prop              | Type                                | Description                                                                          |
| ----------------- | ------------------------------------ | ------------------------------------------------------------------------------------------- |
| **`success`**     | <code>boolean</code>                |                                                                                       |
| **`pspReference`** | <code>string</code>                | Adyen's PSP reference for this payment (`POIData.POITransactionID.TransactionID`).  |
| **`resultCode`**  | <code>string</code>                | Result of the Terminal API `PaymentResponse.Response.Result`, e.g. "Success" or "Failure". |
| **`response`**   | <code>Record&lt;string, unknown&gt;</code> | Full Terminal API `SaleToPOIResponse` JSON returned by the SDK.                      |

### Type Aliases

#### AdyenEnvironment

Which Adyen environment to process Tap to Pay payments against.

<code>'test' | 'live'</code>
