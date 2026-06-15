package com.capacitorpay.adyen;

import android.app.Activity;

import androidx.annotation.Nullable;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// NOTE: This file has NOT been compiled against a real Adyen Android SDK - no Adyen Tap to Pay
// artifacts were available offline in this environment, and Adyen's Tap to Pay for Android SDK
// is less consistently documented than Stripe Terminal's. The `com.adyen.tapToPay` package,
// `TapToPay.getInstance()`, `TapToPayConfiguration`, `TapToPayEnvironment`, `TapToPayCallback<T>`
// and `Cancelable` types below are placeholders illustrating the expected shape (configuration
// object in, async callback out) and WILL need to be adjusted to match the actual API surface of
// Adyen's Tap to Pay for Android SDK - check Adyen's official Tap to Pay for Android integration
// guide and sample app, and the Maven coordinates/repository noted in android/build.gradle.
//
// What IS solid: the Terminal API (Nexo) SaleToPOIRequest/SaleToPOIResponse JSON shapes built/
// parsed in buildPaymentRequest/parsePaymentResponse below - this protocol is stable and shared
// across Adyen's POS terminals and Tap to Pay SDKs.
import com.adyen.tapToPay.Cancelable;
import com.adyen.tapToPay.TapToPay;
import com.adyen.tapToPay.TapToPayCallback;
import com.adyen.tapToPay.TapToPayConfiguration;
import com.adyen.tapToPay.TapToPayEnvironment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "AdyenPlugin")
public class AdyenPlugin extends Plugin {

    private String sdkDataUrl;
    private Map<String, String> headers = new HashMap<>();
    private boolean initialized = false;
    private Cancelable activeCancelable;

    @PluginMethod
    public void setup(PluginCall call) {
        String environment = call.getString("environment");
        String merchantAccount = call.getString("merchantAccount");
        String sdkDataUrl = call.getString("sdkDataUrl");

        if (environment == null) {
            call.reject("environment is required");
            return;
        }
        if (merchantAccount == null) {
            call.reject("merchantAccount is required");
            return;
        }
        if (sdkDataUrl == null) {
            call.reject("sdkDataUrl is required");
            return;
        }

        this.sdkDataUrl = sdkDataUrl;
        this.headers = new HashMap<>();
        JSObject headersObj = call.getObject("headers");
        if (headersObj != null) {
            Iterator<String> keys = headersObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                this.headers.put(key, headersObj.optString(key));
            }
        }

