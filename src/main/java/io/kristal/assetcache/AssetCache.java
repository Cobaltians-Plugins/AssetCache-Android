package io.kristal.assetCache;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
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

import io.kristal.permissions.Permission;
import org.cobaltians.cobalt.fragments.CobaltFragment;

public class AssetCache extends CobaltAbstractPlugin implements Permission.PermissionListener {

    // TAG
    private static final String TAG = CobaltAbstractPlugin.class.getSimpleName();

    private CobaltFragment fragment;
    private Context context;
    private String mModifyAssetCallback;
    private JSONObject dataAsset = new JSONObject();
    private static final int REQUESTCODE_WRITEEXT = 1;
    private final int SUCCESS = 0;
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

    public static CobaltAbstractPlugin getInstance(CobaltPluginWebContainer webContainer) {
        if (sInstance == null) {
            sInstance = new AssetCache();
        }

        return sInstance;
    }

    @Override
    public void onMessage(CobaltPluginWebContainer webContainer, JSONObject message) {
        try {
            String action = message.getString(Cobalt.kJSAction);
            dataAsset = message.getJSONObject(Cobalt.kJSData);
            mModifyAssetCallback = message.getString(Cobalt.kJSCallback);
            fragment = webContainer.getFragment();
            context = webContainer.getActivity();
            if ("download".equals(action) || "delete".equals(action)) {
                // Ask the permission to read/write files into external storage
                // jump to "onRequestPermissionResult"
                PICTURES_ROOT = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES).toString() + "/";
                Permission.getInstance().requestPermissions((Activity)context, REQUESTCODE_WRITEEXT, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, null, null, this);
            }
            else if (Cobalt.DEBUG) {
                Log.w(TAG, "onMessage: action '" + action + "' not recognized");
            }

        }
        catch(JSONException exception) {
            if (Cobalt.DEBUG) {
                Log.e(TAG, "onMessage: wrong format, possible issues: \n" +
                        "\t- missing 'action' field or not a string,\n" +
                        "\t- missing 'data' field or not a object,\n" +
                        "\t- missing 'data.actions' field or not an array,\n" +
                        "\t- missing 'callback' field or not a string.\n");
            }
            exception.printStackTrace();
        }
    }


    @Override
    public void onRequestPermissionResult(int requestCode, String permission, int result) {
        Log.i(TAG, "requestCode=" + requestCode + ", permission=" + permission + ", result=" + result);
        switch (permission) {
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                if (result == GRANTED) {
                    AsyncTaskAsset async = new AsyncTaskAsset(dataAsset);
                    async.execute();
                }
        }
    }

    //AsyncTask for download/delete asset
    private final class AsyncTaskAsset extends AsyncTask<JSONObject, Void, Void> {
        private JSONObject asset;
        private String path = new String();

        private AsyncTaskAsset(JSONObject d) {
            this.asset = d;
            try {
                path = this.asset.getString("path");
            } catch (JSONException e) {
                Log.e(TAG, "Error: Unable to retrieve values in the json object");
                e.printStackTrace();
            }
        }

        @Override
        protected Void doInBackground(JSONObject... objects) {
            if (asset.has("url")) { //event: 'downloadAsset'
                int count;
                String strUrl, folderName;
                URL url;
                HttpURLConnection connection;
                int lengthOfFile;
                InputStream in;

                //Retrieving assets information
                try {
                    strUrl = asset.getString("url");
                    int i = path.lastIndexOf('/');
                    if (i == -1)
                        folderName = "/";
                    else
                        folderName = path.substring(0, i);
                    url = new URL(strUrl);
                } catch (JSONException e) {
                    Log.e(TAG, "Error: Unable to retrieve values in the json object");
                    e.printStackTrace();
                    onResultCallback(path,UNKNOWN_ERROR);
                    return null;
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Error: Unable to create URL");
                    e.printStackTrace();
                    onResultCallback(path,UNKNOWN_ERROR);
                    return null;
                }

                //Connecting to url
                try {
                    connection = (HttpURLConnection) url.openConnection();
                    int responseCode = connection.getResponseCode();
                    Log.i(TAG, "Response code: " + responseCode + " on connection to: " + strUrl);
                    if (responseCode == 404) {
                        onResultCallback(path,FILENOTFOUND_ERROR);
                        return null;
                    }
                    connection.connect();
                    lengthOfFile = connection.getContentLength();
                    in = new BufferedInputStream(url.openStream());
                } catch (IOException e) { // IOException on network
                    Log.e(TAG, "Error: Network error");
                    e.printStackTrace();
                    onResultCallback(path,NETWORK_ERROR);
                    return null;
                }

                //Downloading and Writing file locally
                try {
                    File folder = new File(PICTURES_ROOT + folderName);
                    boolean success = true;
                    if (!folder.exists()) {
                        success = folder.mkdirs();
                    }
                    if (!success) {
                        Log.e(TAG, "Error : Unable to create directories for this path : " + folder.getCanonicalPath());
                        throw new IOException();
                    } else {
                        OutputStream out = new FileOutputStream(PICTURES_ROOT + path);
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
                    }
                } catch (IOException e) { // IOException for local
                    Log.e(TAG, "Error: Read/Write error");
                    e.printStackTrace();
                    onResultCallback(path,WRITE_ERROR);
                    return null;
                }

                onResultCallback(path,SUCCESS);
                cancel(true);
            } else { //event: 'deleteAsset'
                try {
                    File file = new File(PICTURES_ROOT + path);

                    if(file.delete()){ // Removing asset
                        onResultCallback(path,SUCCESS);
                        return null;
                    } else {
                        // If fails : Error asset does not exist
                        Log.e(TAG,"Error : Cannot find file on path " + path);
                        onResultCallback(path,FILENOTFOUND_ERROR);
                        return null;
                    }
                } catch(NullPointerException e){
                    e.printStackTrace();
                    onResultCallback(path,FILENOTFOUND_ERROR);
                    return null;
                } catch (Exception e) {
                    e.printStackTrace();
                    onResultCallback(path,UNKNOWN_ERROR);
                    return null;
                }
            }
            cancel(true);
            return null;
        }

        //Sending callback of download's progress
        private void onProgressCallback(Integer progress) {
            Log.i(TAG, "Downloading... " + path + " : " + progress + "%");
            try {
                JSONObject callbackData = new JSONObject();
                callbackData.put("path", path);
                callbackData.put("status", "downloading");
                callbackData.put("progress", progress);
                fragment.sendCallback(mModifyAssetCallback, callbackData);
            } catch (JSONException exception) {
                exception.printStackTrace();
            }
        }

        //Sending callbacks, depending on delete/download result
        private void onResultCallback(String path, int result) {
            try {
                JSONObject callbackData = new JSONObject();
                callbackData.put("path", path);
                switch(result){
                    case SUCCESS:
                        Log.i(TAG, "Result for " + path + " : SUCCESS");
                        callbackData.put("root", "file://" + PICTURES_ROOT );
                        callbackData.put("status", "success");
                        fragment.sendCallback(mModifyAssetCallback, callbackData);
                        break;
                    case NETWORK_ERROR:
                        Log.i(TAG, "Result for " + path + " : NETWORK_ERROR");
                        callbackData.put("status", "error");
                        callbackData.put("cause", "networkError");
                        fragment.sendCallback(mModifyAssetCallback, callbackData);
                        break;
                    case FILENOTFOUND_ERROR:
                        Log.i(TAG, "Result for " + path + " : FILENOTFOUND_ERROR");
                        callbackData.put("status", "error");
                        callbackData.put("cause", "fileNotFound");
                        fragment.sendCallback(mModifyAssetCallback, callbackData);
                        break;
                    case WRITE_ERROR:
                        Log.i(TAG, "Result for " + path + " : WRITE_ERROR");
                        callbackData.put("status", "error");
                        callbackData.put("cause", "writeError");
                        fragment.sendCallback(mModifyAssetCallback, callbackData);
                        break;
                    case UNKNOWN_ERROR:
                        Log.i(TAG, "Result for " + path + " : UNKNOWN_ERROR");
                        callbackData.put("status", "error");
                        callbackData.put("cause", "unknownError");
                        fragment.sendCallback(mModifyAssetCallback, callbackData);
                        break;
                }
            } catch (JSONException exception) {
                exception.printStackTrace();
            }
        }
    }

}
