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

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * This Activity handles "editing" a note, where editing is responding to
 * {@link Intent#ACTION_VIEW} (request to view data), edit a note
 * {@link Intent#ACTION_EDIT}, create a note {@link Intent#ACTION_INSERT}, or
 * create a new note from the current contents of the clipboard {@link Intent#ACTION_PASTE}.
 *
 * 在原始版本基础上扩展了：
 * 1. 编辑器顶部显示笔记的分类与最后修改时间；
 * 2. 可以为当前笔记设置分类；
 * 3. 可以为当前笔记更换背景颜色（便签配色）。
 */
public class NoteEditor extends AppCompatActivity {
    // For logging and debugging purposes
    private static final String TAG = "NoteEditor";

    /*
     * Creates a projection that returns the note ID and the note contents.
     */
    private static final String[] PROJECTION =
        new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_CATEGORY,
            NotePad.Notes.COLUMN_NAME_COLOR,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
    };

    private static final int COLUMN_INDEX_TITLE = 1;
    private static final int COLUMN_INDEX_NOTE = 2;
    private static final int COLUMN_INDEX_CATEGORY = 3;
    private static final int COLUMN_INDEX_COLOR = 4;
    private static final int COLUMN_INDEX_MODIFIED = 5;

    // A label for the saved state of the activity
    private static final String ORIGINAL_CONTENT = "origContent";

    // This Activity can be started by more than one action. Each action is represented
    // as a "state" constant
    private static final int STATE_EDIT = 0;
    private static final int STATE_INSERT = 1;

    // Global mutable variables
    private int mState;
    private Uri mUri;
    private Cursor mCursor;
    private LinedEditText mText;
    private String mOriginalContent;

    /** 可手动输入 / 修改的标题输入框 */
    private EditText mTitleEdit;
    /** 进入界面时数据库里的标题，用于判断标题是否被改过 */
    private String mOriginalTitle = "";

    /**
     * 用户选择「不保存」时置为 true。
     * onPause 里的自动写库看到这个标记就直接跳过，保证笔记内容和时间戳都不被改动。
     */
    private boolean mDiscardChanges = false;

    // 扩展：分类、便签颜色与顶部信息栏
    private String mCategory;
    private int mColorIndex;
    private View mRoot;
    private Toolbar mToolbar;
    private TextView mCategoryView;
    private TextView mTimeView;

    /**
     * Defines a custom EditText View that draws lines between each line of text that is displayed.
     */
    public static class LinedEditText extends EditText {
        private Rect mRect;
        private Paint mPaint;

        // This constructor is used by LayoutInflater
        public LinedEditText(Context context, AttributeSet attrs) {
            super(context, attrs);

            // Creates a Rect and a Paint object, and sets the style and color of the Paint object.
            mRect = new Rect();
            mPaint = new Paint();
            mPaint.setStyle(Paint.Style.STROKE);
            // 横线改用极浅的灰色，保证在彩色便签背景上也不会喧宾夺主
            mPaint.setColor(0x26000000);
        }

        /**
         * 扩展：更换便签背景色时同步调整横线颜色。
         */
        public void setLineColor(int color) {
            mPaint.setColor(color);
            invalidate();
        }

        /**
         * This is called to draw the LinedEditText object
         * @param canvas The canvas on which the background is drawn.
         */
        @Override
        protected void onDraw(Canvas canvas) {

            // Gets the number of lines of text in the View.
            int count = getLineCount();

            // Gets the global Rect and Paint objects
            Rect r = mRect;
            Paint paint = mPaint;

            /*
             * Draws one line in the rectangle for every line of text in the EditText
             */
            for (int i = 0; i < count; i++) {

                // Gets the baseline coordinates for the current line of text
                int baseline = getLineBounds(i, r);

                /*
                 * Draws a line in the background from the left of the rectangle to the right,
                 * at a vertical position one dip below the baseline, using the "paint" object
                 * for details.
                 */
                canvas.drawLine(r.left, baseline + 1, r.right, baseline + 1, paint);
            }

            // Finishes up by calling the parent method
            super.onDraw(canvas);
        }
    }

    /**
     * This method is called by Android when the Activity is first started. From the incoming
     * Intent, it determines what kind of editing is desired, and then does it.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * Creates an Intent to use when the Activity object's result is sent back to the
         * caller.
         */
        final Intent intent = getIntent();

        /*
         *  Sets up for the edit, based on the action specified for the incoming Intent.
         */

        // Gets the action that triggered the intent filter for this Activity
        final String action = intent.getAction();

        // For an edit action:
        if (Intent.ACTION_EDIT.equals(action)) {

            // Sets the Activity state to EDIT, and gets the URI for the data to be edited.
            mState = STATE_EDIT;
            mUri = intent.getData();

            // For an insert or paste action:
        } else if (Intent.ACTION_INSERT.equals(action)
                || Intent.ACTION_PASTE.equals(action)) {

            // Sets the Activity state to INSERT, gets the general note URI, and inserts an
            // empty record in the provider
            mState = STATE_INSERT;
            mUri = getContentResolver().insert(intent.getData(), null);

            /*
             * If the attempt to insert the new note fails, shuts down this Activity. The
             * originating Activity receives back RESULT_CANCELED if it requested a result.
             * Logs that the insert failed.
             */
            if (mUri == null) {

                // Writes the log identifier, a message, and the URI that failed.
                Log.e(TAG, "Failed to insert new note into " + getIntent().getData());

                // Closes the activity.
                finish();
                return;
            }

            // Since the new entry was created, this sets the result to be returned
            // set the result to be returned.
            setResult(RESULT_OK, (new Intent()).setAction(mUri.toString()));

        // If the action was other than EDIT or INSERT:
        } else {

            // Logs an error that the action was not understood, finishes the Activity, and
            // returns RESULT_CANCELED to an originating Activity.
            Log.e(TAG, "Unknown action, exiting");
            finish();
            return;
        }

        // Sets the layout for this Activity. See res/layout/note_editor.xml
        setContentView(R.layout.note_editor);

        mRoot = findViewById(R.id.editor_root);
        mToolbar = findViewById(R.id.editor_toolbar);
        setSupportActionBar(mToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 左上角返回箭头：有未保存的修改时先询问是否保存
                confirmExit();
            }
        });

        // 系统返回键 / 返回手势走同一套逻辑
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        // Gets a handle to the EditText in the the layout.
        mText = findViewById(R.id.note);
        mTitleEdit = findViewById(R.id.note_title_edit);
        mCategoryView = findViewById(R.id.editor_category);
        mTimeView = findViewById(R.id.editor_time);

        /*
         * If this Activity had stopped previously, its state was written the ORIGINAL_CONTENT
         * location in the saved Instance state. This gets the state.
         */
        if (savedInstanceState != null) {
            mOriginalContent = savedInstanceState.getString(ORIGINAL_CONTENT);
        }

        // 读取数据库中的笔记内容并显示
        openCursor();
        bindCursor();

        // 默认把焦点放在正文上（与原始版本一致），想改标题直接点一下正文上方的标题框即可
        mText.requestFocus();

        // For a paste, initializes the data from clipboard.
        if (Intent.ACTION_PASTE.equals(action)) {
            /*
             * 粘贴出来的是一条已经存在的笔记，因此先把状态切到 EDIT，
             * 这样标题栏会显示笔记标题，标题输入框也可以继续修改。
             */
            mState = STATE_EDIT;
            performPaste();
            // 把快照写回数据库后重新读取，界面（含标题、分类、背景色）随之刷新
            openCursor();
            bindCursor();
            mText.setSelection(mText.getText().length());
        }
    }

    /**
     * 重新查询当前笔记。
     */
    private void openCursor() {
        if (mCursor != null) {
            mCursor.close();
        }
        mCursor = getContentResolver().query(mUri, PROJECTION, null, null, null);
    }

    /**
     * 把游标中的数据绑定到界面上：正文、标题、分类、颜色、修改时间。
     */
    private void bindCursor() {
        if (mCursor == null || !mCursor.moveToFirst()) {
            setTitle(getText(R.string.error_title));
            mText.setText(getText(R.string.error_message));
            mTitleEdit.setText("");
            return;
        }

        String title = mCursor.getString(COLUMN_INDEX_TITLE);
        String note = mCursor.getString(COLUMN_INDEX_NOTE);
        mCategory = mCursor.getString(COLUMN_INDEX_CATEGORY);
        mColorIndex = mCursor.getInt(COLUMN_INDEX_COLOR);
        long modified = mCursor.getLong(COLUMN_INDEX_MODIFIED);

        if (TextUtils.isEmpty(mCategory)) {
            mCategory = NotePad.Notes.DEFAULT_CATEGORY;
        }

        // 设置标题栏文字：编辑已有笔记时显示笔记标题
        if (mState == STATE_EDIT) {
            String shownTitle = TextUtils.isEmpty(title)
                    ? getString(R.string.untitled_note) : title;
            setTitle(getString(R.string.title_edit, shownTitle));
        } else {
            setTitle(getText(R.string.title_create));
        }

        // 显示正文，setTextKeepState 不会改变光标位置
        mText.setTextKeepState(note == null ? "" : note);
        if (mOriginalContent == null) {
            mOriginalContent = note == null ? "" : note;
        }

        // 把标题显示到正文上方的输入框里，用户可以随时手动修改
        String shownTitle = title == null ? "" : title;
        // 新建笔记时数据库里存的是系统默认占位标题，界面上应显示为空，让 hint 提示用户输入
        if (mState == STATE_INSERT && isPlaceholderTitle(shownTitle)) {
            shownTitle = "";
        }
        mTitleEdit.setTextKeepState(shownTitle);
        mOriginalTitle = shownTitle.trim();

        // 扩展：顶部显示分类与修改时间
        mCategoryView.setText(mCategory);
        mTimeView.setText(getString(R.string.editor_modified_at, NoteUtils.formatTime(modified)));

        // 扩展：应用便签背景色
        applyNoteColor();
    }

    /**
     * 判断标题是不是系统 / 本应用使用的占位标题。
     * 新建笔记时 NotePadProvider 会把标题填成系统的"untitled"字符串。
     */
    private boolean isPlaceholderTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return true;
        }
        String trimmed = title.trim();
        return trimmed.equals(getString(android.R.string.untitled))
                || trimmed.equals(getString(R.string.untitled_note));
    }

    /**
     * 扩展：把选中的便签颜色应用到编辑器背景、标题栏与状态栏。
     */
    private void applyNoteColor() {
        int color = NoteUtils.noteColor(mColorIndex);
        mRoot.setBackgroundColor(color);
        mToolbar.setBackgroundColor(color);
        mText.setBackgroundColor(color);
        // 白色便签用深一点的横线，其它颜色用浅灰横线
        mText.setLineColor(mColorIndex == 0 ? 0x33000000 : 0x26000000);
        getWindow().setStatusBarColor(color);
    }

    /**
     * This method is called when the Activity is about to come to the foreground.
     */
    @Override
    protected void onResume() {
        super.onResume();

        /*
         * 重新读取一次，保证从分类、颜色等对话框返回后，标题等信息是最新的。
         * 此处不会丢失用户的输入，因为离开界面时 onPause() 已经把内容写回数据库。
         */
        if (mCursor != null) {
            openCursor();
            bindCursor();
        }
    }

    /**
     * This method is called when an Activity loses focus during its normal operation, and is then
     * later on killed. The Activity has a chance to save its state so that the system can restore
     * it.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        // Save away the original text, so we still have it if the activity
        // needs to be killed while paused.
        outState.putString(ORIGINAL_CONTENT, mOriginalContent);
        super.onSaveInstanceState(outState);
    }

    /**
     * This method is called when the Activity loses focus.
     *
     * 离开界面时自动保存，写回的内容包括正文与修改时间（时间戳因此会同步更新到列表）。
     */
    @Override
    protected void onPause() {
        super.onPause();

        // 用户已选择「不保存」，内容已在 discardAndFinish() 里回滚，这里不能再写库
        if (mDiscardChanges) {
            return;
        }

        if (mCursor != null) {

            // Get the current note text.
            String text = mText.getText().toString();
            int length = text.length();
            String currentTitle = mTitleEdit.getText().toString().trim();

            /*
             * If the Activity is in the midst of finishing and there is no text in the current
             * note, returns a result of CANCELED to the caller, and deletes the note.
             *
             * 扩展：如果用户只写了标题、正文为空，这条笔记仍然保留（标题本身也是有意义的）。
             */
            if (isFinishing() && length == 0 && TextUtils.isEmpty(currentTitle)) {
                setResult(RESULT_CANCELED);
                deleteNote();
            } else if (mState == STATE_EDIT) {
                // 正文或标题任意一项被修改过才写回数据库，避免仅仅打开笔记就刷新时间戳
                String original = mOriginalContent == null ? "" : mOriginalContent;
                String originalTitle = mOriginalTitle == null ? "" : mOriginalTitle;
                if (!original.equals(text) || !originalTitle.equals(currentTitle)) {
                    updateNote(text, currentTitle);
                    mOriginalContent = text;
                    mOriginalTitle = currentTitle;
                }
            } else if (mState == STATE_INSERT) {
                updateNote(text, currentTitle);
                mState = STATE_EDIT;
                mOriginalContent = text;
                mOriginalTitle = currentTitle;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.editor_options_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 只有内容被修改过，"撤销修改"才有意义
        if (mCursor != null && mCursor.moveToFirst()) {
            String savedNote = mCursor.getString(COLUMN_INDEX_NOTE);
            String currentNote = mText.getText().toString();
            MenuItem revert = menu.findItem(R.id.menu_revert);
            if (revert != null) {
                revert.setVisible(!savedNote.equals(currentNote));
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * This method is called when a menu item is selected.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            // 工具栏上的对勾：明确表示保存
            saveAndFinish();
            return true;
        } else if (id == R.id.menu_delete) {
            deleteNote();
            finish();
            return true;
        } else if (id == R.id.menu_revert) {
            cancelNote();
            return true;
        } else if (id == R.id.menu_category) {
            showCategoryDialog();
            return true;
        } else if (id == R.id.menu_color) {
            showColorDialog();
            return true;
        } else if (id == android.R.id.home) {
            confirmExit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 扩展：选择笔记分类。
     */
    private void showCategoryDialog() {
        final String[] categories = NotePad.Notes.CATEGORIES;
        int checked = 0;
        for (int i = 0; i < categories.length; i++) {
            if (categories[i].equals(mCategory)) {
                checked = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_category)
                .setSingleChoiceItems(categories, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                mCategory = categories[which];
                                mCategoryView.setText(mCategory);
                                saveCategory();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * 扩展：选择便签背景色，用一组圆形色块展示可选颜色。
     */
    private void showColorDialog() {
        final float density = getResources().getDisplayMetrics().density;
        final int size = (int) (54 * density);
        final int margin = (int) (10 * density);
        final int padding = (int) (16 * density);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(padding, padding, padding, padding);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_color_title)
                .setNegativeButton(R.string.dialog_cancel, null);
        final AlertDialog dialog = builder.create();

        for (int i = 0; i < NoteUtils.NOTE_COLORS.length; i++) {
            final int index = i;

            View swatch = new View(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(lp);

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(NoteUtils.noteColor(index));
            // 当前颜色用主题色描边，方便识别
            shape.setStroke(index == mColorIndex ? (int) (3 * density) : (int) density,
                    index == mColorIndex ? 0xFF2F6FED : 0x33000000);
            swatch.setBackground(shape);
            swatch.setContentDescription(NoteUtils.NOTE_COLOR_NAMES[index]);
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mColorIndex = index;
                    applyNoteColor();
                    saveColor();
                    dialog.dismiss();
                }
            });
            grid.addView(swatch);
        }

        dialog.setView(grid);
        dialog.show();
    }

    /**
     * 扩展：只更新分类字段。
     */
    private void saveCategory() {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_CATEGORY, mCategory);
        getContentResolver().update(mUri, values, null, null);
    }

    /**
     * 扩展：只更新便签颜色字段。
     */
    private void saveColor() {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_COLOR, mColorIndex);
        getContentResolver().update(mUri, values, null, null);
    }

    /**
     * A helper method that replaces the note's data with the contents of the clipboard.
     *
     * 扩展：如果剪贴板里是本应用「复制」出来的整篇笔记，会把标题、正文、
     * 分类和便签背景色一起还原；否则退化为只粘贴剪贴板里的纯文本。
     */
    private void performPaste() {

        ContentValues snapshot = NoteClipboard.readSnapshot(this);

        if (snapshot == null) {
            // 剪贴板里不是笔记快照，按普通文本处理
            String text = NoteClipboard.readPlainText(this);
            if (TextUtils.isEmpty(text)) {
                return;
            }
            snapshot = new ContentValues();
            snapshot.put(NotePad.Notes.COLUMN_NAME_TITLE, NoteUtils.deriveTitle(text));
            snapshot.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
            snapshot.put(NotePad.Notes.COLUMN_NAME_CATEGORY, NotePad.Notes.DEFAULT_CATEGORY);
            snapshot.put(NotePad.Notes.COLUMN_NAME_COLOR, 0);
        }

        ContentValues values = new ContentValues(snapshot);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        // 当前 mUri 是 ACTION_PASTE 时刚插入的空笔记，把整份快照写进去
        getContentResolver().update(mUri, values, null, null);

        String pastedTitle = snapshot.getAsString(NotePad.Notes.COLUMN_NAME_TITLE);
        mOriginalContent = snapshot.getAsString(NotePad.Notes.COLUMN_NAME_NOTE);
        mOriginalTitle = pastedTitle == null ? "" : pastedTitle.trim();
    }

    /* ------------------------------------------------------------------
     * 退出编辑页：询问是否保存
     * ------------------------------------------------------------------ */

    /**
     * 处理「离开编辑页」这件事。
     *
     * 内容没有任何改动时直接退出；有改动时弹窗让用户选择「保存」或「不保存」。
     * 选「不保存」会把正文和标题还原成进入界面时的样子，且不改动修改时间。
     */
    private void confirmExit() {
        if (mCursor == null) {
            finish();
            return;
        }

        String text = mText.getText().toString();
        String title = mTitleEdit.getText().toString().trim();

        // 没有任何改动，直接退出，不打扰用户
        if (!isDirty(text, title)) {
            finish();
            return;
        }

        // 先收起键盘，避免对话框被输入法挡住
        hideKeyboard();

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_unsaved_title)
                .setMessage(R.string.dialog_unsaved_message)
                .setPositiveButton(R.string.dialog_unsaved_save,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                saveAndFinish();
                            }
                        })
                .setNegativeButton(R.string.dialog_unsaved_discard,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                discardAndFinish();
                            }
                        })
                .setNeutralButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * 判断正文或标题相对进入界面时有没有变化。
     */
    private boolean isDirty(String text, String title) {
        if (mState == STATE_INSERT) {
            // 新建的笔记：只要写了标题或正文就算有内容
            return !TextUtils.isEmpty(text) || !TextUtils.isEmpty(title);
        }
        String originalText = mOriginalContent == null ? "" : mOriginalContent;
        String originalTitle = mOriginalTitle == null ? "" : mOriginalTitle;
        return !originalText.equals(text) || !originalTitle.equals(title);
    }

    /**
     * 保存并退出。写入正文与标题，同时把最后修改时间刷新为当前时刻。
     */
    private void saveAndFinish() {
        String text = mText.getText().toString();
        String title = mTitleEdit.getText().toString().trim();

        updateNote(text, title);

        // 状态切到 EDIT，避免 onPause 里再把同样的内容写一遍
        mState = STATE_EDIT;
        mOriginalContent = text;
        mOriginalTitle = title;
        mDiscardChanges = false;

        Toast.makeText(this, R.string.toast_note_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * 不保存并退出。把正文和标题还原成进入界面时的内容，修改时间保持原值。
     */
    private void discardAndFinish() {
        // 打上标记：onPause 里不要再自动写库
        mDiscardChanges = true;

        if (mState == STATE_INSERT) {
            // 新建的笔记还没真正保存过，直接删掉这条空记录
            deleteNote();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        restoreOriginal();
        Toast.makeText(this, R.string.toast_discarded, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * 把数据库里的正文和标题恢复成进入界面时的内容。
     * 只更新这两列，modified（最后修改时间）因此保持原值。
     */
    private void restoreOriginal() {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_NOTE,
                mOriginalContent == null ? "" : mOriginalContent);
        values.put(NotePad.Notes.COLUMN_NAME_TITLE,
                mOriginalTitle == null ? "" : mOriginalTitle);
        getContentResolver().update(mUri, values, null, null);
    }

    /**
     * 收起软键盘。
     */
    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    /**
     * Replaces the current note contents with the text and title provided as arguments.
     *
     * 扩展：标题不再只能由正文自动截取，而是由标题输入框（或传入的 title）决定。
     *
     * @param text  新的正文
     * @param title 新的标题；传 null 时取界面上的标题输入框
     */
    private void updateNote(String text, String title) {

        // Sets up a map to contain values to be updated in the provider.
        ContentValues values = new ContentValues();
        // 每次保存都刷新修改时间，列表中的时间戳随之更新
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());

        String finalTitle = title;
        if (finalTitle == null) {
            finalTitle = mTitleEdit == null ? "" : mTitleEdit.getText().toString().trim();
        }

        if (mState == STATE_INSERT && TextUtils.isEmpty(finalTitle)) {
            // 新建笔记且用户没有手动填写标题时，退化为用正文首行生成标题
            finalTitle = NoteUtils.deriveTitle(text);
            if (TextUtils.isEmpty(finalTitle)) {
                finalTitle = getString(R.string.untitled_note);
            }
        }

        values.put(NotePad.Notes.COLUMN_NAME_TITLE, finalTitle);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);

        getContentResolver().update(mUri, values, null, null);
    }

    /**
     * This helper method cancels the work done on a note.  It deletes the note if it was
     * newly created, or reverts to the original text of the note
     */
    private void cancelNote() {
        if (mCursor != null) {
            if (mState == STATE_EDIT) {
                // 打上标记，onPause 不再把界面上的内容写回去
                mDiscardChanges = true;
                restoreOriginal();
            } else if (mState == STATE_INSERT) {
                // We inserted an empty note, make sure to delete it
                deleteNote();
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Take care of deleting a note.  Simply deletes the entry.
     */
    private void deleteNote() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
            getContentResolver().delete(mUri, null, null);
            mText.setText("");
        }
    }
}
