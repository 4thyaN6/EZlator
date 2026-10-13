package com.winlator.cmod.steam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.winlator.cmod.R;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AllFilesAccess;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Steam page inside MainActivity. Replaces SteamLoginActivity so the
 * screen has the same toolbar / hamburger as the rest of the app.
 * Signed out: the sign-in form (password, Steam Guard, QR) - same flow as before.
 * Signed in : the owned-game library in the same tile / list layout as Main,
 *             with capsule thumbnails. The owned list and the profile are cached
 *             on the device, so the page opens instantly; "Update" re-reads them.
 * MainActivity 안의 스팀 페이지임. SteamLoginActivity를 대체해서 앱의 다른
 * 화면과 같은 툴바 / 햄버거 메뉴를 가짐.
 * 로그아웃 상태: 로그인 양식(비밀번호, Steam Guard, QR) - 흐름은 예전과 같음.
 * 로그인 상태  : 보유 게임 라이브러리를 메인과 같은 타일 / 목록 구성과 캡슐 썸네일로
 *                보여 줌. 보유 목록과 프로필은 기기에 저장해 두므로 페이지가 바로
 *                열리고, "업데이트"를 누르면 다시 읽음
 */
public class SteamFragment extends Fragment {
    private static final String TAG = "SteamFragment";
    private static final String PREF_VIEW_MODE = "steam_view_mode";
    private static final String MODE_TILE = "tile";
    private static final String MODE_LIST = "list";

    // Shared across page instances: a download keeps running if the user
    // leaves the page, and a second one must not start meanwhile
    // 페이지 인스턴스끼리 공유함: 사용자가 페이지를 나가도 다운로드는 계속되고,
    // 그동안 두 번째 다운로드가 시작되면 안 됨
    private static volatile boolean busy;
    private static SteamSession session;

    // Session shared with SteamUpdateChecker (same UI process). Blocking: call
    // from a background thread. null when signed out or the sign-in fails
    // SteamUpdateChecker와 함께 쓰는 세션임(같은 UI 프로세스). 동기식이라
    // 백그라운드 스레드에서 불러야 함. 로그아웃 상태거나 로그인 실패면 null
    // A kept session that Steam has logged off (the same account logged on again
    // from this device, e.g. a save export or the download process) is closed and
    // opened again; it used to be handed out as it was and every request failed
    // 스팀이 로그오프한 보관 세션(같은 계정이 이 기기에서 다시 로그온함, 예: 세이브
    // 내보내기, 다운로드 프로세스)은 닫고 다시 엶. 예전에는 그대로 내줘서 모든 요청이
    // 실패했음
    public static synchronized SteamSession sharedSession(Context context) {
        if (session != null && session.isAlive()) return session;
        // While the download process is signed in, a new log on here would make
        // Steam log that one off and break the download: no session until it ends
        // 다운로드 프로세스가 로그온해 있는 동안 여기서 새로 로그온하면 스팀이 그쪽을
        // 로그오프해 다운로드가 깨짐: 다운로드가 끝날 때까지 세션을 주지 않음
        if (SteamDownloadState.read(context).running) {
            Log.i(TAG, "shared session not opened: a download is signed in");
            return null;
        }
        if (session != null) {
            Log.i(TAG, "shared session was logged off, opening it again");
            session.close();
            session = null;
        }
        SteamAccountStore st = new SteamAccountStore(context);
        if (!st.isLoggedIn()) return null;
        try {
            SteamSession s = new SteamSession();
            s.open(st);
            session = s;
        } catch (Exception e) {
            Log.w(TAG, "shared session failed", e);
        }
        return session;
    }

    private final SteamLoginManager loginManager = new SteamLoginManager();
    private SteamAccountStore store;
    private ExecutorService thumbPool;

    private View loginView, libraryView;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    // Download panel bound to the background download service
    // 백그라운드 다운로드 서비스에 연결된 다운로드 창
    private SteamDownloadPanel dlPanel;
    private TextView tvStatus, tvLibraryStatus, tvEmpty, tvHeaderNick;
    // Spinner in front of the library status line (shown while deleting a game)
    // 라이브러리 상태 줄 앞의 스피너(게임을 지우는 동안 보임)
    private View pbBusy;
    private Button btnLogin;
    private EditText etSearch;
    private RecyclerView recyclerView;
    private ImageView ivHeaderAvatar;
    private View toolbarHeader, viewToggle, profileHeader;
    private View btViewTile, btViewList;
    private AlertDialog storageDialog;

