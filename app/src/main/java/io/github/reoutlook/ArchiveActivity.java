package io.github.reoutlook;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class ArchiveActivity extends Activity {
    private MailDatabase database;
    private List<CachedMessage> messages;
    private LinearLayout root;
    private WebView detailWebView;
    private boolean showingDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new MailDatabase(this);
        showList();
    }

    private void showList() {
        destroyDetailWebView();
        showingDetail = false;
        messages = database.allMessages();

        root = baseRoot();
        root.addView(header("本地邮件", messages.size() + " 封已缓存", view -> finish()));

        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有缓存邮件\n\n返回 Outlook，打开一封邮件后点击“缓存当前邮件”。");
            empty.setTextColor(Color.DKGRAY);
            empty.setTextSize(16);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(28), dp(28), dp(28));
            root.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ListView list = new ListView(this);
            list.setBackgroundColor(Color.rgb(247, 249, 251));
            list.setDivider(new ColorDrawable(Color.rgb(229, 233, 237)));
            list.setDividerHeight(dp(1));
            list.setPadding(0, dp(6), 0, dp(12));
            list.setClipToPadding(false);
            BaseAdapter adapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return messages.size();
                }

                @Override
                public CachedMessage getItem(int position) {
                    return messages.get(position);
                }

                @Override
                public long getItemId(int position) {
                    return getItem(position).id;
                }

                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    MessageRow row;
                    if (convertView instanceof LinearLayout && convertView.getTag() instanceof MessageRow) {
                        row = (MessageRow) convertView.getTag();
                    } else {
                        row = createMessageRow();
                        convertView = row.root;
                        convertView.setTag(row);
                    }
                    CachedMessage message = getItem(position);
                    row.subject.setText(message.subject);
                    String metadata = message.sender;
                    if (!message.receivedAt.isBlank()) {
                        metadata += "  ·  " + displayDate(message.receivedAt);
                    }
                    row.metadata.setText(metadata);
                    String initial = message.sender.isBlank()
                            ? "?"
                            : message.sender.substring(0, 1).toUpperCase(Locale.getDefault());
                    row.avatar.setText(initial);
                    GradientDrawable avatarBackground = new GradientDrawable();
                    avatarBackground.setShape(GradientDrawable.OVAL);
                    avatarBackground.setColor(avatarColor(message.sender));
                    row.avatar.setBackground(avatarBackground);
                    return convertView;
                }
            };
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> {
                CachedMessage fullMessage = database.message(messages.get(position).id);
                if (fullMessage != null) showDetail(fullMessage);
            });
            root.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
    }

    private void showDetail(CachedMessage message) {
        showingDetail = true;
        root = baseRoot();
        root.addView(header(message.subject, message.sender, view -> showList()));

        if (!message.receivedAt.isBlank()) {
            TextView date = new TextView(this);
            date.setText(displayDate(message.receivedAt));
            date.setTextColor(Color.GRAY);
            date.setTextSize(12);
            date.setPadding(dp(16), dp(8), dp(16), dp(8));
            root.addView(date);
        }

        detailWebView = new WebView(this);
        WebSettings settings = detailWebView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setBlockNetworkLoads(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        detailWebView.setBackgroundColor(Color.WHITE);
        detailWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });

        String html = "<!doctype html><html><head><meta name=\"viewport\" " +
                "content=\"width=device-width,initial-scale=1\"><style>" +
                "body{font-family:sans-serif;color:#242424;margin:16px;overflow-wrap:anywhere}" +
                "img{max-width:100%;height:auto}pre{white-space:pre-wrap}a{color:#0f6cbd}" +
                "</style></head><body>" + message.bodyHtml + "</body></html>";
        detailWebView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null);
        root.addView(detailWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private LinearLayout baseRoot() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(Color.rgb(247, 249, 251));
        WindowStyling.apply(this, view);
        return view;
    }

    private View header(String titleValue, String subtitleValue, View.OnClickListener backAction) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(3), dp(12), dp(3));
        bar.setBackgroundColor(Color.rgb(15, 108, 189));

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setTextColor(Color.WHITE);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(backAction);
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleValue);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitle = new TextView(this);
        subtitle.setText(subtitleValue);
        subtitle.setTextColor(Color.rgb(220, 235, 248));
        subtitle.setTextSize(12);
        subtitle.setMaxLines(1);
        labels.addView(title);
        labels.addView(subtitle);
        bar.addView(labels, new LinearLayout.LayoutParams(0, dp(46), 1));
        return bar;
    }

    private MessageRow createMessageRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setMinimumHeight(dp(82));

        TextView avatar = new TextView(this);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(17);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        avatarParams.setMarginEnd(dp(14));
        row.addView(avatar, avatarParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        TextView subject = new TextView(this);
        subject.setTextColor(Color.rgb(30, 35, 40));
        subject.setTextSize(16);
        subject.setTypeface(null, android.graphics.Typeface.BOLD);
        subject.setMaxLines(2);
        subject.setEllipsize(TextUtils.TruncateAt.END);
        TextView metadata = new TextView(this);
        metadata.setTextColor(Color.rgb(103, 110, 118));
        metadata.setTextSize(13);
        metadata.setMaxLines(1);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(subject);
        text.addView(metadata);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return new MessageRow(row, avatar, subject, metadata);
    }

    private static int avatarColor(String sender) {
        int[] colors = {
                Color.rgb(15, 108, 189), Color.rgb(126, 87, 194),
                Color.rgb(0, 137, 123), Color.rgb(198, 83, 31),
                Color.rgb(173, 20, 87), Color.rgb(46, 125, 50)
        };
        int hash = sender == null ? 0 : sender.hashCode();
        return colors[Math.floorMod(hash, colors.length)];
    }

    private static final class MessageRow {
        final LinearLayout root;
        final TextView avatar;
        final TextView subject;
        final TextView metadata;

        MessageRow(LinearLayout root, TextView avatar, TextView subject, TextView metadata) {
            this.root = root;
            this.avatar = avatar;
            this.subject = subject;
            this.metadata = metadata;
        }
    }

    @Override
    public void onBackPressed() {
        if (showingDetail) showList();
        else finish();
    }

    private void destroyDetailWebView() {
        if (detailWebView == null) return;
        detailWebView.stopLoading();
        detailWebView.removeAllViews();
        detailWebView.destroy();
        detailWebView = null;
    }

    @Override
    protected void onDestroy() {
        destroyDetailWebView();
        database.close();
        super.onDestroy();
    }

    private static String displayDate(String value) {
        try {
            return OffsetDateTime.parse(value).format(
                    DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.getDefault()));
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
