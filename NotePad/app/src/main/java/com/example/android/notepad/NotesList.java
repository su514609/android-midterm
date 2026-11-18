/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Displays a list of notes.
 *
 * 在原始版本基础上扩展了以下能力：
 * 1. 列表条目显示含「年月日」的完整修改时间戳；
 * 2. 支持按标题或内容关键字查询笔记；
 * 3. 支持按分类筛选笔记；
 * 4. 支持切换列表排序方式（修改时间 / 标题 / 分类）；
 * 5. 每条笔记常驻「编辑」「删除」图标，不必长按；
 * 6. 长按笔记只保留「复制」一项；
 * 7. 在空白区域按鼠标右键（或长按空列表）可直接「粘贴笔记」；
 * 8. 复制 / 粘贴是整篇笔记的完整复制，包含标题、正文、分类与便签背景色。
 */
public class NotesList extends AppCompatActivity {

    // For logging and debugging
    private static final String TAG = "NotesList";

    /** 用于保存用户偏好的 SharedPreferences 名称 */
    public static final String PREFS_NAME = "notepad_settings";

    /** 排序方式在 SharedPreferences 中的键 */
    private static final String PREF_SORT_ORDER = "sort_order";

    /** 排序方式：修改时间倒序 */
    private static final int SORT_MODIFIED_DESC = 0;
    /** 排序方式：修改时间正序 */
    private static final int SORT_MODIFIED_ASC = 1;
    /** 排序方式：标题升序 */
    private static final int SORT_TITLE_ASC = 2;
    /** 排序方式：分类 */
    private static final int SORT_CATEGORY_ASC = 3;

    /** 连续两次右键事件的最小间隔，避免一次右键弹出两个菜单 */
    private static final long RIGHT_CLICK_INTERVAL = 350L;

