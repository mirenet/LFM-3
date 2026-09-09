package com.webhtml.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private String cachedBlobData = null;

    @SuppressLint({"SetJavaScriptEnabled", "QueryPermissionsNeeded"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        );
        super.onCreate(savedInstanceState);
        
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#070707"));
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AppBridge(this), "AndroidBridge");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String rawSuggestedName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            String extension = "txt";
            if (rawSuggestedName != null && rawSuggestedName.contains(".")) {
                extension = rawSuggestedName.substring(rawSuggestedName.lastIndexOf(".") + 1);
            }
            final String suggestedFileName = "download." + extension;

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                cachedBlobData = null;
                String js = "(function() {" +
                        "  fetch('" + url + "')" +
                        "    .then(res => res.blob())" +
                        "    .then(blob => {" +
                        "      var reader = new FileReader();" +
                        "      reader.onload = function() {" +
                        "        window.AndroidBridge.cacheData(reader.result);" +
                        "      };" +
                        "      reader.readAsDataURL(blob);" +
                        "    }).catch(err => window.AndroidBridge.cacheData('ERROR'));" +
                        "})();";
                webView.evaluateJavascript(js, null);

                webView.postDelayed(() -> showNativeDownloadDialog(suggestedFileName, url, mimetype, true), 300);
            } else {
                cachedBlobData = null;
                showNativeDownloadDialog(suggestedFileName, url, mimetype, false);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String urlStr = request.getUrl().toString();
                if (!urlStr.contains("#")) {
                    return super.shouldInterceptRequest(view, request);
                }

                try {
                    String[] mainParts = urlStr.split("#", 2);
                    String cleanUrlStr = mainParts[0];
                    String fragment = mainParts[1];

                    boolean isHtmlMode = fragment.contains("html");
                    boolean isApiMode = fragment.contains("api");

                    String defaultUa;
                    if (isHtmlMode) {
                        defaultUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
                    } else if (isApiMode) {
                        defaultUa = "LFM_MusicApp/1.0 (contact: moj@gmail.com)";
                    } else {
                        defaultUa = webView.getSettings().getUserAgentString();
                    }

                    String finalUa = defaultUa;
                    if (fragment.contains("ua=")) {
                        try {
                            String[] uaParts = fragment.split("ua=");
                            if (uaParts.length > 1) {
                                String customUa = URLDecoder.decode(uaParts[1].split("&")[0], "UTF-8");
                                if (!customUa.isEmpty() && customUa.length() < 300) {
                                    finalUa = customUa;
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    URL url = new URL(cleanUrlStr);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", finalUa);
                    
                    if (isHtmlMode) {
                        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    }
                    
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    InputStream inputStream = connection.getInputStream();
                    String mimeType = connection.getContentType();
                    if (mimeType == null) {
                        mimeType = isHtmlMode ? "text/html; charset=UTF-8" : "application/json; charset=UTF-8";
                    }
                    
                    String encoding = "UTF-8";
                    if (mimeType.contains("charset=")) {
                        try {
                            encoding = mimeType.split("charset=")[1].split(";")[0].trim();
                        } catch (Exception ignored) {}
                    }

                    return new WebResourceResponse(mimeType.split(";")[0].trim(), encoding, inputStream);

                } catch (Exception e) {
                    // Fallback
                }
                
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void showNativeDownloadDialog(String suggestedFileName, String url, String mimetype, boolean isBlob) {
        runOnUiThread(() -> {
            float density = getResources().getDisplayMetrics().density;
            
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padHoriz = (int) (22 * density);
            int padVert = (int) (18 * density);
            layout.setPadding(padHoriz, padVert, padHoriz, padVert);
            
            GradientDrawable backgroundDrawable = new GradientDrawable();
            backgroundDrawable.setColor(Color.parseColor("#0F0F0F"));
            backgroundDrawable.setCornerRadius(16 * density);
            backgroundDrawable.setStroke((int) (1 * density), Color.parseColor("#454835"));
            layout.setBackground(backgroundDrawable);

            TextView titleView = new TextView(this);
            titleView.setText("Save File");
            titleView.setTextColor(Color.parseColor("#CAD967"));
            titleView.setTextSize(17);
            titleView.setGravity(Gravity.CENTER);
            titleView.setPadding(0, 0, 0, (int)(14 * density));
            layout.addView(titleView);

            final EditText input = new EditText(this);
            input.setText(suggestedFileName);
            input.setTextSize(15);
            input.setTextColor(Color.parseColor("#F3F2F6"));
            input.setBackground(null);
            input.setPadding(0, (int)(4 * density), 0, (int)(8 * density));

            LinearLayout inputContainer = new LinearLayout(this);
            inputContainer.setOrientation(LinearLayout.VERTICAL);
            inputContainer.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            
            View bottomLine = new View(this);
            bottomLine.setBackgroundColor(Color.parseColor("#373737"));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (1 * density)
            );
            inputContainer.addView(bottomLine, lineParams);
            
            layout.addView(inputContainer);

            LinearLayout buttonLayout = new LinearLayout(this);
            buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
            buttonLayout.setGravity(Gravity.END);
            buttonLayout.setPadding(0, (int)(18 * density), 0, 0);

            GradientDrawable btnDrawable = new GradientDrawable();
            btnDrawable.setColor(Color.parseColor("#CAD967"));
            btnDrawable.setCornerRadius(8 * density);

            Button closeButton = new Button(this);
            closeButton.setText("Close");
            closeButton.setAllCaps(false);
            closeButton.setTextColor(Color.parseColor("#070707"));
            closeButton.setBackground(btnDrawable);
            closeButton.setTextSize(13);
            
            closeButton.setIncludeFontPadding(false);
            closeButton.setMinWidth(0);
            closeButton.setMinimumWidth(0);
            closeButton.setMinHeight(0);
            closeButton.setMinimumHeight(0);
            closeButton.setPadding((int)(14 * density), (int)(6 * density), (int)(14 * density), (int)(6 * density));

            Button saveButton = new Button(this);
            saveButton.setText("Save");
            saveButton.setAllCaps(false);
            saveButton.setTextColor(Color.parseColor("#070707"));
            
            GradientDrawable saveBtnDrawable = new GradientDrawable();
            saveBtnDrawable.setColor(Color.parseColor("#CAD967"));
            saveBtnDrawable.setCornerRadius(8 * density);
            saveButton.setBackground(saveBtnDrawable);
            saveButton.setTextSize(13);
            
            saveButton.setIncludeFontPadding(false);
            saveButton.setMinWidth(0);
            saveButton.setMinimumWidth(0);
            saveButton.setMinHeight(0);
            saveButton.setMinimumHeight(0);
            saveButton.setPadding((int)(14 * density), (int)(6 * density), (int)(14 * density), (int)(6 * density));

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            btnParams.gravity = Gravity.CENTER_VERTICAL;
            btnParams.setMargins((int)(8 * density), 0, 0, 0);
            
            closeButton.setLayoutParams(btnParams);
            saveButton.setLayoutParams(btnParams);

            buttonLayout.addView(closeButton);
            buttonLayout.addView(saveButton);
            layout.addView(buttonLayout);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(layout)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            closeButton.setOnClickListener(v -> dialog.dismiss());

            saveButton.setOnClickListener(v -> {
                String finalName = input.getText().toString().trim();
                if (finalName.isEmpty()) {
                    finalName = suggestedFileName;
                }
                executeDownloadTask(finalName, url, mimetype, isBlob);
                dialog.dismiss();
            });

            dialog.show();

            if (dialog.getWindow() != null) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.copyFrom(dialog.getWindow().getAttributes());
                
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                lp.width = screenWidth - (int) (80 * density);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                dialog.getWindow().setAttributes(lp);
            }
        });
    }

    private void executeDownloadTask(String fileName, String url, String mimetype, boolean isBlob) {
        final String finalFileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (isBlob) {
            if (cachedBlobData != null && !cachedBlobData.equals("ERROR")) {
                saveBase64ToFile(cachedBlobData, finalFileName);
            } else {
                Toast.makeText(getApplicationContext(), "Error: Blob data not ready or expired.", Toast.LENGTH_LONG).show();
            }
        } else {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                request.setTitle(finalFileName);
                request.setDescription("Downloading file...");
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFileName);
                
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "Download started...", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    public class AppBridge {
        private Context context;
        public AppBridge(Context context) { this.context = context; }

        @JavascriptInterface
        public void cacheData(String data) {
            cachedBlobData = data;
        }
    }

    private void saveBase64ToFile(String base64Data, String name) {
        try {
            String base64Content = base64Data;
            if (base64Data.contains(",")) {
                base64Content = base64Data.split(",")[1];
            }
            byte[] decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT);

            OutputStream os = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (uri != null) {
                    os = resolver.openOutputStream(uri);
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File file = new File(downloadsDir, name);
                os = new java.io.FileOutputStream(file);

                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(file));
                sendBroadcast(scanIntent);
            }

            if (os != null) {
                os.write(decodedBytes);
                os.flush();
                os.close();
                Toast.makeText(getApplicationContext(), "File saved: " + name, Toast.LENGTH_LONG).show();
            } else {
                throw new Exception("Could not open output stream.");
            }
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
