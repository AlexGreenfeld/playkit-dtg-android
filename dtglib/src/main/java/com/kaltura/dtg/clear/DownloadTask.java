package com.kaltura.dtg.clear;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.util.Log;

import com.kaltura.dtg.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by noamt on 5/13/15.
 */
class DownloadTask implements Parcelable {
    static final String TAG = "DownloadTask";
    private static final int HTTP_READ_TIMEOUT_MS = 10000;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 15000;
    private static final int PROGRESS_REPORT_COUNT = 20;

    // TODO: Hold url and targetFile as Strings, only convert to URL/File when used.
    final String taskId;
    final URL url;
    final File targetFile;
    String itemId;
    String trackRelativeId;

    @Override
    public String toString() {
        return "<DownloadTask id='" + taskId + "' url='" + url + "' target='" + targetFile + "'>";
    }

    private boolean createParentDir(File targetFile) {
        File parent = targetFile.getParentFile();
        return parent.mkdirs() || parent.isDirectory();
    }
    
    void download(Listener listener) {

        URL url = this.url;
        File targetFile = this.targetFile;
        Log.d(TAG, "Task " + taskId + ": download " + url + " to " + targetFile);
        
        // Create parent dir if needed
        if (!createParentDir(targetFile)) {
            Log.e(TAG, "Can't create parent dir");
            listener.onTaskProgress(taskId, State.ERROR, 0);
            return;
        }
        
        listener.onTaskProgress(taskId, State.IN_PROGRESS, 0);
        long remoteFileSize;
        try {
            remoteFileSize = Utils.httpHeadGetLength(url);
        } catch (InterruptedIOException e) {
            Log.d(TAG, "Task " + taskId + " interrupted (1)");
            listener.onTaskProgress(taskId, State.STOPPED, 0);
            return;
        } catch (IOException e) {
            Log.e(TAG, "HEAD request failed for " + url, e);
            remoteFileSize = -1;
        }

        long localFileSize = targetFile.exists() ? targetFile.length() : 0;

        // finish before even starting, if file is already complete.
        if (localFileSize == remoteFileSize) {
            // We're done.
            listener.onTaskProgress(taskId, State.COMPLETED, 0);
            return;
        } else if (localFileSize > remoteFileSize) {
            // This is really odd. Delete and try again.
            Log.w(TAG, "Target file is longer than remote. Deleting the target.");
            if (!targetFile.delete()) {
                Log.w(TAG, "Can't delete targetFile");
            }
            localFileSize = 0;
        }

        // Start the actual download.
        InputStream inputStream = null;
        HttpURLConnection conn = null;
        FileOutputStream fileOutputStream = null;
        
        State stopReason = null;

        int progressReportBytes = 0;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            conn.setDoInput(true);

            if (localFileSize > 0) {
                // Resume
                conn.setRequestProperty("Range", "Bytes=" + localFileSize + "-");
            }
            conn.connect();

            int response = conn.getResponseCode();
            if (response >= 400) {
                throw new IOException(Utils.format("Response code for %s is %d", url, response));
            }

            inputStream = conn.getInputStream();
            fileOutputStream = new FileOutputStream(targetFile, true);

            byte[] buffer = new byte[10240]; // 10k buffer

            int byteCount;
            progressReportBytes = 0;
            int progressReportCounter = 0;
            while (true) {
                byteCount = inputStream.read(buffer);

                progressReportCounter++;

                if (byteCount < 0) {
                    // EOF
                    break;
                }

                if (byteCount > 0) {
                    fileOutputStream.write(buffer, 0, byteCount);
                    progressReportBytes += byteCount;
                }

                if (progressReportBytes > 0 && progressReportCounter >= PROGRESS_REPORT_COUNT) {
                    Log.v(TAG, "progressReportBytes:" + progressReportBytes + "; progressReportCounter:" + progressReportCounter);
                    listener.onTaskProgress(taskId, State.IN_PROGRESS, progressReportBytes);
                    progressReportBytes = 0;
                    progressReportCounter = 0;
                }
            }

            stopReason = State.COMPLETED;

        } catch (InterruptedIOException e) {
            // Not an error -- task is cancelled.
            Log.d(TAG, "Task " + taskId + " interrupted");
            stopReason = State.STOPPED;

        } catch (IOException e) {
            Log.d(TAG, "Task " + taskId + " failed", e);
            stopReason = State.ERROR;

        } finally {
            Utils.safeClose(inputStream, fileOutputStream);
            if (conn != null) {
                conn.disconnect();
            }

            // Maybe some bytes are still waiting to be reported
            if (progressReportBytes > 0) {
                listener.onTaskProgress(taskId, State.IN_PROGRESS, progressReportBytes);
            }
            if (stopReason != null) {
                listener.onTaskProgress(taskId, stopReason, 0);
            }
        }
    }
    
    interface Listener {
        void onTaskProgress(String taskId, State newState, int newBytes);
    }
    
    enum State {
        IDLE, IN_PROGRESS, COMPLETED, STOPPED, ERROR;
        private final static State[] values = values();
        static State fromOrdinal(int ordinal) {
            return values[ordinal];
        }
    }

    DownloadTask(URL url, File targetFile) {
        this.url = url;
        this.targetFile = targetFile;
        this.taskId = Utils.md5Hex(targetFile.getAbsolutePath());
    }

    DownloadTask(String url, String targetFile) throws MalformedURLException {
        this(new URL(url), new File(targetFile));
    }

    @SuppressLint("ParcelClassLoader")
    private DownloadTask(Parcel in) throws MalformedURLException {
        this(in.readBundle());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        Bundle bundle = toBundle();

        dest.writeBundle(bundle); 
    }

    @NonNull
    private Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("itemId", itemId);
        bundle.putString("targetFile", targetFile.toString());
        bundle.putString("url", url.toString());
        return bundle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<DownloadTask> CREATOR = new Creator<DownloadTask>() {
        @Override
        public DownloadTask createFromParcel(Parcel in) {
            try {
                return new DownloadTask(in);
            } catch (MalformedURLException e) {
                Log.e(TAG, "Can't create DownloadTask from bundle", e);
                return null;
            }
        }

        @Override
        public DownloadTask[] newArray(int size) {
            return new DownloadTask[size];
        }
    };
    
    private DownloadTask(Bundle bundle) throws MalformedURLException {
        this(bundle.getString("url"), bundle.getString("targetFile"));
        this.itemId = bundle.getString("itemId");
    }
    
    @Override
    public boolean equals(Object o) {
        if (o instanceof DownloadTask) {
            DownloadTask otherTask = (DownloadTask) o;
            return this.url.equals(otherTask.url) && this.targetFile.equals(otherTask.targetFile);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int code = 17;
        code = 31 * code + (this.url == null ? 0 : this.url.hashCode());
        code = 31 * code + (this.targetFile == null ? 0 : this.targetFile.hashCode());
        return code;
    }
}