    /**
     * The columns needed by the cursor adapter
     * 除了标题，还需要修改时间、分类、便签颜色
     */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,                              // 0
            NotePad.Notes.COLUMN_NAME_TITLE,                // 1
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,    // 2
            NotePad.Notes.COLUMN_NAME_CATEGORY,             // 3
            NotePad.Notes.COLUMN_NAME_COLOR,                // 4
    };

    /** SimpleCursorAdapter 需要用到的列名与视图 ID（实际绑定在 bindView 中完成） */
    private static final String[] FROM_COLUMNS = { NotePad.Notes.COLUMN_NAME_TITLE };
    private static final int[] TO_VIEWS = { R.id.note_title };

    /** 当前搜索关键字，空串表示没有搜索 */
    private String mSearchText = "";

    /** 当前选中的分类，"全部"表示不筛选 */
    private String mCategory = NotePad.Notes.CATEGORY_ALL;

    private SharedPreferences mPrefs;
    private ListView mNoteList;
    private TextView mEmptyText;
    private NoteAdapter mAdapter;

    /** 上一次处理鼠标右键的时间，用于去重 */
    private long mLastRightClickTime = 0L;

    /**
     * onCreate is called when Android starts this Activity from scratch.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notes_list);

        // 使用 MaterialToolbar 作为标题栏
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        mNoteList = findViewById(android.R.id.list);
        mEmptyText = findViewById(R.id.empty_text);

        // 列表为空时显示空状态提示
        mNoteList.setEmptyView(findViewById(R.id.empty_view));

        /*
         * 长按与点击都绑定在「卡片」本身（见 NoteAdapter#bindView），
         * 不再依赖 ListView 的 OnItemClick / OnItemLongClick：
         * 条目里含有可点击的图标按钮时，ListView 会把整行的点击判定为不可用。
         */

        // 列表为空时，长按空状态区域同样可以粘贴笔记
        View emptyView = findViewById(R.id.empty_view);
        if (emptyView != null) {
            emptyView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    int[] location = new int[2];
                    v.getLocationOnScreen(location);
                    showBlankMenu(location[0] + v.getWidth() / 2,
                            location[1] + v.getHeight() / 2);
                    return true;
                }
            });
        }

        // 创建适配器，游标先置空，稍后由 requery() 填充
        mAdapter = new NoteAdapter(this, null, FROM_COLUMNS, TO_VIEWS);
        mNoteList.setAdapter(mAdapter);

        // 顶部分类筛选标签
        setupCategoryChips();

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 与菜单中的"新建笔记"相同，通过 ACTION_INSERT 打开 NoteEditor
                startActivity(new Intent(Intent.ACTION_INSERT, NotePad.Notes.CONTENT_URI));
            }
        });

        requery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑器返回时重新查询，保证时间戳、分类、颜色都是最新的
        requery();
    }

    /**
     * 初始化顶部的分类筛选标签："全部" + 所有分类。
     */
    private void setupCategoryChips() {
        ChipGroup group = findViewById(R.id.category_group);

        List<String> categories = new ArrayList<>();
        categories.add(NotePad.Notes.CATEGORY_ALL);
        Collections.addAll(categories, NotePad.Notes.CATEGORIES);

        for (String name : categories) {
            Chip chip = new Chip(this);
            chip.setText(name);
            chip.setCheckable(true);
            chip.setClickable(true);
            chip.setChecked(name.equals(mCategory));
            chip.setId(View.generateViewId());
            chip.setTag(name);
            chip.setTextSize(13);
            // 统一的标签配色：选中为主题浅蓝底 + 主题色文字
            chip.setChipBackgroundColor(getColorStateList(R.color.chip_background));
            chip.setTextColor(getColorStateList(R.color.chip_text));
            chip.setChipStrokeColor(getColorStateList(R.color.chip_stroke_color));
            chip.setChipStrokeWidth(getResources().getDisplayMetrics().density);
            group.addView(chip);
        }

        group.setOnCheckedStateChangeListener(new ChipGroup.OnCheckedStateChangeListener() {
            @Override
            public void onCheckedChanged(ChipGroup chipGroup, List<Integer> checkedIds) {
                if (checkedIds.isEmpty()) {
                    return;
                }
                Chip chip = chipGroup.findViewById(checkedIds.get(0));
                if (chip != null) {
                    mCategory = String.valueOf(chip.getTag());
                    requery();
                }
            }
        });
    }

    /**
     * 按当前的搜索关键字、分类筛选条件和排序方式重新查询笔记，并刷新列表。
     *
     * 查询条件通过 ContentResolver.query() 的 selection / selectionArgs 参数下发给
     * NotePadProvider，由 SQLite 完成过滤，避免把所有数据取到内存后再筛选。
     */
    private void requery() {
        StringBuilder selection = new StringBuilder();
        List<String> args = new ArrayList<>();

        // 扩展：根据标题或内容模糊查询
        if (!TextUtils.isEmpty(mSearchText)) {
            selection.append("(")
                    .append(NotePad.Notes.COLUMN_NAME_TITLE).append(" LIKE ? OR ")
                    .append(NotePad.Notes.COLUMN_NAME_NOTE).append(" LIKE ?)");
            String like = "%" + mSearchText + "%";
            args.add(like);
            args.add(like);
        }

        // 扩展：按分类筛选
        if (!NotePad.Notes.CATEGORY_ALL.equals(mCategory)) {
            if (selection.length() > 0) {
                selection.append(" AND ");
            }
            selection.append(NotePad.Notes.COLUMN_NAME_CATEGORY).append(" = ?");
            args.add(mCategory);
        }

        Cursor cursor = getContentResolver().query(
                NotePad.Notes.CONTENT_URI,
                PROJECTION,
                selection.length() == 0 ? null : selection.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                currentSortOrder());

        if (cursor == null) {
            Log.e(TAG, "query failed");
            return;
        }

        // changeCursor() 会关闭旧游标，避免内存泄漏
        mAdapter.changeCursor(cursor);
        updateEmptyHint();
    }

    /**
     * 根据用户选择的排序方式返回 SQLite 的 ORDER BY 子句。
     */
    private String currentSortOrder() {
        int sort = mPrefs.getInt(PREF_SORT_ORDER, SORT_MODIFIED_DESC);
        switch (sort) {
            case SORT_MODIFIED_ASC:
                return NotePad.Notes.SORT_MODIFIED_ASC;
            case SORT_TITLE_ASC:
                return NotePad.Notes.SORT_TITLE_ASC;
            case SORT_CATEGORY_ASC:
                return NotePad.Notes.SORT_CATEGORY_ASC;
            case SORT_MODIFIED_DESC:
            default:
                return NotePad.Notes.SORT_MODIFIED_DESC;
        }
    }

    /**
     * 列表为空时，根据是否存在筛选条件给出不同的提示文字。
     */
    private void updateEmptyHint() {
        boolean filtering = !TextUtils.isEmpty(mSearchText)
                || !NotePad.Notes.CATEGORY_ALL.equals(mCategory);
        mEmptyText.setText(filtering ? R.string.empty_search : R.string.empty_notes);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        // 扩展：把搜索框配置进标题栏
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setMaxWidth(Integer.MAX_VALUE);
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    mSearchText = query == null ? "" : query.trim();
                    requery();
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    // 输入过程中即时查询
                    mSearchText = newText == null ? "" : newText.trim();
                    requery();
                    return true;
                }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    // 收起搜索框时清除关键字，恢复完整列表
                    mSearchText = "";
                    requery();
                    return true;
                }
            });
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // 剪贴板里有内容（笔记快照或其他文字）时，「粘贴」才可用
        MenuItem pasteItem = menu.findItem(R.id.menu_paste);
        if (pasteItem != null) {
            pasteItem.setEnabled(hasClipboardContent());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_add) {
            /*
             * 通过 ACTION_INSERT 启动 NoteEditor，由它插入一条空笔记后进入编辑状态。
             */
            startActivity(new Intent(Intent.ACTION_INSERT, NotePad.Notes.CONTENT_URI));
            return true;
        } else if (id == R.id.menu_paste) {
            pasteNote();
            return true;
        } else if (id == R.id.menu_sort) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 扩展：排序方式选择对话框。
     */
    private void showSortDialog() {
        final int current = mPrefs.getInt(PREF_SORT_ORDER, SORT_MODIFIED_DESC);
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_sort)
                .setSingleChoiceItems(R.array.sort_options, current,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mPrefs.edit().putInt(PREF_SORT_ORDER, which).apply();
                                dialog.dismiss();
                                requery();
                            }
                        })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /* ------------------------------------------------------------------
     * 列表条目上的常驻操作
     * ------------------------------------------------------------------ */

    /** 打开（编辑）某条笔记 */
    private void openNote(long noteId) {
        Uri uri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, noteId);
        startActivity(new Intent(Intent.ACTION_EDIT, uri));
    }

    /** 删除某条笔记（先弹确认框，避免误触） */
    private void confirmDelete(final long noteId, String title) {
        String shown = TextUtils.isEmpty(title) ? getString(R.string.untitled_note) : title;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(getString(R.string.dialog_delete_message, shown))
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_delete_confirm,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Uri uri = ContentUris.withAppendedId(
                                        NotePad.Notes.CONTENT_URI, noteId);
                                getContentResolver().delete(uri, null, null);
                                requery();
                                Toast.makeText(NotesList.this, R.string.toast_note_deleted,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    /* ------------------------------------------------------------------
     * 复制 / 粘贴
     * ------------------------------------------------------------------ */

    /** 把整篇笔记（标题、正文、分类、背景色）复制到剪贴板 */
    private void copyNote(long noteId) {
        Uri uri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, noteId);
        if (NoteClipboard.copy(this, uri)) {
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 从剪贴板粘贴一篇新笔记。
     * 若剪贴板里是本应用复制过的笔记，则完整还原标题 / 正文 / 分类 / 背景色；
     * 否则把剪贴板里的普通文字作为一篇新笔记插入。
     */
    private void pasteNote() {
        ContentValues values = NoteClipboard.readSnapshot(this);

        if (values == null) {
            String text = NoteClipboard.readPlainText(this);
            if (TextUtils.isEmpty(text)) {
                Toast.makeText(this, R.string.toast_nothing_to_paste,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String derived = NoteUtils.deriveTitle(text);
            values = new ContentValues();
            values.put(NotePad.Notes.COLUMN_NAME_TITLE,
                    TextUtils.isEmpty(derived) ? getString(R.string.untitled_note) : derived);
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
            values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.DEFAULT_CATEGORY);
            values.put(NotePad.Notes.COLUMN_NAME_COLOR, 0);
        }

        try {
            Uri newUri = getContentResolver().insert(NotePad.Notes.CONTENT_URI, values);
            if (newUri == null) {
                Toast.makeText(this, R.string.toast_nothing_to_paste,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            requery();
            Toast.makeText(this, R.string.toast_pasted, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "paste note failed", e);
            Toast.makeText(this, R.string.toast_nothing_to_paste, Toast.LENGTH_SHORT).show();
        }
    }

    /** 剪贴板里是否有任何可粘贴的内容 */
    private boolean hasClipboardContent() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        return clipboard != null && clipboard.hasPrimaryClip();
    }

    /* ------------------------------------------------------------------
     * 鼠标右键：在空白处弹出「粘贴笔记」
     * ------------------------------------------------------------------ */

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isSecondaryButtonPress(ev)) {
            showBlankMenu((int) ev.getRawX(), (int) ev.getRawY());
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    /** 判断是否是鼠标右键按下 */
    private boolean isSecondaryButtonPress(MotionEvent ev) {
        if (ev == null) {
            return false;
        }
        int action = ev.getActionMasked();
        // 鼠标按键按下会走 ACTION_BUTTON_PRESS；部分设备/模拟器只上报 ACTION_DOWN
        if (action != MotionEvent.ACTION_BUTTON_PRESS && action != MotionEvent.ACTION_DOWN) {
            return false;
        }
        return ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY);
    }

    /**
     * 在鼠标右键位置弹出浮动菜单。
     * 右键落在某条笔记上时会多一项「复制笔记」。
     *
     * @param screenX 屏幕坐标 X（ev.getRawX()）
     * @param screenY 屏幕坐标 Y（ev.getRawY()）
     */
    private void showBlankMenu(int screenX, int screenY) {
        // 判断右键位置是否落在某条笔记上
        long noteId = -1L;
        int[] location = new int[2];
        mNoteList.getLocationOnScreen(location);
        int localX = screenX - location[0];
        int localY = screenY - location[1];
        if (localX >= 0 && localY >= 0
                && localX <= mNoteList.getWidth() && localY <= mNoteList.getHeight()) {
            int position = mNoteList.pointToPosition(localX, localY);
            if (position != AdapterView.INVALID_POSITION) {
                noteId = mAdapter.getItemId(position);
            }
        }
        showPopup(screenX, screenY, noteId, false);
    }

    /** 长按某条笔记：只弹出「复制笔记」一项 */
    private void showItemMenu(View anchor, long noteId) {
        if (anchor == null || noteId <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        showPopup(location[0] + (int) (36 * density),
                location[1] + anchor.getHeight() / 2, noteId, true);
    }

    /**
     * 构建并显示浮动菜单。
     *
     * @param noteId   命中的笔记 id，小于等于 0 表示没命中任何笔记
     * @param copyOnly true 时只显示「复制笔记」（长按场景）
     */
    private void showPopup(int screenX, int screenY, long noteId, boolean copyOnly) {
        long now = System.currentTimeMillis();
        if (now - mLastRightClickTime < RIGHT_CLICK_INTERVAL) {
            return;
        }
        mLastRightClickTime = now;

        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (copyOnly && noteId <= 0) {
            return;
        }

        final float density = getResources().getDisplayMetrics().density;
        final int menuWidth = (int) (194 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_popup);
        int padding = (int) (6 * density);
        box.setPadding(padding, padding, padding, padding);

        final PopupWindow popup = new PopupWindow(
                box, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(8 * density);

        if (noteId > 0) {
            final long id = noteId;
            box.addView(createMenuItem(R.string.menu_copy_note, true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            popup.dismiss();
                            copyNote(id);
                        }
                    }));
        }

        if (!copyOnly) {
            if (noteId > 0) {
                box.addView(createMenuDivider());
            }
            box.addView(createMenuItem(R.string.menu_paste_note, hasClipboardContent(),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            popup.dismiss();
                            pasteNote();
                        }
                    }));
            box.addView(createMenuDivider());
            box.addView(createMenuItem(R.string.menu_add, true,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            popup.dismiss();
                            startActivity(new Intent(Intent.ACTION_INSERT,
                                    NotePad.Notes.CONTENT_URI));
                        }
                    }));
        }

        // 防止菜单超出屏幕
        int[] decorLocation = new int[2];
        getWindow().getDecorView().getLocationOnScreen(decorLocation);
        int windowX = screenX - decorLocation[0];
        int windowY = screenY - decorLocation[1];

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxX = screenWidth - menuWidth - (int) (8 * density);
        int maxY = screenHeight - (int) (190 * density);
        int x = Math.max(0, Math.min(windowX, maxX));
        int y = Math.max(0, Math.min(windowY, maxY));

        popup.showAtLocation(getWindow().getDecorView(), Gravity.NO_GRAVITY, x, y);
    }

    /** 创建一个浮动菜单里的文字项 */
    private TextView createMenuItem(int textRes, boolean enabled,
                                    View.OnClickListener listener) {
        float density = getResources().getDisplayMetrics().density;
        int horizontal = (int) (12 * density);
        int vertical = (int) (11 * density);

        TextView item = new TextView(this);
        item.setText(textRes);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setPadding(horizontal, vertical, horizontal, vertical);
        item.setTextColor(getColor(enabled ? R.color.text_primary : R.color.empty_icon));
        item.setEnabled(enabled);

        TypedValue outValue = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true)) {
            item.setBackgroundResource(outValue.resourceId);
        }
        if (enabled && listener != null) {
            item.setOnClickListener(listener);
        }
        return item;
    }

    /** 浮动菜单里的分隔线 */
    private View createMenuDivider() {
        float density = getResources().getDisplayMetrics().density;
        View divider = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        int margin = (int) (6 * density);
        params.setMargins(margin, 0, margin, 0);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(getColor(R.color.divider));
        return divider;
    }

    /**
     * 扩展后的列表适配器：在一个条目里同时显示标题、分类、完整时间戳，
     * 用便签颜色作为条目背景，并在条目上常驻「编辑」「删除」两个图标按钮。
     */
    private class NoteAdapter extends SimpleCursorAdapter {

        NoteAdapter(Context context, Cursor c, String[] from, int[] to) {
            super(context, R.layout.noteslist_item, c, from, to, 0);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            TextView title = view.findViewById(R.id.note_title);
            TextView time = view.findViewById(R.id.note_time);
            TextView category = view.findViewById(R.id.note_category);
            View card = view.findViewById(R.id.note_card);
            ImageButton editButton = view.findViewById(R.id.note_edit);
            ImageButton deleteButton = view.findViewById(R.id.note_delete);

            final long noteId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(NotePad.Notes._ID));
            String titleText = cursor.getString(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_TITLE));
            String categoryText = cursor.getString(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_CATEGORY));
            long modified = cursor.getLong(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE));
            int colorIndex = cursor.getInt(
                    cursor.getColumnIndexOrThrow(NotePad.Notes.COLUMN_NAME_COLOR));

            final String shownTitle = TextUtils.isEmpty(titleText)
                    ? getString(R.string.untitled_note) : titleText;

            title.setText(shownTitle);
            // 时间戳：完整显示年月日与时分
            time.setText(NoteUtils.formatTime(modified));
            category.setText(TextUtils.isEmpty(categoryText)
                    ? NotePad.Notes.DEFAULT_CATEGORY : categoryText);
            card.setBackgroundTintList(ColorStateList.valueOf(NoteUtils.noteColor(colorIndex)));

            // 点击卡片打开笔记
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openNote(noteId);
                }
            });

            // 长按卡片：只弹出「复制笔记」一项
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showItemMenu(v, noteId);
                    return true;
                }
            });

            // 常驻的编辑图标：直接进入编辑界面，不需要先长按
            editButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openNote(noteId);
                }
            });

            // 常驻的删除图标：弹确认框后删除
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(noteId, shownTitle);
                }
            });
        }
    }
}
