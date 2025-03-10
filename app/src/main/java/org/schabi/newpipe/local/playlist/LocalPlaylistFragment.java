package org.schabi.newpipe.local.playlist;

import static org.schabi.newpipe.error.ErrorUtil.showUiErrorSnackbar;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.databinding.DialogEditTextBinding;
import org.schabi.newpipe.databinding.LocalPlaylistHeaderBinding;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.PlayerType;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import icepick.State;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class LocalPlaylistFragment extends BaseLocalListFragment<List<PlaylistStreamEntry>, Void> {
    // Save the list 10 seconds after the last change occurred
    private static final long SAVE_DEBOUNCE_MILLIS = 10000;
    private static final int MINIMUM_INITIAL_DRAG_VELOCITY = 12;
    @State
    protected Long playlistId;
    @State
    protected String name;
    @State
    Parcelable itemsListState;

    private LocalPlaylistHeaderBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;

    private ItemTouchHelper itemTouchHelper;

    private LocalPlaylistManager playlistManager;
    private Subscription databaseSubscription;

    private PublishSubject<Long> debouncedSaveSignal;
    private CompositeDisposable disposables;

    /* Has the playlist been fully loaded from db */
    private AtomicBoolean isLoadingComplete;
    /* Has the playlist been modified (e.g. items reordered or deleted) */
    private AtomicBoolean isModified;
    /* Is the playlist currently being processed to remove watched videos */
    private boolean isRemovingWatched = false;

    public static LocalPlaylistFragment getInstance(final long playlistId, final String name) {
        final LocalPlaylistFragment instance = new LocalPlaylistFragment();
        instance.setInitialData(playlistId, name);
        return instance;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistManager = new LocalPlaylistManager(NewPipeDatabase.getInstance(requireContext()));
        debouncedSaveSignal = PublishSubject.create();

        disposables = new CompositeDisposable();

        isLoadingComplete = new AtomicBoolean();
        isModified = new AtomicBoolean();
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playlist, container, false);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Views
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);

        if (headerBinding != null) {
            headerBinding.playlistTitleView.setText(title);
        }
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        setTitle(name);
    }

    @Override
    protected ViewBinding getListHeader() {
        headerBinding = LocalPlaylistHeaderBinding.inflate(activity.getLayoutInflater(), itemsList,
                false);
        playlistControlBinding = headerBinding.playlistControl;

        headerBinding.playlistTitleView.setSelected(true);

        return headerBinding;
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        headerBinding.playlistTitleView.setOnClickListener(view -> createRenameDialog());

        itemTouchHelper = new ItemTouchHelper(getItemTouchCallback());
        itemTouchHelper.attachToRecyclerView(itemsList);

        itemListAdapter.setSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistStreamEntry) {
                    final StreamEntity item =
                            ((PlaylistStreamEntry) selectedItem).getStreamEntity();
                    NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                            item.getServiceId(), item.getUrl(), item.getTitle(), null, false);
                }
            }

            @Override
            public void held(final LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistStreamEntry) {
                    showInfoItemDialog((PlaylistStreamEntry) selectedItem);
                }
            }

            @Override
            public void drag(final LocalItem selectedItem,
                             final RecyclerView.ViewHolder viewHolder) {
                if (itemTouchHelper != null) {
                    itemTouchHelper.startDrag(viewHolder);
                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Loading
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void showLoading() {
        super.showLoading();
        if (headerBinding != null) {
            animate(headerBinding.getRoot(), false, 200);
            animate(playlistControlBinding.getRoot(), false, 200);
        }
    }

    @Override
    public void hideLoading() {
        super.hideLoading();
        if (headerBinding != null) {
            animate(headerBinding.getRoot(), true, 200);
            animate(playlistControlBinding.getRoot(), true, 200);
        }
    }

    @Override
    public void startLoading(final boolean forceLoad) {
        super.startLoading(forceLoad);

        if (disposables != null) {
            disposables.clear();
        }
        disposables.add(getDebouncedSaver());

        isLoadingComplete.set(false);
        isModified.set(false);

        playlistManager.getPlaylistStreams(playlistId)
                .onBackpressureLatest()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getPlaylistObserver());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Lifecycle - Destruction
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onPause() {
        super.onPause();
        itemsListState = itemsList.getLayoutManager().onSaveInstanceState();

        // Save on exit
        saveImmediate();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_local_playlist, menu);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (itemListAdapter != null) {
            itemListAdapter.unsetSelectedListener();
        }
        if (playlistControlBinding != null) {
            playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener(null);
            playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener(null);
            playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener(null);

            headerBinding = null;
            playlistControlBinding = null;
        }

        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
        if (disposables != null) {
            disposables.clear();
        }

        databaseSubscription = null;
        itemTouchHelper = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (debouncedSaveSignal != null) {
            debouncedSaveSignal.onComplete();
        }
        if (disposables != null) {
            disposables.dispose();
        }

        debouncedSaveSignal = null;
        playlistManager = null;
        disposables = null;

        isLoadingComplete = null;
        isModified = null;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Playlist Stream Loader
    ///////////////////////////////////////////////////////////////////////////

    private Subscriber<List<PlaylistStreamEntry>> getPlaylistObserver() {
        return new Subscriber<List<PlaylistStreamEntry>>() {
            @Override
            public void onSubscribe(final Subscription s) {
                showLoading();
                isLoadingComplete.set(false);

                if (databaseSubscription != null) {
                    databaseSubscription.cancel();
                }
                databaseSubscription = s;
                databaseSubscription.request(1);
            }

            @Override
            public void onNext(final List<PlaylistStreamEntry> streams) {
                // Skip handling the result after it has been modified
                if (isModified == null || !isModified.get()) {
                    handleResult(streams);
                    isLoadingComplete.set(true);
                }

                if (databaseSubscription != null) {
                    databaseSubscription.request(1);
                }
            }

            @Override
            public void onError(final Throwable exception) {
                showError(new ErrorInfo(exception, UserAction.REQUESTED_BOOKMARK,
                        "Loading local playlist"));
            }

            @Override
            public void onComplete() {
            }
        };
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.menu_item_share_playlist) {
            sharePlaylist();
        } else if (item.getItemId() == R.id.menu_item_rename_playlist) {
            createRenameDialog();
        } else if (item.getItemId() == R.id.menu_item_remove_watched) {
            if (!isRemovingWatched) {
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.remove_watched_popup_warning)
                        .setTitle(R.string.remove_watched_popup_title)
                        .setPositiveButton(R.string.ok,
                                (DialogInterface d, int id) -> removeWatchedStreams(false))
                        .setNeutralButton(
                                R.string.remove_watched_popup_yes_and_partially_watched_videos,
                                (DialogInterface d, int id) -> removeWatchedStreams(true))
                        .setNegativeButton(R.string.cancel,
                                (DialogInterface d, int id) -> d.cancel())
                        .create()
                        .show();
            }
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /**
     * Share the playlist as a newline-separated list of stream URLs.
     */
    public void sharePlaylist() {
        disposables.add(playlistManager.getPlaylistStreams(playlistId)
                .flatMapSingle(playlist -> Single.just(playlist.stream()
                        .map(PlaylistStreamEntry::getStreamEntity)
                        .map(StreamEntity::getUrl)
                        .collect(Collectors.joining("\n"))))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(urlsText -> ShareUtils.shareText(requireContext(), name, urlsText),
                        throwable -> showUiErrorSnackbar(this, "Sharing playlist", throwable)));
    }

    public void removeWatchedStreams(final boolean removePartiallyWatched) {
        if (isRemovingWatched) {
            return;
        }
        isRemovingWatched = true;
        showLoading();

        disposables.add(playlistManager.getPlaylistStreams(playlistId)
                .subscribeOn(Schedulers.io())
                .map((List<PlaylistStreamEntry> playlist) -> {
                    // Playlist data
                    final Iterator<PlaylistStreamEntry> playlistIter = playlist.iterator();

                    // History data
                    final HistoryRecordManager recordManager =
                            new HistoryRecordManager(getContext());
                    final Iterator<StreamHistoryEntry> historyIter = recordManager
                            .getStreamHistorySortedById().blockingFirst().iterator();

                    // Remove Watched, Functionality data
                    final List<PlaylistStreamEntry> notWatchedItems = new ArrayList<>();
                    boolean thumbnailVideoRemoved = false;

                    // already sorted by ^ getStreamHistorySortedById(), binary search can be used
                    final ArrayList<Long> historyStreamIds = new ArrayList<>();
                    while (historyIter.hasNext()) {
                        historyStreamIds.add(historyIter.next().getStreamId());
                    }

                    if (removePartiallyWatched) {
                        while (playlistIter.hasNext()) {
                            final PlaylistStreamEntry playlistItem = playlistIter.next();
                            final int indexInHistory = Collections.binarySearch(historyStreamIds,
                                    playlistItem.getStreamId());

                            if (indexInHistory < 0) {
                                notWatchedItems.add(playlistItem);
                            } else if (!thumbnailVideoRemoved
                                    && playlistManager.getPlaylistThumbnail(playlistId)
                                    .equals(playlistItem.getStreamEntity().getThumbnailUrl())) {
                                thumbnailVideoRemoved = true;
                            }
                        }
                    } else {
                        final Iterator<StreamStateEntity> streamStatesIter = recordManager
                                .loadLocalStreamStateBatch(playlist).blockingGet().iterator();

                        while (playlistIter.hasNext()) {
                            final PlaylistStreamEntry playlistItem = playlistIter.next();
                            final int indexInHistory = Collections.binarySearch(historyStreamIds,
                                    playlistItem.getStreamId());
                            final StreamStateEntity streamStateEntity = streamStatesIter.next();
                            final long duration = playlistItem.toStreamInfoItem().getDuration();

                            if (indexInHistory < 0 || (streamStateEntity != null
                                    && !streamStateEntity.isFinished(duration))) {
                                notWatchedItems.add(playlistItem);
                            } else if (!thumbnailVideoRemoved
                                    && playlistManager.getPlaylistThumbnail(playlistId)
                                    .equals(playlistItem.getStreamEntity().getThumbnailUrl())) {
                                thumbnailVideoRemoved = true;
                            }
                        }
                    }

                    return Flowable.just(notWatchedItems, thumbnailVideoRemoved);
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(flow -> {
                    final List<PlaylistStreamEntry> notWatchedItems =
                            (List<PlaylistStreamEntry>) flow.blockingFirst();
                    final boolean thumbnailVideoRemoved = (Boolean) flow.blockingLast();

                    itemListAdapter.clearStreamItemList();
                    itemListAdapter.addItems(notWatchedItems);
                    saveChanges();


                    if (thumbnailVideoRemoved) {
                        updateThumbnailUrl();
                    }

                    final long videoCount = itemListAdapter.getItemsList().size();
                    setVideoCount(videoCount);
                    if (videoCount == 0) {
                        showEmptyState();
                    }

                    hideLoading();
                    isRemovingWatched = false;
                }, throwable -> showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                        "Removing watched videos, partially watched=" + removePartiallyWatched))));
    }

    @Override
    public void handleResult(@NonNull final List<PlaylistStreamEntry> result) {
        super.handleResult(result);
        if (itemListAdapter == null) {
            return;
        }

        itemListAdapter.clearStreamItemList();

        if (result.isEmpty()) {
            showEmptyState();
            return;
        }

        itemListAdapter.addItems(result);
        if (itemsListState != null) {
            itemsList.getLayoutManager().onRestoreInstanceState(itemsListState);
            itemsListState = null;
        }
        setVideoCount(itemListAdapter.getItemsList().size());

        playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener(view -> {
            NavigationHelper.playOnMainPlayer(activity, getPlayQueue());
            showHoldToAppendTipIfNeeded();
        });
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener(view -> {
            NavigationHelper.playOnPopupPlayer(activity, getPlayQueue(), false);
            showHoldToAppendTipIfNeeded();
        });
        playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener(view -> {
            NavigationHelper.playOnBackgroundPlayer(activity, getPlayQueue(), false);
            showHoldToAppendTipIfNeeded();
        });
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnLongClickListener(view -> {
            NavigationHelper.enqueueOnPlayer(activity, getPlayQueue(), PlayerType.POPUP);
            return true;
        });

        playlistControlBinding.playlistCtrlPlayBgButton.setOnLongClickListener(view -> {
            NavigationHelper.enqueueOnPlayer(activity, getPlayQueue(), PlayerType.AUDIO);
            return true;
        });

        hideLoading();
    }

    private void showHoldToAppendTipIfNeeded() {
        if (PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(getString(R.string.show_hold_to_append_key), true)) {
            Toast.makeText(activity, R.string.hold_to_append, Toast.LENGTH_SHORT).show();
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void resetFragment() {
        super.resetFragment();
        if (databaseSubscription != null) {
            databaseSubscription.cancel();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playlist Metadata/Streams Manipulation
    //////////////////////////////////////////////////////////////////////////*/

    private void createRenameDialog() {
        if (playlistId == null || name == null || getContext() == null) {
            return;
        }

        final DialogEditTextBinding dialogBinding =
                DialogEditTextBinding.inflate(getLayoutInflater());
        dialogBinding.dialogEditText.setHint(R.string.name);
        dialogBinding.dialogEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        dialogBinding.dialogEditText.setSelection(dialogBinding.dialogEditText.getText().length());
        dialogBinding.dialogEditText.setText(name);

        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getContext())
                .setTitle(R.string.rename_playlist)
                .setView(dialogBinding.getRoot())
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rename, (dialogInterface, i) ->
                        changePlaylistName(dialogBinding.dialogEditText.getText().toString()));

        dialogBuilder.show();
    }

    private void changePlaylistName(final String title) {
        if (playlistManager == null) {
            return;
        }

        this.name = title;
        setTitle(title);

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + playlistId + "] "
                    + "with new title=[" + title + "] items");
        }

        final Disposable disposable = playlistManager.renamePlaylist(playlistId, title)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(longs -> { /*Do nothing on success*/ }, throwable ->
                        showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                "Renaming playlist")));
        disposables.add(disposable);
    }

    private void changeThumbnailUrl(final String thumbnailUrl) {
        if (playlistManager == null) {
            return;
        }

        final Toast successToast = Toast.makeText(getActivity(),
                R.string.playlist_thumbnail_change_success,
                Toast.LENGTH_SHORT);

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + playlistId + "] "
                    + "with new thumbnail url=[" + thumbnailUrl + "]");
        }

        final Disposable disposable = playlistManager
                .changePlaylistThumbnail(playlistId, thumbnailUrl)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignore -> successToast.show(), throwable ->
                        showError(new ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK,
                                "Changing playlist thumbnail")));
        disposables.add(disposable);
    }

    private void updateThumbnailUrl() {
        final String newThumbnailUrl;

        if (!itemListAdapter.getItemsList().isEmpty()) {
            newThumbnailUrl = ((PlaylistStreamEntry) itemListAdapter.getItemsList().get(0))
                    .getStreamEntity().getThumbnailUrl();
        } else {
            newThumbnailUrl = "drawable://" + R.drawable.placeholder_thumbnail_playlist;
        }

        changeThumbnailUrl(newThumbnailUrl);
    }

    private void deleteItem(final PlaylistStreamEntry item) {
        if (itemListAdapter == null) {
            return;
        }

        itemListAdapter.removeItem(item);
        if (playlistManager.getPlaylistThumbnail(playlistId)
                .equals(item.getStreamEntity().getThumbnailUrl())) {
            updateThumbnailUrl();
        }

        setVideoCount(itemListAdapter.getItemsList().size());
        saveChanges();
    }

    private void saveChanges() {
        if (isModified == null || debouncedSaveSignal == null) {
            return;
        }

        isModified.set(true);
        debouncedSaveSignal.onNext(System.currentTimeMillis());
    }

    private Disposable getDebouncedSaver() {
        if (debouncedSaveSignal == null) {
            return Disposable.empty();
        }

        return debouncedSaveSignal
                .debounce(SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> saveImmediate(), throwable ->
                        showError(new ErrorInfo(throwable, UserAction.SOMETHING_ELSE,
                                "Debounced saver")));
    }

    private void saveImmediate() {
        if (playlistManager == null || itemListAdapter == null) {
            return;
        }

        // List must be loaded and modified in order to save
        if (isLoadingComplete == null || isModified == null
                || !isLoadingComplete.get() || !isModified.get()) {
            Log.w(TAG, "Attempting to save playlist when local playlist "
                    + "is not loaded or not modified: playlist id=[" + playlistId + "]");
            return;
        }

        final List<LocalItem> items = itemListAdapter.getItemsList();
        final List<Long> streamIds = new ArrayList<>(items.size());
        for (final LocalItem item : items) {
            if (item instanceof PlaylistStreamEntry) {
                streamIds.add(((PlaylistStreamEntry) item).getStreamId());
            }
        }

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + playlistId + "] "
                    + "with [" + streamIds.size() + "] items");
        }

        final Disposable disposable = playlistManager.updateJoin(playlistId, streamIds)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            if (isModified != null) {
                                isModified.set(false);
                            }
                        },
                        throwable -> showError(new ErrorInfo(throwable,
                                UserAction.REQUESTED_BOOKMARK, "Saving playlist"))
                );
        disposables.add(disposable);
    }


    private ItemTouchHelper.SimpleCallback getItemTouchCallback() {
        int directions = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
        if (shouldUseGridLayout(requireContext())) {
            directions |= ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
        }
        return new ItemTouchHelper.SimpleCallback(directions,
                ItemTouchHelper.ACTION_STATE_IDLE) {
            @Override
            public int interpolateOutOfBoundsScroll(@NonNull final RecyclerView recyclerView,
                                                    final int viewSize,
                                                    final int viewSizeOutOfBounds,
                                                    final int totalSize,
                                                    final long msSinceStartScroll) {
                final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView,
                        viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll);
                final int minimumAbsVelocity = Math.max(MINIMUM_INITIAL_DRAG_VELOCITY,
                        Math.abs(standardSpeed));
                return minimumAbsVelocity * (int) Math.signum(viewSizeOutOfBounds);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder source,
                                  @NonNull final RecyclerView.ViewHolder target) {
                if (source.getItemViewType() != target.getItemViewType()
                        || itemListAdapter == null) {
                    return false;
                }

                final int sourceIndex = source.getBindingAdapterPosition();
                final int targetIndex = target.getBindingAdapterPosition();
                final boolean isSwapped = itemListAdapter.swapItems(sourceIndex, targetIndex);
                if (isSwapped) {
                    saveChanges();
                }
                return isSwapped;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder,
                                 final int swipeDir) {
            }
        };
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private PlayQueue getPlayQueueStartingAt(final PlaylistStreamEntry infoItem) {
        return getPlayQueue(Math.max(itemListAdapter.getItemsList().indexOf(infoItem), 0));
    }

    protected void showInfoItemDialog(final PlaylistStreamEntry item) {
        final StreamInfoItem infoItem = item.toStreamInfoItem();

        try {
            final Context context = getContext();
            final InfoItemDialog.Builder dialogBuilder =
                    new InfoItemDialog.Builder(getActivity(), context, this, infoItem);

            // add entries in the middle
            dialogBuilder.addAllEntries(
                    StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL,
                    StreamDialogDefaultEntry.DELETE
            );

            // set custom actions
            // all entries modified below have already been added within the builder
            dialogBuilder
                    .setAction(
                            StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND,
                            (f, i) -> NavigationHelper.playOnBackgroundPlayer(
                                    context, getPlayQueueStartingAt(item), true))
                    .setAction(
                            StreamDialogDefaultEntry.SET_AS_PLAYLIST_THUMBNAIL,
                            (f, i) ->
                                    changeThumbnailUrl(item.getStreamEntity().getThumbnailUrl()))
                    .setAction(
                            StreamDialogDefaultEntry.DELETE,
                            (f, i) -> deleteItem(item))
                    .create()
                    .show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, infoItem);
        }
    }

    private void setInitialData(final long pid, final String title) {
        this.playlistId = pid;
        this.name = !TextUtils.isEmpty(title) ? title : "";
    }

    private void setVideoCount(final long count) {
        if (activity != null && headerBinding != null) {
            headerBinding.playlistStreamCount.setText(Localization
                    .localizeStreamCount(activity, count));
        }
    }

    private PlayQueue getPlayQueue() {
        return getPlayQueue(0);
    }

    private PlayQueue getPlayQueue(final int index) {
        if (itemListAdapter == null) {
            return new SinglePlayQueue(Collections.emptyList(), 0);
        }

        final List<LocalItem> infoItems = itemListAdapter.getItemsList();
        final List<StreamInfoItem> streamInfoItems = new ArrayList<>(infoItems.size());
        for (final LocalItem item : infoItems) {
            if (item instanceof PlaylistStreamEntry) {
                streamInfoItems.add(((PlaylistStreamEntry) item).toStreamInfoItem());
            }
        }
        return new SinglePlayQueue(streamInfoItems, index);
    }
}

