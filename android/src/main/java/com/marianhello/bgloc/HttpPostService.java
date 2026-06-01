package com.marianhello.bgloc;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class HttpPostService {
    private static final String TAG = "HttpPostService";
    
    public static final int BUFFER_SIZE = 8192; 
    private static final int TIMEOUT_MS = 30000; 

    private final String mUrl;
    private HttpURLConnection mHttpURLConnection;

    public interface UploadingProgressListener {
        void onProgress(int progress);
    }

    public HttpPostService(@NonNull String url) {
        this.mUrl = url;
    }

    public HttpPostService(@NonNull HttpURLConnection httpURLConnection) {
        this.mHttpURLConnection = httpURLConnection;
        this.mUrl = httpURLConnection.getURL().toString();
    }

    private HttpURLConnection openConnection() throws IOException {
        if (mHttpURLConnection == null) {
            mHttpURLConnection = (HttpURLConnection) new URL(mUrl).openConnection();
            mHttpURLConnection.setConnectTimeout(TIMEOUT_MS);
            mHttpURLConnection.setReadTimeout(TIMEOUT_MS);
        }
        return mHttpURLConnection;
    }

  
    private static void consumeAndCloseStreams(@NonNull HttpURLConnection conn) {
        try (InputStream is = conn.getInputStream()) {
            if (is != null) {
                byte[] discardBuffer = new byte[BUFFER_SIZE];
                while (is.read(discardBuffer) != -1) {
                    // Implicitly draining response bytes cleanly to EOF
                }
            }
        } catch (IOException e) {
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    byte[] discardBuffer = new byte[BUFFER_SIZE];
                    while (es.read(discardBuffer) != -1) {
                        // Consuming error body metrics
                    }
                }
            } catch (IOException ignored) {}
        } finally {
            conn.disconnect();
        }
    }

    public int postJSON(@Nullable JSONObject json, @Nullable Map<String, String> headers) throws IOException {
        return postJSONString(json != null ? json.toString() : "{}", headers);
    }

    public int postJSON(@Nullable JSONArray json, @Nullable Map<String, String> headers) throws IOException {
        return postJSONString(json != null ? json.toString() : "[]", headers);
    }

    public int postJSONString(@NonNull String body, @Nullable Map<String, String> headers) throws IOException {
        Map<String, String> safeHeaders = (headers != null) ? headers : new HashMap<>();
        HttpURLConnection conn = this.openConnection();

        byte[] postData = body.getBytes(StandardCharsets.UTF_8);

        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(postData.length);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData);
            os.flush();
        }

        try {
            int responseCode = conn.getResponseCode();
            consumeAndCloseStreams(conn);
            return responseCode;
        } finally {
            mHttpURLConnection = null; 
        }
    }

    public int postJSONFile(@NonNull File file, @Nullable Map<String, String> headers, @Nullable UploadingProgressListener listener) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return postJSONFile(fis, file.length(), headers, listener);
        }
    }

    public int postJSONFile(@NonNull InputStream stream, long size, @Nullable Map<String, String> headers, @Nullable UploadingProgressListener listener) throws IOException {
        Map<String, String> safeHeaders = (headers != null) ? headers : new HashMap<>();
        HttpURLConnection conn = this.openConnection();

        conn.setDoInput(true);
        conn.setDoOutput(true);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            conn.setFixedLengthStreamingMode(size);
        } else {
            conn.setChunkedStreamingMode(BUFFER_SIZE);
        }
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        for (Map.Entry<String, String> entry : safeHeaders.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        long totalBytesWritten = 0;
        int lastPercentage = -1;

        try (BufferedInputStream bis = new BufferedInputStream(stream);
             BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream())) {
            
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
                totalBytesWritten += bytesRead;

                if (size > 0 && listener != null) {
                    int currentPercentage = (int) ((totalBytesWritten * 100L) / size);
                    if (currentPercentage != lastPercentage) {
                        lastPercentage = currentPercentage;
                        listener.onProgress(currentPercentage);
                    }
                }
            }
            bos.flush(); 
        }

        try {
            int responseCode = conn.getResponseCode();
            consumeAndCloseStreams(conn);
            return responseCode;
        } finally {
            mHttpURLConnection = null;
        }
    }

    public static int postJSON(@NonNull String url, @Nullable JSONObject json, @Nullable Map<String, String> headers) throws IOException {
        return new HttpPostService(url).postJSON(json, headers);
    }

    public static int postJSON(@NonNull String url, @Nullable JSONArray json, @Nullable Map<String, String> headers) throws IOException {
        return new HttpPostService(url).postJSON(json, headers);
    }

    public static int postJSONFile(@NonNull String url, @NonNull File file, @Nullable Map<String, String> headers, @Nullable UploadingProgressListener listener) throws IOException {
        return new HttpPostService(url).postJSONFile(file, headers, listener);
    }
}