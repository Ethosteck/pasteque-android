/*
    Pasteque Android client
    Copyright (C) Pasteque contributors, see the COPYRIGHT file

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package fr.pasteque.client;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Message;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fr.pasteque.client.models.Cash;
import fr.pasteque.client.models.Catalog;
import fr.pasteque.client.models.Category;
import fr.pasteque.client.models.User;
import fr.pasteque.client.models.Product;
import fr.pasteque.client.models.Receipt;
import fr.pasteque.client.utils.HostParser;
import fr.pasteque.client.utils.URLTextGetter;

public class SyncSend {

    private static final String LOG_TAG = "Pasteque/SyncSend";

    // Note: SyncUpdate uses positive values, SyncSend negative ones
    public static final int SYNC_DONE = -1;
    public static final int CONNECTION_FAILED = -2;
    public static final int RECEIPTS_SYNC_DONE = -3;
    public static final int RECEIPTS_SYNC_FAILED = -4;
    public static final int CASH_SYNC_DONE = -5;
    public static final int CASH_SYNC_FAILED = -6;
    public static final int EPIC_FAIL = -7;
    public static final int SYNC_ERROR = -8;

    private Context ctx;
    private Handler listener;

    private ProgressDialog progress;
    /** The tickets to send */
    private List<Receipt> receipts;
    private Cash cash;
    private boolean receiptsDone;
    private boolean cashDone;

    private String apiUrl() {
        return HostParser.getHostFromPrefs(this.ctx) + "api.php";
    }

    private Map<String, String> initParams(String service, String action) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("login", Configure.getUser(this.ctx));
        params.put("password", Configure.getPassword(this.ctx));
        params.put("p", service);
        if (action != null) {
            params.put("action", action);
        }
        return params;
    }

    public SyncSend(Context ctx, Handler listener,
                    List<Receipt> receipts, Cash cash) {
        this.listener = listener;
        this.ctx = ctx;
        this.receipts = receipts;
        this.cash = cash;
    }

    /** Launch synchronization and display progress dialog */
    public void startSyncSend() {
        this.progress = new ProgressDialog(ctx);
        this.progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        this.progress.setMax(2);
        this.progress.setTitle(ctx.getString(R.string.sync_title));
        this.progress.setMessage(ctx.getString(R.string.sync_message));
        this.progress.show();
        synchronize();
    }

    public void synchronize() {
        if (this.receipts.size() > 0) {
            runReceiptsSync();
        } else {
            // Skip receipts and go to cash
            this.receiptsDone = true;
            if (progress != null) {
                progress.incrementProgressBy(1);
            }
            runCashSync();
        }
     }

    private void fail(Exception e) {
        if (this.listener != null) {
            Message m = listener.obtainMessage();
            m.what = CASH_SYNC_FAILED;
            m.obj = e;
            m.sendToTarget();
        }
        if (this.progress != null) {
            this.progress.dismiss();
            this.progress = null;
        }
    }

    private void runReceiptsSync() {
        JSONArray rcptsJSON = new JSONArray();
        for (Receipt r : this.receipts) {
            try {
                JSONObject o = r.toJSON();
                rcptsJSON.put(o);
            } catch (JSONException e) {
                Log.e(LOG_TAG, r.toString(), e);
                this.fail(e);
                return;
            }
        }
        Map<String, String> postBody = new HashMap<String, String>();
        postBody.put("tickets", rcptsJSON.toString());
        try {
            postBody.put("cash", this.cash.toJSON().toString());
        } catch (JSONException e) {
            Log.e(LOG_TAG, this.cash.toString(), e);
            this.fail(e);
            return;
        }
        String location = Configure.getStockLocation(this.ctx);
        if (!location.equals("")) {
            postBody.put("location", location);
        }
        URLTextGetter.getText(this.apiUrl(),
                this.initParams("TicketsAPI", "save"),
                postBody, new DataHandler(DataHandler.TYPE_RECEIPTS));
    }

    private void runCashSync() {
        Map<String, String> postBody = new HashMap<String, String>();
        try {
            postBody.put("cash", this.cash.toJSON().toString());
        } catch (JSONException e) {
            Log.e(LOG_TAG, this.cash.toString(), e);
            this.fail(e);
            return;
        }
        URLTextGetter.getText(this.apiUrl(),
                this.initParams("CashesAPI", "update"), postBody,
                new DataHandler(DataHandler.TYPE_CASH));
    }

    private void parseReceiptsResult(String result) {
        int what = 0;
        if (result.equals("true")) {
            what = RECEIPTS_SYNC_DONE;
            // Let's continue with cash!
            runCashSync();
        } else {
            what = RECEIPTS_SYNC_FAILED;
            this.cashDone = true; // We won't even do it
            this.checkFinished();
        }
        if (this.listener != null) {
            Message m = listener.obtainMessage();
            m.what = what;
            m.obj = result;
            m.sendToTarget();
        }
    }

    private void parseCashResult(String content) {
        JSONObject o = null;
        try {
            o = new JSONObject(content);
            if (o.getBoolean("result")) {
                Cash newCash = Cash.fromJSON(o.getJSONObject("cash"));
                if (this.listener != null) {
                    Message m = listener.obtainMessage();
                    m.what = CASH_SYNC_DONE;
                    m.obj = newCash;
                    m.sendToTarget();
                }
            } else {
                if (this.listener != null) {
                    Message m = listener.obtainMessage();
                    m.what = CASH_SYNC_FAILED;
                    m.obj = content;
                    m.sendToTarget();
                }
            }
        } catch(JSONException e) {
            Log.e(LOG_TAG, "Error while parsing cash result", e);
            if (this.listener != null) {
                Message m = listener.obtainMessage();
                m.what = CASH_SYNC_FAILED;
                m.obj = content;
                m.sendToTarget();
            }
            return;
        }
    }

    private void finish() {
        if (this.progress != null) {
            this.progress.dismiss();
            this.progress = null;
        }
        Message m = this.listener.obtainMessage();
        m.what = SYNC_DONE;
        m.sendToTarget();
    }

    private void checkFinished() {
        if (this.receiptsDone && this.cashDone) {
            this.finish();
        }
    }

    
    private class DataHandler extends Handler {
        
        private static final int TYPE_RECEIPTS = 1;
        private static final int TYPE_CASH = 2;

        private int type;
        
        public DataHandler(int type) {
            this.type = type;
        }

        private String getError(String response) {
            try {
                JSONObject o = new JSONObject(response);
                if (o.has("error")) {
                    return o.getString("error");
                }
            } catch (JSONException e) {
            }
            return null;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (this.type) {
            case TYPE_RECEIPTS:
                SyncSend.this.receiptsDone = true;
                break;
            case TYPE_CASH:
                SyncSend.this.cashDone = true;
                break;
            }
            if (progress != null) {
                progress.incrementProgressBy(1);
            }
            switch (msg.what) {
            case URLTextGetter.SUCCESS:
                // Parse content
                String content = (String) msg.obj;
                if (this.getError(content) != null) {
                    if (listener != null) {
                        Message m = listener.obtainMessage();
                        m.what = SYNC_ERROR;
                        m.obj = this.getError(content);
                        m.sendToTarget();
                    }
                    finish();
                } else {
                    switch (type) {
                    case TYPE_RECEIPTS:
                        parseReceiptsResult(content);
                        break;
                    case TYPE_CASH:
                        parseCashResult(content);
                        break;
                    }
                }
                break;
            case URLTextGetter.ERROR:
                Log.e(LOG_TAG, "URLTextGetter error", (Exception)msg.obj);
            case URLTextGetter.STATUS_NOK:
                if (listener != null) {
                    Message m = listener.obtainMessage();
                    m.what = CONNECTION_FAILED;
                    m.obj = msg.obj;
                    m.sendToTarget();
                }
                finish();
                return;
            }
            checkFinished();
        }
    }

}