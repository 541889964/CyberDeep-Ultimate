package com.cyber.deep;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText etInput;
    private TextView tvStatus;
    private GeckoView geckoView;
    private GeckoSession session;
    private static GeckoRuntime runtime;

    private boolean isPageReady = false;
    private boolean isRunning = false;
    private boolean isInjected = false;
    private boolean tokenInjected = false;
    
    private String currentToken = "";
    private SharedPreferences sp;

    private MsgAdapter adapter;
    private final List<Msg> msgList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("cyber", MODE_PRIVATE);

        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        ImageButton btnSend = findViewById(R.id.btnSend);
        geckoView = findViewById(R.id.geckoView);
        tvStatus = findViewById(R.id.tvStatus);
        TextView btnSetToken = findViewById(R.id.btnSetToken);

        adapter = new MsgAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnSetToken.setOnClickListener(v -> showTokenDialog());

        log("App启动，准备加载凭证...");
        new Thread(() -> {
            // 1. 先从本地配置读，如果没有再尝试自动抓火狐
            currentToken = sp.getString("user_token", "");
            if (currentToken.isEmpty()) {
                log("本地无配置，尝试自动提取火狐Token...");
                currentToken = readFirefoxToken();
                if (currentToken != null && !currentToken.isEmpty()) {
                    log("自动提取成功，长度: " + currentToken.length());
                    sp.edit().putString("user_token", currentToken).apply();
                } else {
                    log("自动提取失败，请点击左上角手动设置");
                }
            } else {
                log("已加载本地配置Token");
            }
            
            mainHandler.post(this::initGeckoView);
        }).start();

        btnSend.setOnClickListener(v -> {
            String prompt = etInput.getText().toString().trim();
            if (prompt.isEmpty() || isRunning || !isPageReady) return;
            etInput.setText("");
            addMsg("User", prompt);
            isRunning = true;
            tvStatus.setText("生成中...");
            log("发送指令: " + prompt);
            String js = "javascript:window.cyberSend(" + JSONObject.quote(prompt) + ");";
            session.loadUri(js);
        });
    }

    private void showTokenDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("手动配置 Token");
        final EditText input = new EditText(this);
        input.setText(currentToken);
        input.setHint("在此粘贴你的 Token");
        builder.setView(input);
        builder.setPositiveButton("保存并重载", (dialog, which) -> {
            String newToken = input.getText().toString().trim();
            if (!newToken.isEmpty()) {
                currentToken = newToken;
                sp.edit().putString("user_token", currentToken).apply();
                log("已更新Token，正在重载内核...");
                tokenInjected = false;
                isInjected = false;
                isPageReady = false;
                if (session != null) {
                    session.loadUri("https://chat.deepseek.com");
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private String readFirefoxToken() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls -d /data/data/org.mozilla.firefox/files/mozilla/*.default* 2>/dev/null"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String profilePath = r.readLine();
            if (profilePath == null || profilePath.isEmpty()) return null;
            String srcDb = profilePath + "/storage/default/https+++chat.deepseek.com/ls/data.sqlite";
            File dstDb = new File(getCacheDir(), "ff_ls.db");
            Process cp = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat '" + srcDb + "'"});
            InputStream is = cp.getInputStream();
            FileOutputStream fos = new FileOutputStream(dstDb);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            if (!dstDb.exists() || dstDb.length() == 0) return null;
            SQLiteDatabase db = SQLiteDatabase.openDatabase(dstDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery("SELECT value FROM data WHERE key='userToken'", null);
            String token = null;
            if (c.moveToFirst()) {
                byte[] blob = c.getBlob(0);
                if (blob != null) {
                    String rawStr = new String(blob, "UTF-16LE");
                    Matcher m = Pattern.compile("\"value\":\"([^\"]+)\"").matcher(rawStr);
                    if (m.find()) token = m.group(1);
                    if (token == null) token = rawStr.replaceAll("[^\\x20-\\x7E]", ""); 
                }
            }
            c.close();
            db.close();
            return token;
        } catch (Exception e) {
            log("自动读取异常: " + e.getMessage());
            return null;
        }
    }

    private void initGeckoView() {
        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession session, String title) {
                if (title == null) return;
                
                if (title.contains("DeepSeek") || title.contains("deepseek")) {
                    if (!tokenInjected) {
                        tokenInjected = true;
                        tvStatus.setText("写入凭证...");
                        log("网页加载完成，注入Token...");
                        String js = "javascript:localStorage.setItem('userToken', JSON.stringify({value:'" + currentToken + "', __version:'0'}));";
                        session.loadUri(js);
                        mainHandler.postDelayed(() -> {
                            log("刷新页面以应用Token...");
                            session.reload();
                        }, 300);
                    } else {
                        injectScript();
                    }
                } else if ("READY".equals(title)) {
                    isPageReady = true;
                    tvStatus.setText("内核就绪");
                    log("✅ 隐藏内核就绪，可以开始聊天了！");
                } else if (title.startsWith("REPLY:")) {
                    isRunning = false;
                    tvStatus.setText("就绪");
                    updateLastAiMsg(title.substring(6));
                }
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        
        if (currentToken.isEmpty()) {
            log("⚠️ 暂无Token，只能加载登录页");
        }
        
        log("加载聊天网页...");
        session.loadUri("https://chat.deepseek.com");
        
        mainHandler.postDelayed(() -> {
            if (!tokenInjected) {
                tokenInjected = true;
                log("超时保底注入Token...");
                String js = "javascript:localStorage.setItem('userToken', JSON.stringify({value:'" + currentToken + "', __version:'0'}));";
                session.loadUri(js);
                mainHandler.postDelayed(() -> session.reload(), 300);
            }
        }, 8000);
    }

    private void injectScript() {
        if (isInjected) return;
        isInjected = true;
        log("注入自动化控制脚本...");

        String js =
            "(function() {" +
            "  if (window.cyberHooked) { document.title = 'READY'; return; }" +
            "  window.cyberHooked = true;" +
            "  window.cyberSend = function(text) {" +
            "    let ta = document.querySelector('textarea');" +
            "    if (!ta) { document.title = 'ERR:textarea not found'; return; }" +
            "    let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "    setter.call(ta, text);" +
            "    ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    setTimeout(function() {" +
            "      ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "    }, 300);" +
            "  };" +
            "  let lastText = '';" +
            "  setInterval(function() {" +
            "    let blocks = document.querySelectorAll('.ds-markdown');" +
            "    if (blocks.length > 0) {" +
            "      let latest = blocks[blocks.length - 1].innerText;" +
            "      if (latest !== lastText) { " +
            "        lastText = latest; " +
            "        document.title = 'REPLY:' + latest;" +
            "      }" +
            "    }" +
            "  }, 500);" +
            "  document.title = 'READY';" +
            "})();";
        
        session.loadUri("javascript:" + js);
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            addMsg("System", "[日志] " + msg);
        });
    }

    private void updateLastAiMsg(String fullText) {
        if (msgList.isEmpty() || !"AI".equals(msgList.get(msgList.size() - 1).role)) {
            addMsg("AI", fullText);
        } else {
            Msg last = msgList.get(msgList.size() - 1);
            last.content = fullText;
            adapter.notifyItemChanged(msgList.size() - 1);
            recyclerView.smoothScrollToPosition(msgList.size() - 1);
        }
    }

    private void addMsg(String role, String content) {
        msgList.add(new Msg(role, content));
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.smoothScrollToPosition(msgList.size() - 1);
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            holder.tvContent.setText(msg.content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            
            if ("User".equals(msg.role)) {
                params.gravity = android.view.Gravity.END;
                holder.tvContent.setBackgroundResource(R.drawable.bg_user_bubble);
                holder.tvContent.setTextColor(0xFFFFFFFF);
            } else if ("System".equals(msg.role)) {
                params.gravity = android.view.Gravity.CENTER;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF64748B); // 灰色日志
                holder.tvContent.setTextSize(12f);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF00E5FF);
                holder.tvContent.setTextSize(15f);
            }
            holder.tvContent.setLayoutParams(params);
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvContent; VH(View v) { super(v); tvContent = v.findViewById(R.id.tvContent); } }
    }
    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
}
