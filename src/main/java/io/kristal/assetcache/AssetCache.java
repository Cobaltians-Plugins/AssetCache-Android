package io.kristal.assetcache;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.cobaltians.cobalt.Cobalt;
import org.cobaltians.cobalt.plugin.CobaltAbstractPlugin;
import org.cobaltians.cobalt.plugin.CobaltPluginWebContainer;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.cobaltians.cobalt.tools.Permission;
import org.cobaltians.cobalt.fragments.CobaltFragment;

public class AssetCache extends CobaltAbstractPlugin {

    // TAG
    private static final String TAG = CobaltAbstractPlugin.class.getSimpleName();

    private CobaltFragment fragment;
    private Context context;
    private static final int REQUESTCODE_WRITEEXT = 1;
    private final int PERMISSION_DENIED = 0;
    private final int NETWORK_ERROR = 1;
    private final int FILENOTFOUND_ERROR = 2;
    private final int WRITE_ERROR = 3;
    private final int UNKNOWN_ERROR = 4;
    private String PICTURES_ROOT;
    private final int CALLBACK_DELAY = 250;

    /*******************************************************************************************************
     * MEMBERS
     *******************************************************************************************************/

    private static AssetCache sInstance;

    /**************************************************************************************
     * CONSTRUCTORS
     **************************************************************************************/

    public static CobaltAbstractPlugin getInstance()
    {
        if (sInstance == null)
        {
            sInstance = new AssetCache();
        }
        return sInstance;
    }
    
    @Override
    public void onMessage(@NonNull CobaltPluginWebContainer webContainer,
            @NonNull String action, @Nullable JSONObject data, @Nullable String callbackChannel)
    {
        if (data == null
            || callbackChannel == null)
        {
            if (Cobalt.DEBUG)
            {
                Log.e(TAG, "onMessage: wrong format, possible issues: \n"
                           + "\t- missing 'data' field or not a object,\n"
                           + "\t- missing 'data.actions' field or not an array,\n"
                           + "\t- missing 'callback' field or not a string.\n");
            }
            
            return;
        }
        
        if(webContainer != null && action != null) {
            fragment = webContainer.getFragment();
            context = webContainer.getActivity();
            if ("download".equals(action) || "delete".equals(action)) {
                // Ask the permission to read/write files into external storage
                // jump to "onRequestPermissionResult"
                if(data != null) {
                    if(callbackChannel != null) {
                        PICTURES_ROOT = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toString() + "/";
                        AsyncTaskAsset async = new AsyncTaskAsset(action, data, callbackChannel);
                    } else {
                        Log.e(TAG, "Error : onMessage callbackChannel is null");
                    }
                } else {
                    Log.e(TAG, "Error : onMessage data is null");
                }
            } else if (Cobalt.DEBUG) {
                Log.w(TAG, "onMessage: action '" + action + "' not recognized");
            }
        } else {
            Log.e(TAG, "Error : onMessage webContainer or action is null");
        }
    }

    //AsyncTask for download/delete asset
    private final class AsyncTaskAsset extends AsyncTask<JSONObject, Void, Void> implements Permission.PermissionListener {
        private JSONObject asset;
        private String action;
        private String callback;
        String assetUrl = new String();
        String assetPath = new String();
        String fileName = new String();

