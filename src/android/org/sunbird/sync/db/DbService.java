package org.sunbird.sync.db;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by swayangjit on 27/3/20.
 */
public interface DbService {
    JSONArray seed() throws JSONException;
    long insert(JSONObject request) throws JSONException;
    int delete(String id) throws  JSONException;
    JSONArray read(String table, String[] coloumns, String selection, String selectionArgs) throws JSONException;
}
