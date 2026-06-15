import Foundation
import Capacitor

// NOTE: This file has NOT been compiled against a real Adyen iOS SDK - no Adyen Tap to Pay
// artifacts (CocoaPods or Swift Package Manager) were available offline in this environment,
// and Adyen's Tap to Pay SDK is less consistently documented than Stripe Terminal's. The
// `AdyenTapToPay` namespace and the `TapToPay.Configuration` / `TapToPay.initialize` /
// `TapToPay.checkAvailability` / `TapToPay.activate` / `TapToPay.processPaymentRequest` /
// `TapToPay.cancelCurrentPayment` calls below are placeholders illustrating the expected shape
// (configuration object in, completion/result callback out) and WILL need to be adjusted to
// match the actual API surface of https://github.com/Adyen/adyen-ios's Tap to Pay component -
// check Adyen's official Tap to Pay on iPhone integration guide and sample app.
//
// What IS solid: the Terminal API (Nexo) `SaleToPOIRequest`/`SaleToPOIResponse` JSON shapes
// built/parsed in `buildPaymentRequest`/`parsePaymentResponse` below - this protocol is stable
// and shared across Adyen's POS terminals and Tap to Pay SDKs.
import AdyenTapToPay

@objc(AdyenPlugin)
public class AdyenPlugin: CAPPlugin {
    private var environment: String = "test"
    private var merchantAccount: String?
    private var sdkDataUrl: String?
    private var headers: [String: String] = [:]
    private var initialized = false
    private var activeCancelable: AdyenTapToPay.Cancelable?

    @objc func setup(_ call: CAPPluginCall) {
        guard let environment = call.getString("environment") else {
            call.reject("environment is required")
            return
        }
        guard let merchantAccount = call.getString("merchantAccount") else {
            call.reject("merchantAccount is required")
            return
        }
        guard let sdkDataUrl = call.getString("sdkDataUrl") else {
            call.reject("sdkDataUrl is required")
            return
        }

        self.environment = environment
        self.merchantAccount = merchantAccount
        self.sdkDataUrl = sdkDataUrl
        self.headers = (call.getObject("headers") as? [String: String]) ?? [:]

        fetchSdkData { [weak self] sdkData, error in
            guard let self = self else { return }
            if let error = error {
                call.reject(error.localizedDescription)
                return
            }
            guard let sdkData = sdkData else {
                call.reject("sdkDataUrl did not return sdk data")
                return
            }

            let configuration = TapToPay.Configuration(
                environment: environment == "live" ? .live : .test,
                merchantAccount: merchantAccount
            )

            TapToPay.initialize(configuration: configuration, sdkData: sdkData) { result in
                switch result {
                case .success:
                    self.initialized = true
                    call.resolve(["success": true])
                case .failure(let error):
                    call.reject(error.localizedDescription)
                }
            }
        }
    }

    @objc func checkTapToPay(_ call: CAPPluginCall) {
        TapToPay.checkAvailability { available in
            call.resolve([
                "available": available,
                "activated": TapToPay.isActivated,
            ])
        }
    }

    @objc func activateTapToPay(_ call: CAPPluginCall) {
        guard self.initialized else {
            call.reject("Call setup() first")
            return
        }
        guard let viewController = self.bridge?.viewController else {
            call.reject("No view controller available to present the activation flow")
            return
        }

        TapToPay.activate(from: viewController) { result in
            switch result {
            case .success:
                call.resolve(["success": true])
            case .failure(let error):
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func checkout(_ call: CAPPluginCall) {
        guard self.initialized else {
            call.reject("Call setup() first")
            return
        }
        guard let amount = call.getDouble("amount"),
              let currency = call.getString("currency"),
              let reference = call.getString("reference") else {
            call.reject("Missing parameters")
            return
        }

        let request = buildPaymentRequest(amount: amount, currency: currency, reference: reference)

        self.activeCancelable = TapToPay.processPaymentRequest(request) { [weak self] result in
            self?.activeCancelable = nil
            switch result {
            case .success(let response):
                call.resolve(self?.parsePaymentResponse(response) ?? ["success": false])
            case .failure(let error):
                call.reject(error.localizedDescription)
            }
        }
    }

    @objc func cancelCheckout(_ call: CAPPluginCall) {
        guard let cancelable = self.activeCancelable else {
            call.resolve(["success": false])
            return
        }
        cancelable.cancel()
        self.activeCancelable = nil
        call.resolve(["success": true])
    }

    private func fetchSdkData(completion: @escaping (String?, Error?) -> Void) {
        guard let urlString = self.sdkDataUrl, let url = URL(string: urlString) else {
            completion(nil, NSError(domain: "AdyenPlugin", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid sdkDataUrl"]))
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in self.headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        URLSession.shared.dataTask(with: request) { data, _, error in
            if let error = error {
                completion(nil, error)
                return
            }

            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let sdkData = json["sdkData"] as? String else {
                completion(nil, NSError(domain: "AdyenPlugin", code: 0, userInfo: [NSLocalizedDescriptionKey: "sdkDataUrl did not return an 'sdkData' field"]))
                return
            }

            completion(sdkData, nil)
        }.resume()
    }

    /// Builds a Terminal API (Nexo) `SaleToPOIRequest` for a single payment. `RequestedAmount`
    /// is the decimal amount in the major currency unit (e.g. 12.50 for €12.50) - unlike Stripe,
    /// the Terminal API does not use minor-unit (cents) amounts, so no conversion is needed here.
    private func buildPaymentRequest(amount: Double, currency: String, reference: String) -> [String: Any] {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        // ServiceID must be unique per request; the Nexo spec limits it to 10 characters.
        let serviceId = String(Int(Date().timeIntervalSince1970 * 1000) % 10_000_000_000)

        return [
            "SaleToPOIRequest": [
                "MessageHeader": [
                    "ProtocolVersion": "3.0",
                    "MessageClass": "Service",
                    "MessageCategory": "Payment",
                    "MessageType": "Request",
                    "SaleID": "CapacitorPayAdyen",
                    "ServiceID": serviceId,
                ],
                "PaymentRequest": [
                    "SaleData": [
                        "SaleTransactionID": [
                            "TransactionID": reference,
                            "TimeStamp": timestamp,
                        ],
                    ],
                    "PaymentTransaction": [
                        "AmountsReq": [
                            "Currency": currency,
                            "RequestedAmount": amount,
                        ],
                    ],
                ],
            ],
        ]
    }

    /// Parses a Terminal API (Nexo) `SaleToPOIResponse` into the shape expected by `AdyenCheckoutResult`.
    private func parsePaymentResponse(_ response: [String: Any]) -> [String: Any] {
        let paymentResponse = ((response["SaleToPOIResponse"] as? [String: Any])?["PaymentResponse"] as? [String: Any]) ?? [:]
        let result = (paymentResponse["Response"] as? [String: Any])?["Result"] as? String
        let pspReference = ((paymentResponse["POIData"] as? [String: Any])?["POITransactionID"] as? [String: Any])?["TransactionID"] as? String

        return [
            "success": result == "Success",
            "pspReference": pspReference ?? NSNull(),
            "resultCode": result ?? NSNull(),
            "response": response,
        ]
    }
}