        private AsyncTaskAsset(String a, JSONObject d, String c) { //Action, dataAsset, Callback
            action = a;
            asset = d;
            callback = c;
            //Retrieving assets information
            try {
                if (asset.has("url")) {
                    assetUrl = this.asset.getString("url");
                    fileName = toMD5(assetUrl);
                    Log.d(TAG,assetUrl + " is associated with " + fileName);
                    assetPath = PICTURES_ROOT + fileName;
                } else if (asset.has("path")){
                    assetPath = this.asset.getString("path");
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error: Unable to retrieve values in the json object");
                e.printStackTrace();
            }
            Permission.getInstance().requestPermissions((Activity)context, REQUESTCODE_WRITEEXT, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, null, null, this);
        }

        @Override
        public void onRequestPermissionResult(int requestCode, String permission, int result) {
            Log.i(TAG, "requestCode=" + requestCode + ", permission=" + permission + ", result=" + result);
            switch (permission) {
                case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                    if (result == GRANTED) {
                        this.execute();
                    } else {
                        onErrorCallback(PERMISSION_DENIED);
                    }
            }
        }

        @Override
        protected Void doInBackground(JSONObject... objects) {
            if ("download".equals(action)) {
                int count;
                URL url;
                HttpURLConnection connection;
                int lengthOfFile;
                InputStream in;

                //Creating URL object
                try {
                    url = new URL(assetUrl);
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Error: Unable to create URL");
                    e.printStackTrace();
                    onErrorCallback(UNKNOWN_ERROR);
                    return null;
                }

                //Connecting to URL
                try {
                    connection = (HttpURLConnection) url.openConnection();
                    int responseCode = connection.getResponseCode();
                    Log.i(TAG, "Response code: " + responseCode + " on connection to: " + url);
                    if (responseCode == 404) {
                        onErrorCallback(FILENOTFOUND_ERROR);
                        return null;
                    }
                    connection.connect();
                    lengthOfFile = connection.getContentLength();
                    in = new BufferedInputStream(url.openStream());
                } catch (IOException e) { // IOException on network
                    Log.e(TAG, "Error: Network error");
                    e.printStackTrace();
                    onErrorCallback(NETWORK_ERROR);
                    return null;
                }

                //Downloading and Writing file locally
                try {
                    OutputStream out = new FileOutputStream(assetPath);
                    byte data[] = new byte[1024];
                    long total = 0;
                    long startTime = System.currentTimeMillis();
                    while ((count = in.read(data)) != -1) {
                        total += count;
                        if (lengthOfFile < 0) {
                            lengthOfFile = connection.getContentLength();
                        } else {
                            //Sending callback every CALLBACK_DELAY (ms)
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - startTime > CALLBACK_DELAY) {
                                onProgressCallback((int) ((total * 100) / lengthOfFile));
                                startTime = currentTime;
                            }
                        }
                        out.write(data, 0, count);
                    }
                    out.flush();
                    in.close();
                    out.close();
                } catch (IOException e) { // IOException for local
                    Log.e(TAG, "Error: Read/Write error");
                    e.printStackTrace();
                    onErrorCallback(WRITE_ERROR);
                    return null;
                }
                onSuccessCallback();
                //cancel(true);
            } else if ("delete".equals(action)) {
                try {
                    File file = new File(assetPath);
                    if(file.delete()){ // Removing asset
                        onSuccessCallback();
                        return null;
                    } else {
                        // If fails : Error asset does not exist
                        Log.e(TAG,"Error : Cannot find file on path " + assetPath);
                        onErrorCallback(FILENOTFOUND_ERROR);
                        return null;
                    }
                } catch(NullPointerException e){
                    e.printStackTrace();
                    onErrorCallback(FILENOTFOUND_ERROR);
                    return null;
                } catch (Exception e) {
                    e.printStackTrace();
                    onErrorCallback(UNKNOWN_ERROR);
                    return null;
                }
            }
            cancel(true);
            return null;
        }

        //Sending callback of download's progress
        private void onProgressCallback(Integer progress) {
            Log.i(TAG, "Downloading... " + assetPath + " : " + progress + "%");
            try {
                JSONObject callbackData = new JSONObject();
                callbackData.put("status", "downloading");
                callbackData.put("progress", progress);
                Cobalt.publishMessage(callbackData, callback);
            } catch (JSONException exception) {
                exception.printStackTrace();
            }
        }

        //Sending callbacks on error
        private void onErrorCallback(int error) {
            try {
                JSONObject callbackData = new JSONObject();
                callbackData.put("status", "error");
                switch(error){
                    case NETWORK_ERROR:
                        Log.i(TAG, "Result for " + assetPath + " : NETWORK_ERROR");
                        callbackData.put("cause", "networkError");
                        Cobalt.publishMessage(callbackData, callback);
                        break;
                    case FILENOTFOUND_ERROR:
                        Log.i(TAG, "Result for " + assetPath + " : FILENOTFOUND_ERROR");
                        callbackData.put("cause", "fileNotFound");
                        Cobalt.publishMessage(callbackData, callback);
                        break;
                    case WRITE_ERROR:
                        Log.i(TAG, "Result for " + assetPath + " : WRITE_ERROR");
                        callbackData.put("cause", "writeError");
                        Cobalt.publishMessage(callbackData, callback);
                        break;
                    case UNKNOWN_ERROR:
                        Log.i(TAG, "Result for " + assetPath + " : UNKNOWN_ERROR");
                        callbackData.put("cause", "unknownError");
                        Cobalt.publishMessage(callbackData, callback);
                        break;
                    case PERMISSION_DENIED:
                        Log.i(TAG, "Result for " + assetPath + " : PERMISSION_DENIED");
                        callbackData.put("cause", "permissionDenied");
                        Cobalt.publishMessage(callbackData, callback);
                        break;
                }
            } catch (JSONException exception) {
                exception.printStackTrace();
            }
        }

        //Sending callbacks on success
        private void onSuccessCallback() {
            try {
                JSONObject callbackData = new JSONObject();
                Log.i(TAG, "Result for " + assetPath + " : SUCCESS");
                if ("download".equals(action)) {
                    callbackData.put("path", assetPath);
                }
                callbackData.put("status", "success");
                Cobalt.publishMessage(callbackData, callback);
            } catch (JSONException exception) {
                exception.printStackTrace();
            }
        }

        public String toMD5(String s) {
            try {
                // Create MD5 Hash
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(s.getBytes());
                byte digest[] = md.digest();

                // Create Hex String
                StringBuffer hexString = new StringBuffer();
                for (int i=0; i<digest.length; i++) {
                    hexString.append(Integer.toHexString(0xFF & digest[i]));
                }
                return hexString.toString().toUpperCase();
            }catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            return "";
        }

    }

}