        fetchSdkData((sdkData, error) -> {
            if (error != null) {
                call.reject(error.getMessage(), error);
                return;
            }

            TapToPayConfiguration configuration = new TapToPayConfiguration(
                "live".equals(environment) ? TapToPayEnvironment.LIVE : TapToPayEnvironment.TEST,
                merchantAccount
            );

            TapToPay.getInstance().initialize(getContext(), configuration, sdkData, new TapToPayCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    initialized = true;
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    call.resolve(ret);
                }

                @Override
                public void onFailure(Exception e) {
                    call.reject(e.getMessage(), e);
                }
            });
        });
    }

    @PluginMethod
    public void checkTapToPay(PluginCall call) {
        TapToPay.getInstance().checkAvailability(getContext(), new TapToPayCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean available) {
                JSObject ret = new JSObject();
                ret.put("available", available);
                ret.put("activated", TapToPay.getInstance().isActivated());
                call.resolve(ret);
            }

            @Override
            public void onFailure(Exception e) {
                JSObject ret = new JSObject();
                ret.put("available", false);
                ret.put("activated", false);
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void activateTapToPay(PluginCall call) {
        if (!this.initialized) {
            call.reject("Call setup() first");
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity available to present the activation flow");
            return;
        }

        TapToPay.getInstance().activate(activity, new TapToPayCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            }

            @Override
            public void onFailure(Exception e) {
                call.reject(e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void checkout(PluginCall call) {
        if (!this.initialized) {
            call.reject("Call setup() first");
            return;
        }

        Double amount = call.getDouble("amount");
        String currency = call.getString("currency");
        String reference = call.getString("reference");
        if (amount == null || currency == null || reference == null) {
            call.reject("Missing parameters");
            return;
        }

        try {
            JSONObject request = buildPaymentRequest(amount, currency, reference);

            this.activeCancelable = TapToPay.getInstance().processPaymentRequest(request, new TapToPayCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject response) {
                    activeCancelable = null;
                    call.resolve(parsePaymentResponse(response));
                }

                @Override
                public void onFailure(Exception e) {
                    activeCancelable = null;
                    call.reject(e.getMessage(), e);
                }
            });
        } catch (JSONException e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void cancelCheckout(PluginCall call) {
        Cancelable cancelable = this.activeCancelable;
        JSObject ret = new JSObject();
        if (cancelable == null) {
            ret.put("success", false);
            call.resolve(ret);
            return;
        }
        this.activeCancelable = null;
        cancelable.cancel();
        ret.put("success", true);
        call.resolve(ret);
    }

    /**
     * Builds a Terminal API (Nexo) SaleToPOIRequest for a single payment. RequestedAmount is the
     * decimal amount in the major currency unit (e.g. 12.50 for €12.50) - unlike Stripe, the
     * Terminal API does not use minor-unit (cents) amounts, so no conversion is needed here.
     */
    private JSONObject buildPaymentRequest(double amount, String currency, String reference) throws JSONException {
        SimpleDateFormat iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso8601.setTimeZone(TimeZone.getTimeZone("UTC"));
        String timestamp = iso8601.format(new Date());
        // ServiceID must be unique per request; the Nexo spec limits it to 10 characters.
        String serviceId = String.valueOf(System.currentTimeMillis() % 10_000_000_000L);

        JSONObject messageHeader = new JSONObject();
        messageHeader.put("ProtocolVersion", "3.0");
        messageHeader.put("MessageClass", "Service");
        messageHeader.put("MessageCategory", "Payment");
        messageHeader.put("MessageType", "Request");
        messageHeader.put("SaleID", "CapacitorPayAdyen");
        messageHeader.put("ServiceID", serviceId);

        JSONObject saleTransactionId = new JSONObject();
        saleTransactionId.put("TransactionID", reference);
        saleTransactionId.put("TimeStamp", timestamp);

        JSONObject saleData = new JSONObject();
        saleData.put("SaleTransactionID", saleTransactionId);

        JSONObject amountsReq = new JSONObject();
        amountsReq.put("Currency", currency);
        amountsReq.put("RequestedAmount", amount);

        JSONObject paymentTransaction = new JSONObject();
        paymentTransaction.put("AmountsReq", amountsReq);

        JSONObject paymentRequest = new JSONObject();
        paymentRequest.put("SaleData", saleData);
        paymentRequest.put("PaymentTransaction", paymentTransaction);

        JSONObject saleToPOIRequest = new JSONObject();
        saleToPOIRequest.put("MessageHeader", messageHeader);
        saleToPOIRequest.put("PaymentRequest", paymentRequest);

        JSONObject root = new JSONObject();
        root.put("SaleToPOIRequest", saleToPOIRequest);
        return root;
    }

    /** Parses a Terminal API (Nexo) SaleToPOIResponse into the shape expected by AdyenCheckoutResult. */
    private JSObject parsePaymentResponse(JSONObject response) {
        JSObject result = new JSObject();
        try {
            JSONObject saleToPOIResponse = response.optJSONObject("SaleToPOIResponse");
            JSONObject paymentResponse = saleToPOIResponse != null ? saleToPOIResponse.optJSONObject("PaymentResponse") : null;
            JSONObject responseInfo = paymentResponse != null ? paymentResponse.optJSONObject("Response") : null;
            String resultCode = responseInfo != null ? responseInfo.optString("Result", null) : null;
            JSONObject poiData = paymentResponse != null ? paymentResponse.optJSONObject("POIData") : null;
            JSONObject poiTransactionId = poiData != null ? poiData.optJSONObject("POITransactionID") : null;
            String pspReference = poiTransactionId != null ? poiTransactionId.optString("TransactionID", null) : null;

            result.put("success", "Success".equals(resultCode));
            result.put("pspReference", pspReference);
            result.put("resultCode", resultCode);
            result.put("response", JSObject.fromJSONObject(response));
        } catch (JSONException e) {
            result.put("success", false);
        }
        return result;
    }

    private interface SdkDataCallback {
        void onResult(@Nullable String sdkData, @Nullable Exception error);
    }

    private void fetchSdkData(SdkDataCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(this.sdkDataUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                for (Map.Entry<String, String> entry : this.headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    callback.onResult(null, new Exception("sdkDataUrl returned HTTP " + responseCode));
                    return;
                }

                String body = readStream(connection.getInputStream());
                String sdkData = new JSONObject(body).getString("sdkData");
                callback.onResult(sdkData, null);
            } catch (Exception e) {
                callback.onResult(null, e);
            }
        });
    }

    private String readStream(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }
}
