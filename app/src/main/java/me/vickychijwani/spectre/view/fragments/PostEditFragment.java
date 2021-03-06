package me.vickychijwani.spectre.view.fragments;

import android.app.Activity;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.github.ksoichiro.android.observablescrollview.ObservableScrollView;
import com.github.ksoichiro.android.observablescrollview.ObservableScrollViewCallbacks;
import com.github.ksoichiro.android.observablescrollview.ScrollState;
import com.github.slugify.Slugify;
import com.melnykov.fab.FloatingActionButton;
import com.squareup.otto.Subscribe;

import java.io.IOException;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;
import io.realm.RealmList;
import me.vickychijwani.spectre.R;
import me.vickychijwani.spectre.event.PostSavedEvent;
import me.vickychijwani.spectre.event.SavePostEvent;
import me.vickychijwani.spectre.model.Post;
import me.vickychijwani.spectre.model.Tag;
import me.vickychijwani.spectre.util.AppUtils;
import me.vickychijwani.spectre.util.PostUtils;
import me.vickychijwani.spectre.view.EditTextActionModeManager;
import me.vickychijwani.spectre.view.PostViewActivity;
import me.vickychijwani.spectre.view.TagsEditText;

public class PostEditFragment extends BaseFragment implements ObservableScrollViewCallbacks,
        EditTextActionModeManager.Callbacks {

    private static final String TAG = "PostEditFragment";

    @InjectView(R.id.post_header)
    View mPostHeader;

    @InjectView(R.id.post_title_edit)
    EditText mPostTitleEditView;

    @InjectView(R.id.post_tags_edit)
    TagsEditText mPostTagsEditView;

    @InjectView(R.id.post_markdown)
    EditText mPostEditView;

    @InjectView(R.id.preview_btn)
    FloatingActionButton mPreviewBtn;

    @InjectView(R.id.post_markdown_scroll_view)
    ObservableScrollView mScrollView;

    private OnPreviewClickListener mPreviewClickListener;
    private Post mOriginalPost;
    private Post mPost;
    private boolean mbDiscardChanges = false;

    // action mode
    private View.OnClickListener mActionModeCloseClickListener;
    private EditTextActionModeManager mEditTextActionModeManager;
    private PostViewActivity mActivity;

    // scroll behaviour
    private int mActionBarSize;
    private boolean mPostHeaderCollapsed = false;


    public interface OnPreviewClickListener {
        void onPreviewClicked();
    }


    @SuppressWarnings("unused")
    public static PostEditFragment newInstance() {
        return new PostEditFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View view = inflater.inflate(R.layout.fragment_post_edit, container, false);
        ButterKnife.inject(this, view);

        mActivity = ((PostViewActivity) getActivity());
        setPost(mActivity.getPost(), true);

        // action mode manager
        mEditTextActionModeManager = new EditTextActionModeManager(mActivity, this);
        mActionModeCloseClickListener = v -> mEditTextActionModeManager.stopActionMode(true);
        mEditTextActionModeManager.register(mPostTitleEditView);
        mEditTextActionModeManager.register(mPostTagsEditView);

        // title
        mActivity.setTitle(null);
        // hack for word wrap with "Done" IME action! see http://stackoverflow.com/a/13563946/504611
        mPostTitleEditView.setHorizontallyScrolling(false);
        mPostTitleEditView.setMaxLines(Integer.MAX_VALUE);

        // tags
        mPostTagsEditView.setAdapter(new ArrayAdapter<>(
                getActivity(), android.R.layout.simple_list_item_1, new Tag[]{}
        ));

        // preview button
        mPreviewBtn.setOnClickListener(v -> mPreviewClickListener.onPreviewClicked());

        mActionBarSize = getActionBarSize();
        mScrollView.setScrollViewCallbacks(this);

        setHasOptionsMenu(true);

        return view;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        show();
    }

    @Override
    public void onResume() {
        super.onResume();
        setPost(mPost, false);
    }

    @Override
    public void onPause() {
        super.onPause();
        // persist changes to drafts, unless the user opted to discard those changes
        onSaveClicked(Post.DRAFT.equals(mPost.getStatus()));
    }

    @Override
    public void onShow() {
        if (mActivity != null && ! mPostHeaderCollapsed) {
            mActivity.setTitle(null);
        }
    }

    @Override
    public void onHide() {
        mPost.setMarkdown(mPostEditView.getText().toString());
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mPreviewClickListener = (OnPreviewClickListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                + "must implement OnPreviewClickListener");
        }
    }


    // action mode
    @Override
    public void onActionModeStarted(EditText editText) {
        if (editText == mPostTitleEditView) {
            mActivity.setTitle(getString(R.string.edit_title));
        } else if (editText == mPostTagsEditView) {
            mActivity.setTitle(getString(R.string.edit_tags));
        }
        mActivity.supportInvalidateOptionsMenu();
        mActivity.setNavigationItem(R.drawable.close, mActionModeCloseClickListener);
        mPreviewBtn.hide(false);
    }

    @Override
    public void onActionModeStopped(boolean discardChanges) {
        if (discardChanges) {
            setPost(mPost, false);
        } else {
            onSaveClicked(false);
        }
        AppUtils.hideKeyboard(mActivity);
        mActivity.setTitle(null);
        mActivity.supportInvalidateOptionsMenu();
        mActivity.resetNavigationItem();
        mPreviewBtn.show(false);
    }

    @Override
    public boolean onBackPressed() {
        //noinspection SimplifiableIfStatement
        if (mEditTextActionModeManager.stopActionMode(true)) {
            return true;
        }
        return super.onBackPressed();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.post_edit, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        boolean actionModeActive = mEditTextActionModeManager.isActionModeActive();
        menu.findItem(R.id.action_done).setVisible(actionModeActive);
        menu.findItem(R.id.action_save).setVisible(!actionModeActive);
        menu.findItem(R.id.action_publish).setVisible(!actionModeActive);
        if (Post.PUBLISHED.equals(mPost.getStatus())) {
            menu.findItem(R.id.action_publish).setTitle(R.string.unpublish);
        } else {
            menu.findItem(R.id.action_publish).setTitle(R.string.publish);
        }
        onSaveClicked(false);   // make sure user changes are stored in mPost before computing diff
        boolean isPostDirty = PostUtils.isDirty(mOriginalPost, mPost);
        menu.findItem(R.id.action_discard).setVisible(isPostDirty && !actionModeActive);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_done:
                mEditTextActionModeManager.stopActionMode(false);
                return true;
            case R.id.action_save:
                onSaveClicked(true);
                return true;
            case R.id.action_publish:
                onPublishUnpublishClicked();
                return true;
            case R.id.action_discard:
                onDiscardChangesClicked();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public boolean onSaveClicked(boolean persistChanges) {
        return onSaveClicked(persistChanges, null);
    }

    private boolean onSaveClicked(boolean persistChanges, @Nullable @Post.Status String newStatus) {
        mPost.setTitle(mPostTitleEditView.getText().toString());
        mPost.setMarkdown(mPostEditView.getText().toString());
        mPost.setHtml(null);   // omit stale HTML from request body
        RealmList<Tag> tags = new RealmList<>();
        List<Object> tagObjects = mPostTagsEditView.getObjects();
        for (Object obj : tagObjects) {
            tags.add((Tag) obj);
        }
        mPost.setTags(tags);
        if (newStatus != null) {
            mPost.setStatus(newStatus);
        }

        // this handles cases like edit => onPause saves changes => discard, which should discard
        // ALL changes made since the editor was opened, hence save mOriginalPost (can't use
        // onSaveClicked(isDraft && !mbDiscardChanges) in onPause for this reason)
        if (mbDiscardChanges) {
            // avoid network call if no changes have been made
            if (! PostUtils.isDirty(mOriginalPost, mPost)) return false;
            getBus().post(new SavePostEvent(mOriginalPost));
            mbDiscardChanges = false;
            return true;
        } else if (persistChanges) {
            // avoid network call if no changes have been made
            if (! PostUtils.isDirty(mOriginalPost, mPost)) return false;
            getBus().post(new SavePostEvent(mPost));
            return true;
        }
        return false;
    }

    private void onPublishUnpublishClicked() {
        int msg = R.string.alert_publish;
        String targetStatus = Post.PUBLISHED;
        if (Post.PUBLISHED.equals(mPost.getStatus())) {
            msg = R.string.alert_unpublish;
            targetStatus = Post.DRAFT;
        }
        @Post.Status final String finalTargetStatus = targetStatus;
        new AlertDialog.Builder(mActivity)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (finalTargetStatus.equals(mPost.getStatus())) {
                        Crashlytics.logException(new IllegalStateException("UI is messed up, " +
                                "desired post status is same as current status!"));
                    }
                    if (TextUtils.isEmpty(mPost.getSlug())
                            || mPost.getSlug().startsWith(Post.DEFAULT_SLUG_PREFIX)) {
                        try {
                            mPost.setSlug(new Slugify().slugify(mPost.getTitle()));
                        } catch (IOException e) {
                            Crashlytics.logException(e);
                        }
                    }
                    onSaveClicked(true, finalTargetStatus);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .create().show();
    }

    private void onDiscardChangesClicked() {
        new AlertDialog.Builder(mActivity)
                .setMessage(getString(R.string.alert_discard_changes))
                .setPositiveButton(R.string.discard, (dialog, which) -> {
                    mbDiscardChanges = true;
                    getActivity().finish();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                .create().show();
    }

    @Subscribe
    public void onPostSavedEvent(PostSavedEvent event) {
        Toast.makeText(mActivity, "Post saved!", Toast.LENGTH_SHORT).show();
    }

    public void setPost(@NonNull Post post, boolean isOriginal) {
        mPost = post;
        if (isOriginal) {
            mOriginalPost = new Post(post);   // store a copy for calculating diff later
        }
        mPostTitleEditView.setText(post.getTitle());
        mPostEditView.setText(post.getMarkdown());
        mPostTagsEditView.clear();
        for (Tag tag : post.getTags()) {
            mPostTagsEditView.addObject(tag, tag.getName());
        }
    }


    // scroll behaviour
    @Override
    public void onScrollChanged(int scrollY, boolean firstScroll, boolean dragging) {
        if (scrollY >= mActionBarSize) {
            collapsePostHeader();
        } else {
            expandPostHeader();
        }
    }

    @Override
    public void onDownMotionEvent() {

    }

    @Override
    public void onUpOrCancelMotionEvent(ScrollState scrollState) {

    }

    private void collapsePostHeader() {
        if (mPostHeaderCollapsed) {
            return;
        }
        mPostHeader.animate().alpha(0.0f).setDuration(200);
        getActivity().setTitle(mPost.getTitle());
        mPostHeaderCollapsed = true;
    }

    private void expandPostHeader() {
        if (! mPostHeaderCollapsed) {
            return;
        }
        mPostHeader.animate().alpha(1.0f).setDuration(200);
        getActivity().setTitle(null);
        mPostHeaderCollapsed = false;
    }

    private int getActionBarSize() {
        TypedValue typedValue = new TypedValue();
        int[] textSizeAttr = new int[] { R.attr.actionBarSize };
        int indexOfAttrTextSize = 0;
        TypedArray a = getActivity().obtainStyledAttributes(typedValue.data, textSizeAttr);
        int actionBarSize = a.getDimensionPixelSize(indexOfAttrTextSize, -1);
        a.recycle();
        return actionBarSize;
    }

}
