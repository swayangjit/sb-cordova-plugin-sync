package org.sunbird.sync.queue;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.sunbird.sync.db.DbService;
import org.sunbird.sync.model.NetworkQueueModel;
import org.sunbird.sync.model.Request;

import java.util.PriorityQueue;

/**
 * Created by swayangjit on 26/3/20.
 */
public class NetworkQueueImpl implements NetworkQueue {
    private PriorityQueue<NetworkQueueModel> mPriorityNetworkModelQueue = new PriorityQueue<>();
    private DbService mDbService;

    public NetworkQueueImpl(DbService dbService) {
        this.mDbService = dbService;
    }

    @Override
    public void seed() {
        try {
            JSONArray resultArray = mDbService.seed();
            if (resultArray != null) {
                for (int i = 0; i < resultArray.length(); i++) {
                    JSONObject jsonObject = resultArray.getJSONObject(i);
                    String requestStr = jsonObject.optString("request");
                    Long id = jsonObject.optLong("_id");
                    String msgId = jsonObject.optString("msg_id");
                    Integer priority = jsonObject.optInt("priority");
                    Integer eventCount = jsonObject.optInt("item_count");
                    String timestamp = jsonObject.optString("timestamp");
                    String config = jsonObject.optString("config");
                    JSONObject requestJson = new JSONObject(requestStr);
                    String host = requestJson.optString("host");
                    Object body = requestJson.get("body");
                    String path = requestJson.optString("path");
                    String type = requestJson.optString("type");
                    String serializer = requestJson.optString("serializer");
                    JSONObject headers = requestJson.optJSONObject("headers");
                    Request request = new Request(host, path, type, headers, serializer, body);
                    NetworkQueueModel networkQueueModel = new NetworkQueueModel(msgId, priority, Long.valueOf(timestamp), config, eventCount, request);
                    mPriorityNetworkModelQueue.add(networkQueueModel);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public NetworkQueueModel dequeue() {
        if (mPriorityNetworkModelQueue != null) {
            try {
                NetworkQueueModel networkQueueModel = mPriorityNetworkModelQueue.poll();
                mDbService.delete(networkQueueModel.getId());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public NetworkQueueModel peek() {
        return mPriorityNetworkModelQueue != null ? mPriorityNetworkModelQueue.peek() : null;
    }

    @Override
    public int getSize() {
        return mPriorityNetworkModelQueue.size();
    }

    @Override
    public boolean isEmpty() {
        return getSize() == 0;
    }

}
