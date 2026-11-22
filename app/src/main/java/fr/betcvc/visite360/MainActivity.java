package fr.betcvc.visite360;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visite 360 betCVC : coque Android autour de la page web embarquee (assets/www).
 * - sert la page et les photos sur une origine https interne (aucun acces Internet requis) ;
 * - dialogue avec la camera Insta360 (protocole OSC, http://192.168.42.1) sur le reseau Wi-Fi ;
 * - range les photos par visite et enregistre les exports dans Telechargements.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final String CAMERA = "http://192.168.42.1";
    private static final int PICK_FILES = 41;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(14, 19, 22));
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Laisse la place aux barres systeme et a l'encoche (affichage bord a bord).
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = ins.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                } else {
                    v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                            ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
                }
                return ins;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(BASE + "index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || !v.contains("1")) {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || fileCallback == null) {
            return;
        }
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                uris = new Uri[n];
                for (int i = 0; i < n; i++) {
                    uris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    // ------------------------------------------------------------------ page et photos

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) {
                return null;
            }
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if (path.startsWith("/photos/")) {
                    String[] p = path.substring(8).split("/");
                    if (p.length == 2 && safeId(p[0]) && safeFile(p[1])) {
                        File f = new File(photosDir(p[0]), p[1]);
                        if (f.isFile()) {
                            return ok("image/jpeg", new FileInputStream(f));
                        }
                    }
                    return notFound();
                }
                String name = path.equals("/") ? "index.html" : path.substring(1);
                if (name.contains("..")) {
                    return notFound();
                }
                return ok(mime(name), getAssets().open("www/" + name));
            } catch (IOException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (HOST.equals(req.getUrl().getHost())) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
            } catch (Exception ignored) {
                // aucun navigateur disponible
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(i, "Photos 360"), PICK_FILES);
            } catch (Exception e) {
                fileCallback = null;
                cb.onReceiveValue(null);
            }
            return true;
        }
    }

    private static WebResourceResponse ok(String mime, InputStream in) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, 200, "OK", h, in);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ fichiers des visites

    private static boolean safeId(String s) {
        return s != null && s.matches("[a-z0-9]{1,40}");
    }

    private static boolean safeFile(String s) {
        return s != null && s.matches("[A-Za-z0-9._-]{1,120}") && !s.contains("..");
    }

    private File tourDir(String id) {
        return new File(new File(getFilesDir(), "visites"), id);
    }

    private File photosDir(String id) {
        return new File(tourDir(id), "photos");
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static String folderName(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
        if (t.length() > 60) t = t.substring(0, 60).trim();
        return t.isEmpty() ? "Visite" : t;
    }

    // ------------------------------------------------------------------ reseau camera

    /** Le Wi-Fi de la camera n'a pas d'acces Internet : on force le passage par le Wi-Fi. */
    private HttpURLConnection open(String url) throws IOException {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        URL u = new URL(url);
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return (HttpURLConnection) n.openConnection(u);
                }
            }
        }
        throw new IOException("le téléphone n'est connecté à aucun réseau Wi-Fi");
    }

    private void reply(final int id, final boolean ok, final String text) {
        final String js = "window.__native&&window.__native(" + id + "," + ok + ","
                + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void progress(final int id, final long done, final long total) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.__nativeProgress&&window.__nativeProgress("
                        + id + "," + done + "," + total + ")", null);
            }
        });
    }

    private static String netError(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException || e instanceof java.net.UnknownHostException) {
            return "connectez le téléphone au Wi-Fi de la caméra, puis réessayez";
        }
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ pont JavaScript

    private final class Bridge {

        /** Requete OSC vers la camera. Reponse asynchrone via window.__native(id, ok, texte). */
        @JavascriptInterface
        public void osc(final int id, final String method, final String path, final String body) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        if (path == null || !path.startsWith("/osc/") || path.contains("..")) {
                            throw new IOException("chemin refusé");
                        }
                        boolean post = "POST".equals(method);
                        c = open(CAMERA + path);
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(20000);
                        c.setRequestMethod(post ? "POST" : "GET");
                        c.setRequestProperty("Accept", "application/json");
                        c.setRequestProperty("X-XSRF-Protected", "1");
                        if (post) {
                            byte[] b = (body == null || body.isEmpty() ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                            c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
                            c.setFixedLengthStreamingMode(b.length);
                            c.setDoOutput(true);
                            try (OutputStream o = c.getOutputStream()) {
                                o.write(b);
                            }
                        }
                        int code = c.getResponseCode();
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        if (in != null) {
                            try {
                                copy(in, out);
                            } finally {
                                in.close();
                            }
                        }
                        String txt = new String(out.toByteArray(), StandardCharsets.UTF_8);
                        if (txt.trim().isEmpty()) {
                            throw new IOException("réponse vide de la caméra (code " + code + ")");
                        }
                        reply(id, true, txt);
                    } catch (Exception e) {
                        reply(id, false, netError(e));
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
        }

        /**
         * Telecharge une photo de la camera, range l'original dans la galerie
         * (Pictures/Visite360/nom de la visite) et cree la copie d'affichage de la visite.
         */
        @JavascriptInterface
        public void fetchPhoto(final int id, final String url, final String tourId, final String tourName,
                               final String name, final int maxW) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    File tmp = new File(getCacheDir(), "camera.tmp");
                    HttpURLConnection c = null;
                    try {
                        if (url == null || !(url.startsWith(CAMERA + "/") || url.startsWith(CAMERA + ":80/"))) {
                            throw new IOException("adresse refusée");
                        }
                        if (!safeId(tourId) || !safeFile(name)) {
                            throw new IOException("nom de fichier refusé");
                        }
                        c = open(url);
                        c.setConnectTimeout(5000);
                        c.setReadTimeout(30000);
                        if (c.getResponseCode() != 200) {
                            throw new IOException("la caméra a répondu " + c.getResponseCode());
                        }
                        long total = c.getContentLength(), done = 0, last = 0;
                        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[131072];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                out.write(buf, 0, n);
                                done += n;
                                long now = System.currentTimeMillis();
                                if (now - last > 250) {
                                    last = now;
                                    progress(id, done, total);
                                }
                            }
                        }
                        if (total > 0 && done != total) {
                            throw new IOException("téléchargement incomplet");
                        }

                        BitmapFactory.Options b = new BitmapFactory.Options();
                        b.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(tmp.getPath(), b);
                        int w = b.outWidth, h = b.outHeight;
                        if (w <= 0 || h <= 0) {
                            throw new IOException("image illisible");
                        }
                        if (Math.abs((double) w / h - 2.0) > 0.06) {
                            throw new IOException("ce n'est pas une photo 360 assemblée (format 2:1 attendu)");
                        }

                        boolean archived = saveOriginal(tmp, folderName(tourName), name);

                        File dir = photosDir(tourId);
                        if (!dir.isDirectory() && !dir.mkdirs()) {
                            throw new IOException("stockage indisponible");
                        }
                        String base = name.replaceAll("\\.[^.]+$", "");
                        File view = new File(dir, base + ".jpg");
                        int limit = Math.max(1024, Math.min(maxW, 8192));
                        int sample = 1;
                        while (w / sample > limit) {
                            sample *= 2;
                        }
                        int vw = w, vh = h;
                        if (sample == 1) {
                            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(view)) {
                                copy(in, out);
                            }
                        } else {
                            Bitmap bmp = null;
                            while (bmp == null) {
                                try {
                                    BitmapFactory.Options o = new BitmapFactory.Options();
                                    o.inSampleSize = sample;
                                    bmp = BitmapFactory.decodeFile(tmp.getPath(), o);
                                    if (bmp == null) {
                                        throw new IOException("image illisible");
                                    }
                                } catch (OutOfMemoryError oom) {
                                    bmp = null;
                                    sample *= 2;
                                    if (sample > 16) {
                                        throw new IOException("mémoire insuffisante pour cette photo");
                                    }
                                }
                            }
                            vw = bmp.getWidth();
                            vh = bmp.getHeight();
                            try (OutputStream out = new FileOutputStream(view)) {
                                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                            } finally {
                                bmp.recycle();
                            }
                        }
                        JSONObject r = new JSONObject();
                        r.put("file", view.getName());
                        r.put("w", vw);
                        r.put("h", vh);
                        r.put("originalW", w);
                        r.put("archived", archived);
                        reply(id, true, r.toString());
                    } catch (Exception e) {
                        reply(id, false, netError(e));
                    } finally {
                        if (c != null) c.disconnect();
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            });
        }

        /** Enregistre un fichier genere par la page dans Telechargements/Visite360. */
        @JavascriptInterface
        public void saveDownload(final int id, final String filename, final String base64) {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!safeFile(filename)) {
                            throw new IOException("nom de fichier refusé");
                        }
                        byte[] data = Base64.decode(base64, Base64.DEFAULT);
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                        v.put(MediaStore.MediaColumns.MIME_TYPE,
                                filename.endsWith(".zip") ? "application/zip" : "application/octet-stream");
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Visite360");
                        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (u == null) {
                            throw new IOException("dossier Téléchargements indisponible");
                        }
                        try (OutputStream o = getContentResolver().openOutputStream(u)) {
                            if (o == null) {
                                throw new IOException("écriture impossible");
                            }
                            o.write(data);
                        }
                        reply(id, true, "ok");
                    } catch (Exception e) {
                        reply(id, false, netError(e));
                    }
                }
            });
        }

        /** Liste des visites enregistrees, la plus recente d'abord : [{id, name, n}]. */
        @JavascriptInterface
        public String listTours() {
            JSONArray arr = new JSONArray();
            File[] dirs = new File(getFilesDir(), "visites").listFiles();
            if (dirs == null) {
                return "[]";
            }
            List<File> files = new ArrayList<>();
            for (File d : dirs) {
                File f = new File(d, "visite.json");
                if (d.isDirectory() && safeId(d.getName()) && f.isFile()) {
                    files.add(f);
                }
            }
            Collections.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (File f : files) {
                try {
                    JSONObject j = new JSONObject(read(f));
                    JSONObject o = new JSONObject();
                    o.put("id", f.getParentFile().getName());
                    o.put("name", j.optString("name", "Visite"));
                    JSONArray p = j.optJSONArray("panos");
                    o.put("n", p == null ? 0 : p.length());
                    arr.put(o);
                } catch (Exception ignored) {
                    // fichier illisible : visite ignoree
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public String loadTour(String id) {
            try {
                if (!safeId(id)) return "";
                File f = new File(tourDir(id), "visite.json");
                return f.isFile() ? read(f) : "";
            } catch (IOException e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveTour(String id, String json) throws IOException {
            if (!safeId(id) || json == null) {
                throw new IOException("visite refusée");
            }
            File dir = tourDir(id);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("stockage indisponible");
            }
            File tmp = new File(dir, "visite.json.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, "visite.json"))) {
                throw new IOException("enregistrement impossible");
            }
        }

        /** Supprime la copie d'affichage d'une photo (l'original reste dans la galerie). */
        @JavascriptInterface
        public void deletePhoto(String tourId, String file) {
            if (safeId(tourId) && safeFile(file)) {
                //noinspection ResultOfMethodCallIgnored
                new File(photosDir(tourId), file).delete();
            }
        }

        @JavascriptInterface
        public void wifiSettings() {
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception ignored) {
                    // reglages indisponibles
                }
            }
        }
    }

    /** Copie l'original dans la galerie : Pictures/Visite360/<visite>/<nom>. */
    private boolean saveOriginal(File src, String folder, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Visite360/" + folder);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) {
                return false;
            }
            try (InputStream in = new FileInputStream(src); OutputStream out = getContentResolver().openOutputStream(u)) {
                if (out == null) {
                    return false;
                }
                copy(in, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