    private String viewMode = MODE_TILE;
    private final List<SteamSession.OwnedGame> allGames = new ArrayList<>();
    private final GamesAdapter adapter = new GamesAdapter();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.steam_fragment, container, false);
        Context context = requireContext();
        store = new SteamAccountStore(context);
        thumbPool = Executors.newFixedThreadPool(3);
        viewMode = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_VIEW_MODE, MODE_TILE);

        loginView = view.findViewById(R.id.SVSteamLogin);
        libraryView = view.findViewById(R.id.LLSteamLibrary);
        tvStatus = view.findViewById(R.id.TVSteamStatus);
        tvLibraryStatus = view.findViewById(R.id.TVSteamLibraryStatus);
        pbBusy = view.findViewById(R.id.PBSteamBusy);
        // Page opened again while a delete is still running: show it again
        // 삭제가 아직 도는 중에 페이지를 다시 열면 진행 표시를 다시 보여 줌
        liveInstance = this;
        if (deletingAppId != 0) {
            pbBusy.setVisibility(View.VISIBLE);
            tvLibraryStatus.setText(getString(R.string.steam_manage_deleting, deletingName));
        }
        tvEmpty = view.findViewById(R.id.TVSteamEmpty);
        btnLogin = view.findViewById(R.id.BTSteamLogin);
        etSearch = view.findViewById(R.id.ETSteamSearch);
        recyclerView = view.findViewById(R.id.RVSteamGames);
        recyclerView.setAdapter(adapter);
        applyLayoutManager();

        setupLoginForm(view);

        view.findViewById(R.id.BTSteamUpdate).setOnClickListener(v -> loadLibrary(true));
        swipeRefresh = view.findViewById(R.id.SRLSteamGames);
        swipeRefresh.setOnRefreshListener(() -> loadLibrary(true));

        dlPanel = new SteamDownloadPanel(view.findViewById(R.id.LLSteamDownload), 0);
        dlPanel.setListener(result -> {
            if (result != null && !result.isEmpty()) tvLibraryStatus.setText(result);
            // A finished game moves into the "Installed" section
            // 다 받은 게임은 "설치됨" 구역으로 옮겨감
            applyFilter();
        });
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { applyFilter(); }
        });

        // Toolbar action slot: Tile/List chips + profile block (same slot the
        // Main screen uses for its chips; removed again in onDestroyView)
        // 툴바 동작 슬롯: 타일/목록 칩 + 프로필 묶음(메인 화면이 칩을 넣는 그 슬롯임.
        // onDestroyView에서 다시 뺌)
        ViewGroup slot = requireActivity().findViewById(R.id.FLToolbarActions);
        toolbarHeader = inflater.inflate(R.layout.steam_toolbar_header, slot, false);
        slot.addView(toolbarHeader);
        viewToggle = toolbarHeader.findViewById(R.id.LLSteamViewToggle);
        profileHeader = toolbarHeader.findViewById(R.id.LLSteamProfileHeader);
        tvHeaderNick = toolbarHeader.findViewById(R.id.TVSteamHeaderNick);
        ivHeaderAvatar = toolbarHeader.findViewById(R.id.IVSteamHeaderAvatar);
        ivHeaderAvatar.setClipToOutline(true);
        btViewTile = toolbarHeader.findViewById(R.id.BTViewTile);
        btViewList = toolbarHeader.findViewById(R.id.BTViewList);
        btViewTile.setOnClickListener(v -> setViewMode(MODE_TILE));
        btViewList.setOnClickListener(v -> setViewMode(MODE_LIST));
        profileHeader.setOnClickListener(v -> confirmLogout());
        applyViewModeChips();

        refresh();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The token may have been refused elsewhere meanwhile (download, achievements,
        // update check): the library is still shown, so switch to the sign-in form
        // 그동안 다른 곳(다운로드, 도전과제, 업데이트 확인)에서 토큰이 거부됐을 수 있음:
        // 라이브러리가 그대로 보이면 로그인 양식으로 바꿈
        if (store != null && libraryView != null
                && libraryView.getVisibility() == View.VISIBLE && !store.isLoggedIn()) {
            session = null;
            refresh();
        }
        if (getActivity() instanceof AppCompatActivity && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.steam_account);
        }
        // Downloads need "All files access"; without it the notice comes back
        // and Back leaves the page (same rule as the old activity)
        // 다운로드에는 "모든 파일 접근"이 필요함. 없으면 안내창이 다시 뜨고, 뒤로가기는
        // 페이지를 나감(예전 액티비티와 같은 규칙)
        if (AllFilesAccess.isGranted()) {
            if (storageDialog != null && storageDialog.isShowing()) storageDialog.dismiss();
            return;
        }
        if (storageDialog == null || !storageDialog.isShowing()) {
            storageDialog = AllFilesAccess.showEnableDialog(requireActivity(), () -> {
                Activity a = getActivity();
                if (a != null) a.onBackPressed();
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        dlPanel.start();
    }

    @Override
    public void onStop() {
        dlPanel.stop();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (liveInstance == this) liveInstance = null;
        if (storageDialog != null && storageDialog.isShowing()) storageDialog.dismiss();
        if (toolbarHeader != null && toolbarHeader.getParent() instanceof ViewGroup) {
            ((ViewGroup) toolbarHeader.getParent()).removeView(toolbarHeader);
        }
        toolbarHeader = null;
        loginManager.cancel();
        if (thumbPool != null) thumbPool.shutdownNow();
        super.onDestroyView();
    }

    // Runs on the UI thread only while the page is still attached
    // 페이지가 아직 붙어 있을 때만 UI 스레드에서 실행함
    private void ui(Runnable r) {
        Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (isAdded() && getView() != null) r.run();
        });
    }

    // ==================== Sign-in (same flow as the old activity) ====================
    // ==================== 로그인 (예전 액티비티와 같은 흐름) ====================

    private void setupLoginForm(View view) {
        EditText etUser = view.findViewById(R.id.ETSteamUser);
        EditText etPass = view.findViewById(R.id.ETSteamPassword);
        btnLogin.setOnClickListener(v -> {
            String user = etUser.getText().toString();
            String pass = etPass.getText().toString();
            if (user.trim().isEmpty() || pass.isEmpty()) return;
            btnLogin.setEnabled(false);
            tvStatus.setText(R.string.steam_signing_in);
            loginManager.login(user, pass, store, new SteamLoginManager.Listener() {
                @Override
                public void onCodeRequired(boolean email, boolean wrong, CompletableFuture<String> code) {
                    ui(() -> askCode(email, wrong, code));
                }

                @Override
                public void onConfirmInMobileApp() {
                    ui(() -> tvStatus.setText(R.string.steam_confirm_in_app));
                }

                @Override
                public void onSuccess(String accountName, long steamId64) {
                    ui(() -> {
                        etPass.setText("");
                        refresh();
                    });
                }

                @Override
                public void onFailed(String message) {
                    ui(() -> {
                        btnLogin.setEnabled(true);
                        tvStatus.setText(getString(R.string.steam_login_failed, message));
                    });
                }
            });
        });

        // QR sign-in: shows a code for the Steam mobile app to scan
        // QR 로그인: 스팀 모바일 앱으로 찍을 코드를 보여 줌
        ImageView ivQr = view.findViewById(R.id.IVSteamQr);
        view.findViewById(R.id.BTSteamQr).setOnClickListener(v -> {
            tvStatus.setText(R.string.steam_qr_hint);
            ivQr.setVisibility(View.VISIBLE);
            loginManager.loginWithQr(store, new SteamLoginManager.QrListener() {
                @Override
                public void onQrUrl(String url) {
                    ui(() -> ivQr.setImageBitmap(makeQr(url)));
                }

                @Override
                public void onSuccess(String accountName, long steamId64) {
                    ui(() -> {
                        ivQr.setVisibility(View.GONE);
                        refresh();
                    });
                }

                @Override
                public void onFailed(String message) {
                    ui(() -> {
                        ivQr.setVisibility(View.GONE);
                        tvStatus.setText(getString(R.string.steam_login_failed, message));
                    });
                }
            });
        });
    }

    // Steam Guard code prompt / Steam Guard 코드 입력창
    private void askCode(boolean email, boolean wrong, CompletableFuture<String> code) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        int msg = email ? R.string.steam_guard_email : R.string.steam_guard_app;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.steam_guard)
                .setMessage(wrong ? getString(R.string.steam_guard_wrong) + "\n" + getString(msg) : getString(msg))
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (d, w) -> code.complete(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    code.cancel(true);
                    loginManager.cancel();
                })
                .show();
    }

    // Challenge URL -> QR bitmap (ZXing core) / challenge URL -> QR 비트맵(ZXing core)
    private static Bitmap makeQr(String url) {
        try {
            int size = 720;
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++)
                for (int y = 0; y < size; y++)
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // Switches between the sign-in form and the library
    // 로그인 양식과 라이브러리 사이를 전환함
    private void refresh() {
        boolean in = store.isLoggedIn();
        loginView.setVisibility(in ? View.GONE : View.VISIBLE);
        libraryView.setVisibility(in ? View.VISIBLE : View.GONE);
        viewToggle.setVisibility(in ? View.VISIBLE : View.GONE);
        profileHeader.setVisibility(in ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(true);
        // Not signed in: say so, or that Steam refused the saved token (expired / revoked)
        // 로그인 안 됨: 그렇다고 표시하거나, 스팀이 저장된 토큰을 거부했다고(만료 / 취소) 표시함
        tvStatus.setText(in ? "" : getString(store.isTokenRejected()
                ? R.string.steam_token_expired : R.string.steam_not_signed_in));
        if (!in) {
            allGames.clear();
            adapter.setItems(new ArrayList<>());
            return;
        }
        loadProfile();
        loadLibrary(false);
        ensureSteamLite();
    }

    // Tapping the profile picture asks first, then signs out and forgets the
    // cached list / profile of this account
    // 프로필 사진을 누르면 먼저 물어본 뒤 로그아웃하고, 이 계정의 저장된 목록 /
    // 프로필도 지움
    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.steam_logout)
                .setMessage(R.string.steam_logout_confirm)
                .setPositiveButton(R.string.steam_logout, (d, w) -> {
                    FileUtils.delete(cacheDir(requireContext(), store.getSteamId64()));
                    store.clear();
                    session = null;
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ==================== Profile + owned-list cache ====================
    // ==================== 프로필 + 보유 목록 캐시 ====================

    // Per-account cache folder (files dir, so Android does not clear it)
    // 계정별 캐시 폴더(files 폴더라 안드로이드가 지우지 않음)
    private static File cacheDir(Context context, long steamId64) {
        return new File(context.getFilesDir(), "steam_cache/" + steamId64);
    }

    // Cached nickname / avatar first (instant), then a fresh fetch updates both
    // 저장된 닉네임 / 사진을 먼저 보여 주고(즉시), 새로 받아 오면 둘 다 갱신함
    private void loadProfile() {
        final Context app = requireContext().getApplicationContext();
        final long sid = store.getSteamId64();
        final File dir = cacheDir(app, sid);
        final File nickFile = new File(dir, "nickname.txt");
        final File avatarFile = new File(dir, "avatar.png");
        String cachedNick = nickFile.isFile() ? FileUtils.readString(nickFile) : null;
        tvHeaderNick.setText(cachedNick != null ? cachedNick.trim() : "…");
        if (avatarFile.isFile()) ivHeaderAvatar.setImageBitmap(BitmapFactory.decodeFile(avatarFile.getPath()));

        new Thread(() -> {
            SteamProfileFetcher.Profile p = SteamProfileFetcher.fetch(sid);
            if (p == null) return;
            dir.mkdirs();
            if (p.nickname != null) FileUtils.writeString(nickFile, p.nickname);
            if (p.avatar != null) {
                try (FileOutputStream out = new FileOutputStream(avatarFile)) {
                    p.avatar.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Exception e) {
                    Log.w(TAG, "avatar cache write failed", e);
                }
            }
            ui(() -> {
                if (p.nickname != null) tvHeaderNick.setText(p.nickname);
                if (p.avatar != null) ivHeaderAvatar.setImageBitmap(p.avatar);
            });
        }).start();
    }

    // force=false: show the cached owned list when there is one (no network).
    // force=true ("Update") or no cache yet: read it from Steam and store it
    // force=false: 저장된 보유 목록이 있으면 그걸 보여 줌(네트워크 안 씀).
    // force=true("업데이트")이거나 아직 캐시가 없으면 스팀에서 읽고 저장함
    private void loadLibrary(boolean force) {
        final Context app = requireContext().getApplicationContext();
        final long sid = store.getSteamId64();
        final File listFile = new File(cacheDir(app, sid), "owned_games.json");

        if (!force && listFile.isFile()) {
            List<SteamSession.OwnedGame> cached = readOwnedCache(listFile);
            if (cached != null) {
                showGames(cached);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                tvLibraryStatus.setText(getString(R.string.steam_cached_list,
                        android.text.format.DateFormat.getDateFormat(app).format(new java.util.Date(listFile.lastModified()))
                                + " " + android.text.format.DateFormat.getTimeFormat(app).format(new java.util.Date(listFile.lastModified()))));
                return;
            }
        }
        if (busy || SteamDownloadState.read(requireContext()).running) {
            // The session is in use by a download; the cached list stays
            // 세션을 다운로드가 쓰는 중이라 저장된 목록을 그대로 둠
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        busy = true;
        tvLibraryStatus.setText(R.string.steam_loading_games);
        new Thread(() -> {
            try {
                // Same rule as sharedSession(): a logged-off session is opened again
                // sharedSession()과 같은 규칙: 로그오프된 세션은 다시 엶
                if (session != null && !session.isAlive()) {
                    session.close();
                    session = null;
                }
                if (session == null) {
                    SteamSession s = new SteamSession();
                    s.open(store);
                    session = s;
                }
                List<SteamSession.OwnedGame> games = session.listOwnedGames();
                writeOwnedCache(listFile, games);
                ui(() -> {
                    showGames(games);
                    tvLibraryStatus.setText(getString(R.string.steam_owned_games, games.size()));
                    swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                Log.w(TAG, "owned games failed", e);
                session = null;
                // Token refused: back to the sign-in form with the expired message
                // 토큰 거부: 만료 안내와 함께 로그인 양식으로 돌아감
                if (e instanceof SteamSession.TokenRejectedException) {
                    ui(() -> {
                        swipeRefresh.setRefreshing(false);
                        refresh();
                    });
                    return;
                }
                ui(() -> {
                    tvLibraryStatus.setText(getString(R.string.steam_login_failed, String.valueOf(e.getMessage())));
                    swipeRefresh.setRefreshing(false);
                });
            } finally {
                busy = false;
            }
        }).start();
    }

    private static List<SteamSession.OwnedGame> readOwnedCache(File file) {
        try {
            JSONArray arr = new JSONArray(FileUtils.readString(file));
            List<SteamSession.OwnedGame> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new SteamSession.OwnedGame(o.getInt("id"), o.getString("name")));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "owned cache unreadable", e);
            return null;
        }
    }

    private static void writeOwnedCache(File file, List<SteamSession.OwnedGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (SteamSession.OwnedGame g : games) {
                arr.put(new JSONObject().put("id", g.appId).put("name", g.name));
            }
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            FileUtils.writeString(file, arr.toString());
        } catch (Exception e) {
            Log.w(TAG, "owned cache write failed", e);
        }
    }

    private void showGames(List<SteamSession.OwnedGame> games) {
        allGames.clear();
        allGames.addAll(games);
        Collections.sort(allGames, (a, b) -> a.name.compareToIgnoreCase(b.name));
        applyFilter();
    }

    // Games already downloaded go to their own section at the end, under an
    // "Installed (n)" header. Installed = its appmanifest exists in
    // the EZlator Steam library
    // 이미 받은 게임은 끝쪽의 별도 구역으로 "설치됨 (n)" 머리글 아래에 모음.
    // 설치됨 = EZlator 스팀 라이브러리에 appmanifest가 있음
    private boolean isInstalled(int appId) {
        // Either library (internal or SD card) / 어느 라이브러리든(내장 또는 SD카드)
        return SteamGameInstaller.libraryOf(requireContext(), appId) != null;
    }

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Object> notInstalled = new ArrayList<>();
        List<Object> installed = new ArrayList<>();
        for (SteamSession.OwnedGame g : allGames) {
            if (!q.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (isInstalled(g.appId)) installed.add(g);
            else notInstalled.add(g);
        }
        List<Object> shown = new ArrayList<>(notInstalled);
        if (!installed.isEmpty()) {
            shown.add(getString(R.string.steam_installed_header, installed.size()));
            shown.addAll(installed);
        }
        adapter.setItems(shown);
        tvEmpty.setVisibility(shown.isEmpty() && !busy ? View.VISIBLE : View.GONE);
    }

    // ==================== Tile / list (same as Main) ====================
    // ==================== 타일 / 목록 (메인과 같음) ====================

    private void setViewMode(String mode) {
        viewMode = mode;
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_VIEW_MODE, mode).apply();
        applyViewModeChips();
        applyLayoutManager();
        recyclerView.setAdapter(adapter);
    }

    private void applyViewModeChips() {
        if (btViewTile != null) btViewTile.setSelected(MODE_TILE.equals(viewMode));
        if (btViewList != null) btViewList.setSelected(MODE_LIST.equals(viewMode));
    }

    // Same column rule as ShortcutsFragment.applyLayoutManager(): 115dp, 2..5
    // ShortcutsFragment.applyLayoutManager()와 같은 열 규칙: 115dp, 2~5열
    private void applyLayoutManager() {
        if (MODE_TILE.equals(viewMode)) {
            // Same tiles-per-row option as the main screen
            // 메인 화면과 같은 줄당 타일 수 옵션
            final int span = com.winlator.cmod.ShortcutsFragment.gridSpan(requireContext());
            GridLayoutManager glm = new GridLayoutManager(recyclerView.getContext(), span);
            // Section headers take the whole row / 구역 머리글은 한 줄 전체를 차지함
            glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return adapter.getItemViewType(position) == 2 ? span : 1;
                }
            });
            recyclerView.setLayoutManager(glm);
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        }
    }

    private class GamesAdapter extends RecyclerView.Adapter<GamesAdapter.Holder> {
        // OwnedGame rows plus String section headers / OwnedGame 행과 String 구역 머리글
        private List<Object> items = new ArrayList<>();

        void setItems(List<Object> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            if (items.get(position) instanceof String) return 2;
            return MODE_TILE.equals(viewMode) ? 0 : 1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 2) {
                TextView header = new TextView(parent.getContext());
                int pad = (int) (10 * parent.getResources().getDisplayMetrics().density);
                header.setPadding(pad, pad * 2, pad, pad / 2);
                header.setTextSize(14);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                android.util.TypedValue tv = new android.util.TypedValue();
                parent.getContext().getTheme().resolveAttribute(R.attr.neonAccent, tv, true);
                header.setTextColor(tv.data);
                header.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new Holder(header);
            }
            int layout = viewType == 0 ? R.layout.neon_shortcut_tile_item : R.layout.neon_shortcut_list_item;
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Object item = items.get(position);
            if (item instanceof String) {
                ((TextView) h.itemView).setText((String) item);
                return;
            }
            SteamSession.OwnedGame game = (SteamSession.OwnedGame) item;
            h.name.setText(game.name);
            if (h.subtitle != null) h.subtitle.setText("AppID " + game.appId);
            if (h.menu != null) h.menu.setVisibility(View.GONE);
            h.itemView.setOnClickListener(v -> confirmDownload(game));
            bindThumb(h, game.appId);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover, icon;
            final TextView name, subtitle;
            final View menu;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.IVCover);
                icon = v.findViewById(R.id.IVIcon);
                name = v.findViewById(R.id.TVName);
                subtitle = v.findViewById(R.id.TVSubtitle);
                menu = v.findViewById(R.id.BTMenu);
            }
        }
    }

    // ==================== Thumbnails ====================
    // ==================== 썸네일 ====================

    // Memory cache -> disk cache -> Steam (SteamArtwork). The cover's tag guards against
    // a recycled row receiving another game's picture
    // 메모리 캐시 -> 디스크 캐시 -> 스팀 CDN 순임. 재사용된 행에 다른 게임의 그림이
    // 들어가지 않게 커버의 태그로 확인함
    private void bindThumb(GamesAdapter.Holder h, int appId) {
        h.cover.setTag(appId);
        // SteamArtwork (current asset paths + store API fallback)
        // SteamArtwork 사용(현재 아트워크 경로 + 상점 API 대체)
        Bitmap cached = SteamArtwork.cachedCapsule(appId);
        if (cached != null) {
            h.cover.setImageBitmap(cached);
            h.icon.setVisibility(View.GONE);
            return;
        }
        h.cover.setImageDrawable(null);
        h.icon.setVisibility(View.VISIBLE);
        h.icon.setImageResource(R.drawable.ic_app_logo);
        if (thumbPool == null || thumbPool.isShutdown()) return;
        final Context app = requireContext().getApplicationContext();
        thumbPool.execute(() -> {
            Bitmap bmp = SteamArtwork.loadCapsule(app, appId);
            if (bmp == null) return;
            ui(() -> {
                if (Integer.valueOf(appId).equals(h.cover.getTag())) {
                    h.cover.setImageBitmap(bmp);
                    h.icon.setVisibility(View.GONE);
                }
            });
        });
    }

    // ==================== Download (same flow as before) ====================
    // ==================== 다운로드 (예전과 같은 흐름) ====================

    // Same size as the shortcut detail sheet; the game's wide Steam
    // artwork (hero, else header) is the background; Back (left) / Download (right)
    // 숏컷 상세창과 같은 크기. 게임의 넓은 스팀 아트워크(히어로, 없으면 헤더)를
    // 배경으로 쓰고, 뒤로가기(왼쪽) / 받기(오른쪽)를 둠
    private void confirmDownload(SteamSession.OwnedGame game) {
        Context context = requireContext();
        android.app.Dialog dialog = new android.app.Dialog(context);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.steam_download_confirm);
        ImageView background = dialog.findViewById(R.id.IVConfirmBackground);
        ((TextView) dialog.findViewById(R.id.TVConfirmName)).setText(game.name);
        boolean installed = isInstalled(game.appId);
        ((TextView) dialog.findViewById(R.id.TVConfirmInfo)).setText(installed
                ? getString(R.string.steam_installed) + " · AppID " + game.appId
                : "AppID " + game.appId);

        Bitmap wide = SteamArtwork.cachedWide(game.appId);
        if (wide != null) background.setImageBitmap(wide);
        else if (thumbPool != null && !thumbPool.isShutdown()) {
            final Context app = context.getApplicationContext();
            thumbPool.execute(() -> {
                Bitmap b = SteamArtwork.loadWide(app, game.appId);
                if (b != null) ui(() -> { if (dialog.isShowing()) background.setImageBitmap(b); });
            });
        }

        Button btDownload = dialog.findViewById(R.id.BTConfirmDownload);
        btDownload.setText(installed ? R.string.steam_redownload : R.string.steam_download);
        btDownload.setOnClickListener(v -> {
            dialog.dismiss();
            download(game);
        });
        View btBack = dialog.findViewById(R.id.BTConfirmBack);
        btBack.setOnClickListener(v -> dialog.dismiss());
        // Installed game: Delete goes between Back and Re-download, for a game
        // whose files turned out broken even after verification
        // 설치된 게임: 뒤로가기와 다시 받기 사이에 삭제를 둠. 검증을 거쳤는데도 파일이
        // 손상된 것으로 드러난 게임을 위한 것임
        if (installed) addDeleteButton(dialog, btBack, btDownload, game);

        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // The download runs in SteamDownloadService (own process,
    // notification, keeps going in the background and across game exits).
    // The service signs in to Steam with its own session
    // 다운로드는 SteamDownloadService(자체 프로세스, 알림, 백그라운드와 게임
    // 종료 후에도 계속됨)에서 돎. 서비스는 자기 세션으로 스팀에 따로 로그인함
    // Built in code and placed right before the Download button in the same row,
    // copying the look of Back (or Download when Back is not a text button).
    // The button row is expected to be a LinearLayout; if it is not, the button
    // is left out and a warning is logged rather than breaking the dialog
    // 코드로 만들어서 같은 줄의 받기 버튼 바로 앞에 넣고, 모양은 뒤로가기(뒤로가기가
    // 글자 버튼이 아니면 받기)를 따라 함. 버튼 줄은 LinearLayout이어야 하며, 아니면
    // 창을 망가뜨리지 않고 버튼을 빼고 경고 로그만 남김
    private void addDeleteButton(android.app.Dialog dialog, View btBack, Button btDownload, SteamSession.OwnedGame game) {
        if (!(btDownload.getParent() instanceof android.widget.LinearLayout)) {
            Log.w(TAG, "download dialog button row is not a LinearLayout; delete button skipped");
            return;
        }
        android.widget.LinearLayout row = (android.widget.LinearLayout) btDownload.getParent();
        Context context = dialog.getContext();
        TextView look = btBack instanceof TextView ? (TextView) btBack : btDownload;

        Button del = new androidx.appcompat.widget.AppCompatButton(context);
        del.setText(R.string.steam_manage_delete);
        // Same look as the source button / 원본 버튼과 같은 모양
        android.graphics.drawable.Drawable bg = look.getBackground();
        if (bg != null && bg.getConstantState() != null) {
            del.setBackground(bg.getConstantState().newDrawable(context.getResources()).mutate());
        }
        androidx.core.view.ViewCompat.setBackgroundTintList(del, androidx.core.view.ViewCompat.getBackgroundTintList(look));
        del.setTextColor(look.getTextColors());
        del.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, look.getTextSize());
        del.setTypeface(look.getTypeface());
        del.setTransformationMethod(look.getTransformationMethod());
        del.setPadding(look.getPaddingLeft(), look.getPaddingTop(), look.getPaddingRight(), look.getPaddingBottom());
        del.setMinHeight(look.getMinHeight());
        del.setMinimumHeight(look.getMinimumHeight());
        del.setElevation(look.getElevation());
        del.setStateListAnimator(look.getStateListAnimator() != null ? look.getStateListAnimator().clone() : null);

        // Size / weight / margins from the Download button (it is in this row for sure)
        // 크기 / 비율 / 여백은 받기 버튼 것을 씀(받기 버튼은 확실히 이 줄에 있음)
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                (android.widget.LinearLayout.LayoutParams) btDownload.getLayoutParams());
        row.addView(del, row.indexOfChild(btDownload), lp);

        del.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(game);
        });
    }

    // Deleting removes many GB from shared storage, so it always asks first.
    // Refused while this game downloads or any game runs (files may be in use)
    // 삭제는 공용 저장소에서 수 GB를 지우므로 항상 먼저 물어봄. 이 게임을 받는 중이거나
    // 게임이 실행 중이면 거절함(파일을 쓰고 있을 수 있음)
    private void confirmDelete(SteamSession.OwnedGame game) {
        Context context = requireContext();
        SteamDownloadState current = SteamDownloadState.read(context);
        if ((current.isActive() && current.appId == game.appId) || SteamDownloadState.isGameRunning(context)
                || deletingAppId != 0) {
            tvLibraryStatus.setText(getString(R.string.steam_manage_delete_busy, game.name));
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.steam_manage_delete)
                .setMessage(getString(R.string.steam_manage_delete_confirm, game.name))
                .setPositiveButton(R.string.steam_manage_delete, (d, w) -> deleteGame(game))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // App id being deleted (0 = none). Static like busy: the page can be left
    // and opened again while a large folder is still being deleted
    // 삭제 중인 앱 id임(0 = 없음). busy처럼 static임: 큰 폴더를 지우는 동안 페이지를
    // 나갔다가 다시 열 수 있음
    private static volatile int deletingAppId = 0;
    // Name shown in the status line while deleting, and the page instance that
    // is on screen now (the delete thread reports to it, not to a page that was
    // closed and reopened in the meantime)
    // 삭제 중 상태 줄에 보일 이름과, 지금 화면에 있는 페이지 인스턴스임(삭제 스레드는
    // 그 사이 닫았다 다시 연 예전 페이지가 아니라 이쪽으로 결과를 알림)
    private static volatile String deletingName = "";
    private static volatile SteamFragment liveInstance;

    private void deleteGame(SteamSession.OwnedGame game) {
        final Context app = requireContext().getApplicationContext();
        deletingAppId = game.appId;
        deletingName = game.name;
        tvLibraryStatus.setText(getString(R.string.steam_manage_deleting, game.name));
        pbBusy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String msg;
            try {
                SteamGameInstaller.uninstall(app, game.appId);
                msg = app.getString(R.string.steam_manage_deleted, game.name);
            } catch (Exception e) {
                Log.w(TAG, "delete failed", e);
                msg = app.getString(R.string.steam_manage_delete_failed, String.valueOf(e.getMessage()));
            } finally {
                deletingAppId = 0;
            }
            final String result = msg;
            SteamFragment page = liveInstance;
            if (page == null) return;
            page.ui(() -> {
                page.pbBusy.setVisibility(View.GONE);
                page.tvLibraryStatus.setText(result);
                // The game moves back out of the "Installed" section
                // 게임이 "설치됨" 구역 밖으로 다시 옮겨감
                page.applyFilter();
            });
        }, "SteamDelete").start();
    }

    private void download(SteamSession.OwnedGame game) {
        // A game still being deleted cannot be downloaded into the same folder yet
        // 아직 지우는 중인 게임은 같은 폴더로 받을 수 없음
        if (deletingAppId == game.appId) {
            tvLibraryStatus.setText(getString(R.string.steam_manage_deleting, game.name));
            return;
        }
        SteamDownloadState current = SteamDownloadState.read(requireContext());
        if (current.isActive() && current.appId != game.appId) {
            tvLibraryStatus.setText(getString(R.string.steam_dl_busy, current.name));
            return;
        }
        // Room is checked first; only when it is short the user picks
        // close / delete / move (SteamSpaceCheck)
        // 먼저 공간을 확인함. 모자랄 때만 사용자가 닫기 / 삭제하기 / 옮기기를 고름
        // (SteamSpaceCheck)
        SteamSpaceCheck.startDownload(requireActivity(), game.appId, game.name, () -> {
            if (isAdded()) dlPanel.refresh();
        });
    }

    // Fetch the SteamLite agent once, right after sign-in
    // 로그인 직후 SteamLite 에이전트를 한 번 받음
    private void ensureSteamLite() {
        if (SteamLiteManager.isInstalled(requireContext())) return;
        final Context app = requireContext().getApplicationContext();
        tvLibraryStatus.setText(R.string.steamlite_installing);
        new Thread(() -> {
            boolean ok = SteamLiteManager.install(app);
            ui(() -> tvLibraryStatus.setText(ok ? R.string.steamlite_ready : R.string.steamlite_failed));
        }).start();
    }
}
