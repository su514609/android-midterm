package com.example.android.notepad;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 笔记剪贴板工具类（整篇笔记的完整复制 / 粘贴）。
 *
 * 原始版本的「复制」只是把一条 content:// URI 放进剪贴板，粘贴时再回查数据库，
 * 只能拿回标题和正文，分类、便签背景色都会丢失，而且原笔记一旦被删除就再也粘贴不出来。
 *
 * 这里改成「快照」方案，复制时把标题、正文、分类、便签颜色打包成 JSON：
 *
 * 1. 剪贴板里放一条可读的纯文本（标题 + 正文），粘贴到别的应用里也是正常文字；
 * 2. 完整快照同时写进剪贴板的多条目里，并备份到本应用的 SharedPreferences。
 *
 * 这样即使系统 / 模拟器把剪贴板裁剪成单条文本（部分模拟器会与主机剪贴板同步，
 * 只保留第一条），粘贴时仍能凭文本比对找回分类和背景色。
 */
public final class NoteClipboard {

    private static final String TAG = "NoteClipboard";

    /** 剪贴板标签，用于在系统粘贴提示里显示来源 */
    private static final String CLIP_LABEL = "记事本笔记";

    /** 完整快照的前缀，用于和普通文本区分 */
    private static final String PAYLOAD_PREFIX = "##NOTEPAD_NOTE_V1##";

    /** 备份完整快照用的 SharedPreferences */
    private static final String PREFS_NAME = "notepad_clipboard";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_TEXT = "text";

    /** 复制时需要的列 */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_CATEGORY,
            NotePad.Notes.COLUMN_NAME_COLOR,
    };

    private static final int INDEX_TITLE = 0;
    private static final int INDEX_NOTE = 1;
    private static final int INDEX_CATEGORY = 2;
    private static final int INDEX_COLOR = 3;

    private NoteClipboard() {
    }

    /**
     * 把一篇笔记完整复制到剪贴板。
     *
     * @param noteUri 单条笔记的 content URI
     * @return 复制成功返回 true
     */
    public static boolean copy(Context context, Uri noteUri) {
        if (context == null || noteUri == null) {
            return false;
        }

        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return false;
        }

        String title;
        String note;
        String category;
        int color;

        Cursor cursor = context.getContentResolver().query(
                noteUri, PROJECTION, null, null, null);
        if (cursor == null) {
            return false;
        }
        try {
            if (!cursor.moveToFirst()) {
                return false;
            }
            title = safeString(cursor.getString(INDEX_TITLE));
            note = safeString(cursor.getString(INDEX_NOTE));
            category = safeString(cursor.getString(INDEX_CATEGORY));
            color = cursor.getInt(INDEX_COLOR);
        } finally {
            cursor.close();
        }

        if (TextUtils.isEmpty(category)) {
            category = NotePad.Notes.DEFAULT_CATEGORY;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
            payload.put(NotePad.Notes.COLUMN_NAME_NOTE, note);
            payload.put(NotePad.Notes.COLUMN_NAME_CATEGORY, category);
            payload.put(NotePad.Notes.COLUMN_NAME_COLOR, color);
        } catch (JSONException e) {
            Log.e(TAG, "failed to build clipboard payload", e);
            return false;
        }
        String payloadText = payload.toString();

        // 可读文本：标题 + 正文，粘贴到其它应用时也是正常内容
        StringBuilder readable = new StringBuilder();
        if (!TextUtils.isEmpty(title)) {
            readable.append(title).append('\n');
        }
        readable.append(note);
        String readableText = readable.toString();

        // 备份到本地，防止系统把多条目剪贴板裁剪成单条文本
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAYLOAD, payloadText)
                .putString(KEY_TEXT, readableText)
                .apply();

        ClipData clip = new ClipData(
                new ClipDescription(CLIP_LABEL,
                        new String[] { ClipDescription.MIMETYPE_TEXT_PLAIN }),
                new ClipData.Item(readableText));
        // 第二条：完整快照（含分类与背景色），支持多条目时直接从这里读取
        clip.addItem(new ClipData.Item(PAYLOAD_PREFIX + payloadText));

        clipboard.setPrimaryClip(clip);
        return true;
    }

    /**
     * 读取剪贴板里的完整笔记快照。
     *
     * @return 含 title / note / category / color 的 ContentValues；剪贴板里没有笔记快照时返回 null
     */
    public static ContentValues readSnapshot(Context context) {
        ClipData clip = primaryClip(context);
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }

        // 1) 优先从多条目剪贴板里直接取快照
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence text = clip.getItemAt(i).getText();
            if (text == null) {
                continue;
            }
            String raw = text.toString();
            if (raw.startsWith(PAYLOAD_PREFIX)) {
                ContentValues values = parse(raw.substring(PAYLOAD_PREFIX.length()));
                if (values != null) {
                    return values;
                }
            }
        }

        // 2) 剪贴板被裁剪成单条文本时，用文本比对找回本地快照
        CharSequence first = clip.getItemAt(0).getText();
        if (first == null) {
            return null;
        }
        String clipText = first.toString();

        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedText = prefs.getString(KEY_TEXT, null);
        String savedPayload = prefs.getString(KEY_PAYLOAD, null);
        if (savedText != null && savedPayload != null && savedText.equals(clipText)) {
            return parse(savedPayload);
        }
        return null;
    }

    /** 剪贴板里是否有本应用复制过的笔记快照 */
    public static boolean hasNote(Context context) {
        return readSnapshot(context) != null;
    }

    /**
     * 读取剪贴板里的普通文本（不是笔记快照时使用，例如从别的应用复制了一段文字）。
     */
    public static String readPlainText(Context context) {
        ClipData clip = primaryClip(context);
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        return text == null ? null : text.toString();
    }

    /** 把快照 JSON 解析成 ContentValues */
    private static ContentValues parse(String json) {
        try {
            JSONObject payload = new JSONObject(json);

            String category = payload.optString(
                    NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.DEFAULT_CATEGORY);
            if (TextUtils.isEmpty(category)) {
                category = NotePad.Notes.DEFAULT_CATEGORY;
            }

            ContentValues values = new ContentValues();
            values.put(NotePad.Notes.COLUMN_NAME_TITLE,
                    payload.optString(NotePad.Notes.COLUMN_NAME_TITLE, ""));
            values.put(NotePad.Notes.COLUMN_NAME_NOTE,
                    payload.optString(NotePad.Notes.COLUMN_NAME_NOTE, ""));
            values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, category);
            values.put(NotePad.Notes.COLUMN_NAME_COLOR,
                    payload.optInt(NotePad.Notes.COLUMN_NAME_COLOR, 0));
            return values;
        } catch (JSONException e) {
            Log.e(TAG, "failed to parse clipboard payload", e);
            return null;
        }
    }

    private static ClipData primaryClip(Context context) {
        if (context == null) {
            return null;
        }
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        return clipboard.getPrimaryClip();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }
}
