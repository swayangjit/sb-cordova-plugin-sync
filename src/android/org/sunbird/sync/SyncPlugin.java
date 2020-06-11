package org.sunbird.sync;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sunbird.sync.db.DbService;
import org.sunbird.sync.db.DbServiceImpl;
import org.sunbird.sync.model.HttpResponse;
import org.sunbird.sync.model.NetworkQueueModel;
import org.sunbird.sync.model.Request;
import org.sunbird.sync.network.ApiService;
import org.sunbird.sync.network.ApiServiceImpl;
import org.sunbird.sync.preference.PreferenceService;
import org.sunbird.sync.preference.PreferenceServiceImpl;
import org.sunbird.sync.queue.NetworkQueue;
import org.sunbird.sync.queue.NetworkQueueImpl;

import java.util.ArrayList;

/**
 * This class echoes a string called from JavaScript.
 */
public class SyncPlugin extends CordovaPlugin {

    private static final String TAG = "Cordova-Plugin-SYNC";
    private DbService mDbService;
    private NetworkQueue mNetworkQueue;
    private ApiService mApiService;
    private PreferenceService mPreferenceService;
    private boolean isSyncing;
    private ArrayList<CallbackContext> mHandler = new ArrayList<>();
    private JSONObject mLastEvent;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        mDbService = new DbServiceImpl();
        mNetworkQueue = new NetworkQueueImpl(mDbService);
        mApiService = new ApiServiceImpl();
        mPreferenceService = new PreferenceServiceImpl(cordova.getActivity());
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("sync")) {
            this.syncNetworkQueue(callbackContext);
            return true;
        } else if (action.equals("enqueue")) {
            this.enqueue(args, callbackContext);
            return true;
        } else if (action.equals("onSyncSucces")) {
            mHandler.add(callbackContext);
            return true;
        }

        return false;
    }

    private void syncNetworkQueue(CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    mNetworkQueue.seed();
                    while (!mNetworkQueue.isEmpty()) {
                        isSyncing = true;
                        NetworkQueueModel networkQueueModel = mNetworkQueue.peek();
                        HttpResponse httpResponse = mApiService.process(networkQueueModel.getRequest());
                        if (httpResponse != null) {
                            if (httpResponse.getStatus() >= 200 && httpResponse.getStatus() < 300) {
                                handlePostAPIActions(networkQueueModel.getType(), httpResponse);
                                mNetworkQueue.dequeue(false);
                                publishSuccessResult(networkQueueModel, httpResponse);
                            } else if (httpResponse.getStatus() == 400) {
                                mNetworkQueue.dequeue(false);
                                publishEvent("error", "BAD_REQUEST");
                                continue;
                            } else if (httpResponse.getStatus() == 401) {
                                handleUnAuthorizedError(networkQueueModel, httpResponse);
                                mNetworkQueue.dequeue(true);
                                continue;
                            } else if (httpResponse.getStatus() == -3) {
                                publishEvent("error", "NETWORK_ERROR");
                                break;
                            }
                        }
                    }
                    isSyncing = false;
                } catch (Exception e) {
                    e.printStackTrace();
                    isSyncing = false;
                }
            }
        });
    }

    private void handlePostAPIActions(String type, HttpResponse httpResponse) throws JSONException {
        if (type.equalsIgnoreCase("telemetry")) {
            postProcessTelemetrySync(httpResponse);
        }
    }

    public void publishSuccessResult(NetworkQueueModel networkQueueModel, HttpResponse response) throws JSONException {
        JSONObject config = networkQueueModel.getConfig();
        String type = networkQueueModel.getType();
        if (config != null && config.optBoolean("shouldPublishResult")) {
            if (type.equalsIgnoreCase("telemetry")) {
                publishEvent("syncedEventCount", networkQueueModel.getEventCount());
            } else if (type.equalsIgnoreCase("course_progress")) {
                String responseStr = response.getBody();
                JSONObject result = getResultFromAPIResponse(responseStr);
                publishEvent("courseProgressResponse", result);
            }else if (type.equalsIgnoreCase("course_assesment")) {
                String responseStr = response.getBody();
                JSONObject result = getResultFromAPIResponse(responseStr);
                publishEvent("courseAssesmentResponse", result);
            }

        }
    }

    private JSONObject getResultFromAPIResponse(String body) {
        JSONObject result;
        try {
            JSONObject jsonObject = new JSONObject(body);
            result = jsonObject.optJSONObject("result");
        } catch (Exception e) {
            return null;
        }
        return result;
    }

    private void publishEvent(String key, Object value) throws JSONException {
        mLastEvent = new JSONObject();
        mLastEvent.put(key, value);
        consumeEvents();
    }

    private void postProcessTelemetrySync(HttpResponse httpResponse) throws JSONException {
        JSONArray jsonArray = mDbService.read("no_sql", new String[]{"value"}, "key = ?", "last_synced_device_register_is_successful");
        if (jsonArray != null && jsonArray.optJSONObject(0) != null) {
            JSONObject isDeviceRegisterSuccesfullDBObj = jsonArray.optJSONObject(0);
            String isDeviceRegisterSuccesfull = isDeviceRegisterSuccesfullDBObj.optString("value");
            if (isDeviceRegisterSuccesfull.equalsIgnoreCase("false")) {
                if (httpResponse != null) {
                    String responseStr = httpResponse.getBody();
                    try {
                        JSONObject response = new JSONObject(responseStr);
                        if (response != null) {
                            long serverTime = Long.valueOf(response.optString("ets"));
                            long now = System.currentTimeMillis();
                            long currentOffset = serverTime - now;
                            long allowedOffset = Math.abs(currentOffset) > 86400000 ? currentOffset : 0;
                            if (allowedOffset > 0) {
                                JSONObject insertObj = new JSONObject();
                                insertObj.put("key", "telemetry_log_min_allowed_offset_key");
                                insertObj.put("value", String.valueOf(allowedOffset));
                                mDbService.insert(insertObj);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void handleUnAuthorizedError(NetworkQueueModel networkQueueModel, HttpResponse httpResponse) throws JSONException {
        Request request = networkQueueModel.getRequest();
        JSONObject headers = request.getHeaders();
        String response = httpResponse.getError();
        JSONObject responseObject = null;
        if (response != null) {
            responseObject = new JSONObject(response);
        }

        if (responseObject != null && "Unauthorized".equalsIgnoreCase(responseObject.optString("message"))) {
            headers.put("Authorization", "Bearer " + mPreferenceService.getBearerToken());
        } else {
            if (mPreferenceService.getUserToken() != null) {
                headers.put("X-Authenticated-User-Token", mPreferenceService.getUserToken());
            }
        }

        request.setHeaders(headers);
        JSONObject model = new JSONObject();
        model.put("request", request.toJSON().toString());
        mDbService.update("msg_id", new String[]{networkQueueModel.getId()}, model);
    }

    private void enqueue(JSONArray args, CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    Object data = args.get(0);
                    JSONObject request = (JSONObject) args.get(1);
                    boolean shouldSync = args.getBoolean(2);
                    String networkRequest = request.getString("request");
                    JSONObject jsonNetworkObject = new JSONObject(networkRequest);
                    jsonNetworkObject.put("body", data);
                    request.put("request", jsonNetworkObject.toString());
                    mDbService.insert(request);
                    if (!isSyncing && shouldSync) {
                        syncNetworkQueue(callbackContext);
                    }
                    callbackContext.success();
                } catch (Exception e) {
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    private void consumeEvents() {
        if (this.mHandler.size() == 0 || mLastEvent == null) {
            return;
        }

        for (CallbackContext callback : this.mHandler) {
            final PluginResult result = new PluginResult(PluginResult.Status.OK, mLastEvent);
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
            callback.success(mLastEvent);
        }

        mLastEvent = null;
    }

}
