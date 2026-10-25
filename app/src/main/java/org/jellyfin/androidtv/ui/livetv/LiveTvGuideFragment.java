package org.jellyfin.androidtv.ui.livetv;

import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding;
import org.jellyfin.androidtv.ui.AsyncImageView;
import org.jellyfin.androidtv.ui.GuideChannelHeader;
import org.jellyfin.androidtv.ui.GuidePagingButton;
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener;
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView;
import org.jellyfin.androidtv.ui.ObservableScrollView;
import org.jellyfin.androidtv.ui.ProgramGridCell;
import org.jellyfin.androidtv.ui.ScrollViewListener;
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRowView;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.PlaybackHelper;
import org.jellyfin.androidtv.util.TextUtilsKt;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.EmptyResponse;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.sdk.model.api.BaseItemDto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jellyfin.androidtv.ui.livetv.multiview.GuideMultiViewPick;

import kotlin.Lazy;
import timber.log.Timber;

public class LiveTvGuideFragment extends Fragment implements LiveTvGuide, View.OnKeyListener {
    public static final int GUIDE_ROW_HEIGHT_DP = 55;
    public static final int GUIDE_ROW_WIDTH_PER_MIN_DP = 7;
    /** The most channels on one guide page (and per programs request, TvManagerHelper). */
    public static final int PAGE_SIZE = 75;
    // Rows in a screenful at least (more when the screen holds more): the most added in one frame
    static final int FIRST_BATCH_MIN = 8;
    public static final int NORMAL_HOURS = 9;
    public static final int FILTERED_HOURS = 4;

    private TextView mDisplayDate;
    private TextView mTitle;
    private TextView mChannelStatus;
    private TextView mFilterStatus;
    private TextView mSummary;
    private AsyncImageView mImage;
    private LinearLayout mInfoRow;
    private LinearLayout mChannels;
    private LinearLayout mTimeline;
    private LinearLayout mProgramRows;
    private ObservableScrollView mChannelScroller;
    private HorizontalScrollView mTimelineScroller;
    private View mSpinner;
    private View mResetButton;
    private TextView mProgramTime;
    private TextView mViewFavorites;
    private TextView mViewAll;
    private LinearLayout mDays;
    private View mEmptyFavorites;
    private View mShowAllButton;
    private View mNowLine;
    private TextView mNowPill;

    // Favorites / All: null until the saved choice is read (Jellyfin display preferences, shared with the web)
    private GuideView mView = null;
    private int mFavoriteCount = -1;
    private int mAllCount = -1;
    // When the guide data ends (furthest programme end in this view); null = unknown
    private LocalDateTime mFurthestEnd;
    private List<LocalDate> mGuideDays = new ArrayList<>();
    // A load started before the last toggle answers into nothing
    private int mLoadGeneration = 0;
    // All shows its first page before the whole list has come (mAllChannels is that page until then)
    private boolean mPartialList = false;
    private boolean mRestLoading = false;
    // All shown from the list read last time: read it again once the first screen is up
    private boolean mRelistAfterPaint = false;
    // When load() started, for the GuideTiming lines (0: the first screen of this load is logged)
    private long mLoadStartMs = 0;
    private boolean mFirstScreenLogged = false;
    // After Favorites / All, focus stays on the toggle instead of jumping into the grid
    private boolean mKeepHeaderFocus = false;

    BaseItemDto mSelectedProgram;
    // The live preview in the header's box (GuidePreview)
    private GuidePreview mPreview;
    RelativeLayout mSelectedProgramView;
    // The press whose long press opened a menu (see onKey)
    private long mLongPressDownTime = -1;

    private List<BaseItemDto> mAllChannels;
    private UUID mFirstFocusChannelId;
    private boolean focusAtEnd;
    private GuideFilters mFilters = new GuideFilters();

    private LocalDateTime mCurrentGuideStart = LocalDateTime.now();
    private LocalDateTime mCurrentGuideEnd;
    private int mCurrentDisplayChannelStartNdx = 0;
    private int mCurrentDisplayChannelEndNdx = 0;

    // Channels on one page: fewer on a small-memory TV (GuidePageSize.kt), where building 75
    // rows of program cells at once took seconds and most of the heap.
    private int pageSize = PAGE_SIZE;

    private int guideRowHeightPx;
    private int guideRowWidthPerMinPx;

    private Handler mHandler = new Handler();

    private final Lazy<PlaybackHelper> playbackHelper = inject(PlaybackHelper.class);
    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);
    private final Lazy<PlaybackLauncher> playbackLauncher = inject(PlaybackLauncher.class);
    // Multi-view: the button next to Recordings and the guide's pick mode
    private GuideMultiViewPick mMultiView;

    // Favourite and lineup changes while the guide is open, applied in place (GuideLiveUpdates)
    private GuideLiveUpdates mLiveUpdates;
    private ObservableScrollView mProgramVScroller;
    // Programmes of a page are on their way (paging, a new day): the rows are about to be replaced
    private boolean mProgramsLoading = false;
    // A page's rows are going on screen as they are built, a few per frame (DisplayProgramsTask.addRowsInFrame)
    private boolean mRowsAdding = false;
    private int mProgramsToken = 0;
    private ObservableHorizontalScrollView mProgramHScroller;
    // Where the guide was when it was left (onPause): the next load comes back there
    @Nullable private GuidePlace mPlace;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("GuideTiming", "guide created at " + android.os.SystemClock.uptimeMillis());
        mLiveUpdates = new GuideLiveUpdates(this);
        mLiveUpdates.start();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        guideRowHeightPx = Utils.convertDpToPixel(requireContext(), GUIDE_ROW_HEIGHT_DP);
        guideRowWidthPerMinPx = Utils.convertDpToPixel(requireContext(), GUIDE_ROW_WIDTH_PER_MIN_DP);
        pageSize = GuidePageSizeKt.guidePageSize(requireContext());

        LiveTvGuideBinding binding = LiveTvGuideBinding.inflate(getLayoutInflater(), container, false);

        mDisplayDate = binding.displayDate;
        mTitle = binding.title;
        mSummary = binding.summary;
        mChannelStatus = binding.channelsStatus;
        mFilterStatus = binding.filterStatus;
        mChannelStatus.setTextColor(Color.GRAY);
        mFilterStatus.setTextColor(Color.GRAY);
        mInfoRow = binding.infoRow;
        mImage = binding.programImage;
        mChannels = binding.channels;
        mTimeline = binding.timeline;
        mProgramRows = binding.programRows;
        mSpinner = binding.spinner;
        mSpinner.setVisibility(View.VISIBLE);


        mProgramTime = binding.programTime;
        mDays = binding.days;
        mEmptyFavorites = binding.emptyFavorites;
        mShowAllButton = binding.showAllButton;
        mNowLine = binding.nowLine;
        mNowPill = binding.nowPill;
        mViewFavorites = binding.viewFavorites;
        mViewAll = binding.viewAll;
        mViewFavorites.setOnClickListener(v -> setView(GuideView.FAVORITES));
        mViewAll.setOnClickListener(v -> setView(GuideView.ALL));
        mShowAllButton.setOnClickListener(v -> setView(GuideView.ALL));
        LiveTvGuideFragmentHelperKt.setupGuideLinks(this, binding);
        mMultiView = new GuideMultiViewPick(this, binding, mChannels);
        updateToggle();

        mResetButton = binding.resetButton;
        mResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pageGuideTo(LocalDateTime.now());
            }
        });

        mProgramRows.setFocusable(false);
        mChannelScroller = binding.channelScroller;
        ObservableScrollView programVScroller = binding.programVScroller;
        mProgramVScroller = programVScroller;
        programVScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                mChannelScroller.scrollTo(x, y);
            }
        });
        mChannelScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                programVScroller.scrollTo(x, y);
            }
        });

        mTimelineScroller = binding.timelineHScroller;
        mTimelineScroller.setFocusable(false);
        mTimelineScroller.setFocusableInTouchMode(false);
        mTimeline.setFocusable(false);
        mTimeline.setFocusableInTouchMode(false);
        mChannelScroller.setFocusable(false);
        mChannelScroller.setFocusableInTouchMode(false);
        ObservableHorizontalScrollView programHScroller = binding.programHScroller;
        mProgramHScroller = programHScroller;
        programHScroller.setScrollViewListener(new HorizontalScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableHorizontalScrollView scrollView, int x, int y, int oldx, int oldy) {
                mTimelineScroller.scrollTo(x, y);
            }
        });

        programHScroller.setFocusable(false);
        programHScroller.setFocusableInTouchMode(false);

        mChannels.setFocusable(false);
        mChannelScroller.setFocusable(false);

        mPreview = new GuidePreview(this, binding.programImage, binding.previewSurface, binding.title, this::isInGrid);
        mPreview.start();

        // A known Live TV problem, in the server's words (Tentacle FamilyStatus)
        LiveTvStatusNotice.onGuideOpened(this);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Back in the grid: the top row first (the switch shown), as on Home; Back again leaves
        new GuideBackToTop(this, this::isInGrid, () -> mView == GuideView.ALL ? mViewAll : mViewFavorites).attach(view);
        // Added after it, so while picking channels for multi-view Back cancels the pick first
        mMultiView.attach(getViewLifecycleOwner());
    }

    private int getGuideHours() {
        return mFilters.any() ? FILTERED_HOURS : NORMAL_HOURS;
    }

    private void load() {
        final int generation = ++mLoadGeneration;
        mProgramsLoading = false;
        mPartialList = false;
        mRestLoading = false;
        mRelistAfterPaint = false;
        if (mLoadStartMs == 0) {
            mLoadStartMs = android.os.SystemClock.uptimeMillis();
            mFirstScreenLogged = false;
            android.util.Log.i("GuideTiming", "load at " + mLoadStartMs);
        }
        // Back from a channel: the hours page it was on, when that hasn't gone by
        GuidePlace place = pendingPlace();
        mCurrentGuideStart = place != null ? GuidePlaces.placeGuideStart(place, LocalDateTime.now()) : GuideModel.guideStartFor(LocalDateTime.now());
        fillTimeLine(mCurrentGuideStart, getGuideHours());
        mSpinner.setVisibility(View.VISIBLE);
        mEmptyFavorites.setVisibility(View.GONE);

        if (mView == null) {
            // The person's last choice first (shared with Tentacle's web guide); Favorites when none
            TvManagerHelperKt.loadGuideView(this, view -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                mView = view;
                timing("view known: " + view);
                load();
                return null;
            });
            return;
        }
        updateToggle();

        if (mView == GuideView.FAVORITES) {
            TvManager.loadFavoriteChannels(this, (channels, allCount) -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (channels == null) {
                    onChannelsFailed();
                    return null;
                }
                if (allCount != null) mAllCount = allCount;
                mFavoriteCount = channels.size();
                List<UUID> ids = new ArrayList<>();
                for (BaseItemDto channel : channels) ids.add(channel.getId());
                onChannelsLoaded(channels, GuideModel.indexOfOrZero(ids, TvManager.getLastLiveTvChannel()), generation);
                return null;
            });
        } else if (TvManager.getCachedAllChannels() != null) {
            // Shown at once from the list read last time (also by the player); read again after
            List<BaseItemDto> all = TvManager.getCachedAllChannels();
            mAllCount = all.size();
            mFavoriteCount = countFavorites(all);
            mRelistAfterPaint = true;
            onChannelsLoaded(all, TvManager.lastChannelIndex(all), generation);
        } else if (!mFilters.any()) {
            // The first page first (1,700 channels take seconds to read on a TV), the rest after it shows
            TvManagerHelperKt.loadFirstGuidePage(this, pageSize, (page, allCount, favoriteCount) -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (page == null) {
                    onChannelsFailed();
                    return null;
                }
                if (allCount != null) mAllCount = allCount;
                if (favoriteCount != null) mFavoriteCount = favoriteCount;
                mPartialList = allCount == null || allCount > page.size();
                onChannelsLoaded(page, TvManager.lastChannelIndex(page), generation);
                return null;
            });
        } else {
            // Filters look through every channel: the whole list first
            TvManager.loadAllChannels(this, ndx -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (ndx < 0 || TvManager.getAllChannels() == null) {
                    onChannelsFailed();
                    return null;
                }
                List<BaseItemDto> all = TvManager.getAllChannels();
                mAllCount = all.size();
                mFavoriteCount = countFavorites(all);
                onChannelsLoaded(all, ndx, generation);
                return null;
            });
        }
    }

    private void onChannelsFailed() {
        mSpinner.setVisibility(View.GONE);
        // The server's plain words when it knows what is wrong, else "Unable to load channels"
        LiveTvStatusNotice.onLiveTvError(this, getString(R.string.msg_unable_load_channels));
    }

    private void onChannelsLoaded(List<BaseItemDto> channels, int lastNdx, int generation) {
        if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
        timing("channels in: " + channels.size());
        mAllChannels = channels;
        updateToggle();

        // The dates on offer stop where this view's guide data stops
        List<UUID> ids = null;
        if (mView == GuideView.FAVORITES) {
            ids = new ArrayList<>();
            for (BaseItemDto channel : channels) ids.add(channel.getId());
        }
        mFurthestEnd = null;
        updateDays();
        if (!channels.isEmpty()) {
            TvManagerHelperKt.loadFurthestProgramEnd(this, ids, end -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                mFurthestEnd = end;
                updateDays();
                return null;
            });
        }

        if (channels.isEmpty()) {
            showNoChannels(true);
            return;
        }

        // Back from a channel: the page it was on, when the channel to focus is on it
        GuidePlace place = pendingPlace();
        List<UUID> channelIds = idsOf(channels);
        Integer placed = place != null ? GuidePlaces.placePageStart(channelIds, place, GuidePlaces.placeFocus(place, TvManager.getLastLiveTvChannel(), channelIds), pageSize) : null;
        int start;
        if (placed != null) {
            start = placed;
        } else if (lastNdx >= pageSize) {
            // last channel is not in first page so grab a set where it will be in the middle
            start = lastNdx - (pageSize / 2);
        } else {
            start = 0; // just start at beginning
        }
        displayChannels(start, pageSize);
    }

    /** No channel in this view: Favorites says how to add one and offers All. */
    private void showNoChannels(boolean focusShowAll) {
        // A page still going on screen stops (its rows would land under the empty message)
        if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
        mDisplayProgramsTask = null;
        mRowsAdding = false;
        if (mView == GuideView.FAVORITES) {
            // One plain line on how to add one, and the way to All (focus there before the rows go)
            mEmptyFavorites.setVisibility(View.VISIBLE);
            if (focusShowAll) mShowAllButton.requestFocus();
        }
        mChannels.removeAllViews();
        mProgramRows.removeAllViews();
        mChannelStatus.setText("");
        mFilterStatus.setText("");
        mNowLine.setVisibility(View.GONE);
        mNowPill.setVisibility(View.GONE);
        mSpinner.setVisibility(View.GONE);
    }

    private static int countFavorites(List<BaseItemDto> channels) {
        int n = 0;
        for (BaseItemDto channel : channels) {
            if (channel.getUserData() != null && channel.getUserData().isFavorite()) n++;
        }
        return n;
    }

    /** Favorites / All: remembered for this person (on the server, so the web follows) and shown. */
    private void setView(GuideView view) {
        if (view == mView) return;
        mView = view;
        TvManagerHelperKt.saveGuideView(this, view);
        mPlace = null;
        mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
        mKeepHeaderFocus = true;
        updateToggle();
        load();
        (view == GuideView.FAVORITES ? mViewFavorites : mViewAll).requestFocus();
    }

    private void updateToggle() {
        if (mViewFavorites == null) return;
        mViewFavorites.setText(LiveTvGuideFragmentHelperKt.guideToggleLabel(getString(R.string.lbl_guide_favorites), mFavoriteCount < 0 ? null : mFavoriteCount));
        mViewAll.setText(LiveTvGuideFragmentHelperKt.guideToggleLabel(getString(R.string.lbl_guide_all), mAllCount < 0 ? null : mAllCount));
        mViewFavorites.setSelected(mView == GuideView.FAVORITES);
        mViewAll.setSelected(mView == GuideView.ALL);
    }

    /** A channel's favourite changed (heart in the popup, or holding OK on its name): new counts. */
    public void onFavoritesChanged() {
        if (mView == GuideView.ALL && mPartialList) {
            // The whole list (on its way) brings the count
        } else if (mView == GuideView.ALL && TvManager.getAllChannels() != null) {
            mFavoriteCount = countFavorites(TvManager.getAllChannels());
        } else if (mView == GuideView.FAVORITES && mAllChannels != null) {
            mFavoriteCount = countFavorites(mAllChannels);
        }
        updateToggle();
        // In Favorites an unstarred channel's row goes now, not only when the server's message comes
        if (mLiveUpdates != null) mLiveUpdates.recompute();
    }

    /** Today up to the last day with guide data; no choice when only today has data. */
    private void updateDays() {
        if (mDays == null || !isAdded()) return;
        LocalDateTime now = LocalDateTime.now();
        mGuideDays = GuideModel.guideDays(now, mFurthestEnd);
        mDays.removeAllViews();
        if (mGuideDays.size() < 2 || mAllChannels == null || mAllChannels.isEmpty()) {
            mDays.setVisibility(View.GONE);
            return;
        }
        mDays.setVisibility(View.VISIBLE);
        LocalDate shown = mCurrentGuideStart.toLocalDate();
        for (int i = 0; i < mGuideDays.size(); i++) {
            final LocalDate day = mGuideDays.get(i);
            TextView chip = new TextView(requireContext(), null, 0, R.style.GuideChip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) params.setMarginStart(Utils.convertDpToPixel(requireContext(), 6));
            chip.setLayoutParams(params);
            chip.setText(GuideModel.guideDayLabel(day, now.toLocalDate(), getString(R.string.lbl_today), getString(R.string.lbl_tomorrow), java.util.Locale.getDefault()));
            chip.setSelected(day.equals(shown));
            chip.setOnClickListener(v -> {
                mKeepHeaderFocus = true;
                pageGuideTo(day.equals(LocalDate.now()) ? LocalDateTime.now() : day.atStartOfDay());
            });
            mDays.addView(chip);
        }
    }

    /** The day chip of the day shown is the chosen one (also after paging into the next day). */
    private void updateDaySelection() {
        if (mDays == null) return;
        LocalDate shown = mCurrentGuideStart.toLocalDate();
        for (int i = 0; i < mDays.getChildCount() && i < mGuideDays.size(); i++) {
            mDays.getChildAt(i).setSelected(mGuideDays.get(i).equals(shown));
        }
    }

    public void refreshFavorite(UUID channelId){
        BaseItemDto channel = TvManager.findChannel(channelId);
        if (channel == null) return;
        for (int i = 0; i < mChannels.getChildCount(); i++) {
            View child = mChannels.getChildAt(i);
            if (!(child instanceof GuideChannelHeader)) continue;
            GuideChannelHeader gch = (GuideChannelHeader) child;
            if (gch.getChannel().getId().equals(channelId)) {
                gch.setChannel(channel);
                gch.refreshFavorite();
            }
        }
        onFavoritesChanged();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Back in front (from the player, another screen, the TV's home): a change meanwhile wasn't
        // heard, so the channels are read once and only what differs changes
        if (!doLoad()) {
            // Still as it was (the rows stayed): nothing to come back to
            mPlace = null;
            if (mLiveUpdates != null) mLiveUpdates.relist();
        }
        mHandler.removeCallbacks(mNowTick);
        mHandler.postDelayed(mNowTick, 60_000);
        // Back from a programme's page or the player with focus still on a programme on now: its
        // preview starts again after the rest (onPause stopped it)
        if (mSelectedProgramView != null) notePreviewFocus(mSelectedProgramView);
    }

    /** Loads the guide when it is stale, forced or empty; true when it does. */
    protected boolean doLoad() {
        // Read the filters every time: the Filters panel saves them and then calls doLoad(),
        // and with the load in onResume only, a change applied after leaving the guide.
        mFilters.load();
        // The guide's filters are gone (Lucas, 24 Oct: "not really working too well"): ones saved
        // before would hide channels with no way left to turn them off
        if (mFilters.any()) mFilters.clear();
        if (TvManager.shouldForceReload() || mCurrentGuideStart.plusMinutes(30).isBefore(LocalDateTime.now()) || mChannels.getChildCount() == 0) {
            load();

            GuidePlace place = pendingPlace();
            mFirstFocusChannelId = place != null ? GuidePlaces.placeFocus(place, TvManager.getLastLiveTvChannel(), mAllChannels != null ? idsOf(mAllChannels) : null) : TvManager.getLastLiveTvChannel();
            return true;
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mPreview != null) mPreview.stop();

        mHandler.removeCallbacks(mNowTick);
        // Leaving (a channel, a programme's page): kept to come back to (see GuidePlace)
        GuidePlace place = currentPlace();
        if (place != null) mPlace = place;
        if (mDisplayProgramsTask != null && mDisplayProgramsTask.getStatus() != AsyncTask.Status.FINISHED) {
            mDisplayProgramsTask.cancel(true);
            // A page only partly built: leave the guide empty so onResume reloads it
            mChannels.removeAllViews();
            mProgramRows.removeAllViews();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mCurrentGuideStart.isAfter(LocalDateTime.now())) {
            TvManager.forceReload(); //we paged ahead - force a re-load if we come back in
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) return onKeyUp(keyCode, event);
        else if (event.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) {
            // MainActivity hands a long press over twice (its onKeyDown, then onKeyLongPress): it
            // opened two menus on the Sony, so Back seemed not to close it, and a channel's star
            // toggled twice (nothing changed)
            if (event.getDownTime() == mLongPressDownTime) return true;
            mLongPressDownTime = event.getDownTime();
            return onKeyLongPress(keyCode);
        }
        else if (event.getAction() == KeyEvent.ACTION_DOWN) return onKeyDown(keyCode, event);
        return false;
    }

    private boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                event.startTracking();
                return true;
        }
        return false;
    }

    private boolean onKeyLongPress(int keyCode) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (mMultiView.isActive()) return true; // picking: a long press is a pick, on release
                showFocusedMenu();
                return true;
        }
        return false;
    }

    private boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                // The same menu as a long press of OK (the guide's filters are gone, #-menus)
                showFocusedMenu();
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (mMultiView.isActive()) {
                    // Picking channels for multi-view: OK ticks (or unticks) the row's channel
                    if (mSelectedProgramView instanceof ProgramGridCell && mSelectedProgram != null)
                        mMultiView.pick(mSelectedProgram.getChannelId());
                    else if (mSelectedProgramView instanceof GuideChannelHeader)
                        mMultiView.pick(((GuideChannelHeader) mSelectedProgramView).getChannel().getId());
                    return true;
                }
                if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                    if (mSelectedProgramView instanceof ProgramGridCell) {
                        if (mSelectedProgram.getStartDate().isBefore(LocalDateTime.now())) {
                            if (mPreview != null) mPreview.handOff(mSelectedProgram.getChannelId());
                            playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                        }
                        else
                            showProgramOptions();
                        return true;
                    } else if (mSelectedProgramView instanceof GuideChannelHeader) {
                        // Tuning directly to a channel
                        GuideChannelHeader channelHeader = (GuideChannelHeader) mSelectedProgramView;
                        if (mPreview != null) mPreview.handOff(channelHeader.getChannel().getId());
                        playbackHelper.getValue().getItemsToPlay(requireContext(), channelHeader.getChannel(), false, false, new Response<List<BaseItemDto>>(getLifecycle()) {
                            @Override
                            public void onResponse(List<BaseItemDto> response) {
                                if (!isActive()) return;
                                playbackLauncher.getValue().launch(requireContext(), response);
                            }
                        });
                    }
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (mMultiView.isActive()) {
                    mMultiView.start();
                    return true;
                }
                if (mSelectedProgram != null
                        && mSelectedProgram.getChannelId() != null) {
                    // tune to the current channel
                    playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                    return true;
                }

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isLast()
                        && GuideModel.hasGuideDataAfter(mCurrentGuideEnd, mFurthestEnd)) {
                    requestGuidePage(mCurrentGuideEnd);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isFirst()
                        && mSelectedProgram.getStartDate().isAfter(LocalDateTime.now())) {
                    focusAtEnd = true;
                    requestGuidePage(mCurrentGuideStart.minusHours(getGuideHours()));
                }
                break;
        }

        return false;
    }

    private void requestGuidePage(final LocalDateTime startTime) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lbl_load_guide_data)
                .setMessage(startTime.isAfter(mCurrentGuideStart) ? getString(R.string.msg_live_tv_next, getGuideHours()) : getString(R.string.msg_live_tv_prev, getGuideHours()))
                .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pageGuideTo(startTime);
                    }
                })
                .setNegativeButton(R.string.lbl_no, null)
                .show();
    }

    private void pageGuideTo(LocalDateTime startTime) {
        // Never before the current half hour: the guide shows what is on now and later
        if (startTime.isBefore(LocalDateTime.now())) startTime = GuideModel.guideStartFor(LocalDateTime.now());
        Timber.i("page to %s", startTime);
        TvManager.forceReload(); // don't allow cache
        if (mSelectedProgram != null) {
            mFirstFocusChannelId = mSelectedProgram.getChannelId();
        }
        fillTimeLine(startTime, getGuideHours());
        loadProgramData();
    }

    /** A long press of OK or the Menu key: the programme's menu, or a channel name's favourite. */
    private void showFocusedMenu() {
        if (mSelectedProgramView instanceof ProgramGridCell) showProgramOptions();
        else if (mSelectedProgramView instanceof GuideChannelHeader) GuideMenusKt.favoriteFromGuide(this);
    }

    public void showProgramOptions() {
        if (mSelectedProgram == null) return;
        GuideMenusKt.showProgramMenu(this);
    }

    public void displayChannels(int start, int max) {
        // GuidePagingButton asks for PAGE_SIZE channels: never more than this TV's page
        int end = start + Math.min(max, pageSize);
        if (end > mAllChannels.size()) {
            end = mAllChannels.size();
        }

        if (mFilters.any()) {
            // if we are filtered, then we need to get programs for all channels
            mCurrentDisplayChannelStartNdx = 0;
            mCurrentDisplayChannelEndNdx = mAllChannels.size()-1;
        } else {
            mCurrentDisplayChannelStartNdx = start;
            mCurrentDisplayChannelEndNdx = end - 1;
        }
        Timber.d("*** Display channels pre-execute");
        mSpinner.setVisibility(View.VISIBLE);

        loadProgramData();
    }

    private void loadProgramData() {
        // A page still going on screen stops here: its rows are about to be replaced
        if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
        mRowsAdding = false;
        mProgramRows.removeAllViews();
        mChannels.removeAllViews();
        mChannelStatus.setText("");
        mFilterStatus.setText("");
        if (mAllChannels == null || mAllChannels.isEmpty()) return;
        // The guide's own channels (Favorites or All), one page of them
        int last = Math.min(mCurrentDisplayChannelEndNdx, mAllChannels.size() - 1);
        UUID[] ids = new UUID[Math.max(0, last - mCurrentDisplayChannelStartNdx + 1)];
        for (int i = 0; i < ids.length; i++) ids[i] = mAllChannels.get(mCurrentDisplayChannelStartNdx + i).getId();
        final int generation = mLoadGeneration;
        final int token = ++mProgramsToken;
        mProgramsLoading = true;
        timing("programmes asked: " + ids.length + " channels");
        TvManager.getProgramsAsync(this, ids, mCurrentGuideStart, mCurrentGuideEnd, new EmptyResponse(getLifecycle()) {
            @Override
            public void onResponse() {
                if (token == mProgramsToken) mProgramsLoading = false;
                if (!isActive() || generation != mLoadGeneration) return;
                Timber.d("*** Programs response");
                timing("programmes in");
                if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
                mDisplayProgramsTask = new DisplayProgramsTask();
                mDisplayProgramsTask.execute(mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx);
            }
        });
    }

    // What one guide row cost on the main thread when last measured (ms; < 0: not yet in this
    // process). A later open starts with a batch of that size instead of one row.
    private static double sRowCostMs = -1;

    DisplayProgramsTask mDisplayProgramsTask;
    class DisplayProgramsTask extends AsyncTask<Integer, Integer, Void> {

        View firstFocusView;
        int displayedChannels = 0;
        // Each row (its programme cells and its channel header) is built and measured off the main
        // thread, then goes on screen as soon as it is built, as many per frame as fit the frame
        // budget by the measured cost per row (GuideRowPacing): one row first when the cost is not
        // known yet. Eight rows at once, built cold, were one 0.8-1.4 s frame on the Sony the first
        // time the guide opened after start.
        // Filled by the background thread up to [built]; the main thread reads below it only.
        BaseItemDto[] rowChannels = new BaseItemDto[0];
        LinearLayout[] rows = new LinearLayout[0];
        GuideChannelHeader[] headers = new GuideChannelHeader[0];
        volatile int built = 0;
        volatile boolean buildDone = false;
        // A screenful (most rows in one frame), and how many are on screen already (main thread only)
        int screenful = FIRST_BATCH_MIN;
        int added = 0;
        // Rows the last frame added; a frame is asked for
        int lastBatch = 0;
        boolean frameScheduled = false;
        // Focused while the rows go up: not again at the end (the person may have moved on)
        View focusedEarly;
        boolean focusChannelFound = false;
        // Sizes for measuring off the main thread the way the guide will (0: unknown, not measured)
        int headerWidth = 0;
        final Context context = requireContext();
        // This page's channel list: a Favorites / All switch mid-way swaps mAllChannels
        final List<BaseItemDto> channels = mAllChannels;

        @Override
        protected void onPreExecute() {
            Timber.d("*** Display programs pre-execute");
            mRowsAdding = false;
            mChannels.removeAllViews();
            mProgramRows.removeAllViews();
            displayedChannels = 0;
            addTopPagingRow();
            // The rows that fit in the guide's window, and one more for the partly shown one
            if (mProgramVScroller != null && mProgramVScroller.getHeight() > 0 && guideRowHeightPx > 0) {
                screenful = Math.max(FIRST_BATCH_MIN, mProgramVScroller.getHeight() / guideRowHeightPx + 1);
            }
            headerWidth = Math.max(0, mChannels.getWidth() - mChannels.getPaddingLeft() - mChannels.getPaddingRight());
        }

        @Override
        protected Void doInBackground(Integer... params) {
            int start = params[0];
            int end = params[1];
            int size = Math.max(0, Math.min(end, channels.size() - 1) - start + 1);
            BaseItemDto[] builtChannels = new BaseItemDto[size];
            LinearLayout[] builtRows = new LinearLayout[size];
            GuideChannelHeader[] builtHeaders = new GuideChannelHeader[size];
            rowChannels = builtChannels;
            rows = builtRows;
            headers = builtHeaders;

            Timber.d("*** About to iterate programs");
            timing("rows: building");
            LinearLayout prevRow = null;
            int count = 0;
            for (int i = start; i <= end; i++) {
                if (isCancelled()) return null;
                if (i >= channels.size()) break;
                final BaseItemDto channel = channels.get(i);
                List<BaseItemDto> programs = TvManager.getProgramsForChannel(channel.getId(), mFilters);
                final LinearLayout row = getProgramRow(programs, channel.getId());
                if (row == null) continue; // no row to show
                GuideChannelHeader header = getChannelHeader(context, channel);
                premeasure(row, header);

                // set focus parameters if we are not on first row
                // this makes focus movements more predictable for the grid view
                if (prevRow != null) {
                    TvManager.setFocusParams(row, prevRow, true);
                    TvManager.setFocusParams(prevRow, row, false);
                }
                prevRow = row;

                builtChannels[count] = channel;
                builtRows[count] = row;
                builtHeaders[count] = header;
                count++;
                built = count; // after the arrays: the main thread reads below it
                publishProgress(count);
            }
            return null;
        }

        /**
         * Measures a row and its header while they are not on screen yet, the way the guide will:
         * the text layouts and the first run of the layout code then happen here instead of in the
         * frame that shows them (a TextView keeps its layout when measured again at the same width).
         * Only for speed: anything going wrong leaves the measuring to the main thread.
         */
        private void premeasure(LinearLayout row, GuideChannelHeader header) {
            try {
                int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                row.measure(unspecified, unspecified);
                if (headerWidth > 0) header.measure(View.MeasureSpec.makeMeasureSpec(headerWidth, View.MeasureSpec.EXACTLY), unspecified);
            } catch (RuntimeException e) {
                Timber.w(e, "Guide row not measured ahead");
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            mRowsAdding = true;
            scheduleFrame();
        }

        /** Rows are built and not on screen yet: they go up at the next frame. */
        private void scheduleFrame() {
            if (frameScheduled) return;
            frameScheduled = true;
            mProgramRows.postOnAnimation(this::addRowsInFrame);
        }

        /** Adds the next rows built, as many as fit a frame, then measures what they cost. */
        private void addRowsInFrame() {
            frameScheduled = false;
            // A newer page (paging, a reload, the view switched) or the guide gone: this one stops
            if (mDisplayProgramsTask != this || isCancelled() || !isAdded() || getView() == null) {
                if (mDisplayProgramsTask == this) mRowsAdding = false;
                return;
            }
            int ready = built;
            if (added < ready) {
                int perFrame = GuideRowPacing.guideRowsPerFrame(sRowCostMs >= 0 ? sRowCostMs : null,
                        lastBatch > 0 ? lastBatch : null, GuideRowPacing.GUIDE_ROWS_FRAME_BUDGET_MS, screenful);
                int end = GuideModel.guideRowBatchEnd(added, ready, perFrame);
                final int count = end - added;
                final long began = android.os.SystemClock.uptimeMillis();
                boolean firstRows = added == 0;
                addRows(end);
                if (firstRows) {
                    timing("first row on screen");
                    mSpinner.setVisibility(View.GONE);
                    updateNowLine();
                }
                focusEarly();
                if (firstRows) onFirstScreen(end);
                lastBatch = count;
                // This message runs after the frame's layout and draw: their time is the rows' cost
                mProgramRows.post(() -> {
                    Double cost = GuideRowPacing.guideRowCost(android.os.SystemClock.uptimeMillis() - began, count);
                    if (cost != null) sRowCostMs = cost;
                    if (mDisplayProgramsTask != this || isCancelled()) return;
                    if (added < built || buildDone) scheduleFrame();
                });
                return;
            }
            if (buildDone) {
                mRowsAdding = false;
                onRowsAdded();
            }
            // else: the next row built schedules the next frame (onProgressUpdate)
        }

        /** Adds the rows from the first not yet on screen up to [end], with their channel names. */
        private void addRows(int end) {
            for (int i = added; i < end; i++) {
                BaseItemDto channel = rowChannels[i];
                LinearLayout row = rows[i];
                GuideChannelHeader header = headers[i];
                mChannels.addView(header);
                mMultiView.decorate(header);
                header.loadImage();
                mProgramRows.addView(row);
                if (i == 0 && firstFocusView == null) firstFocusView = row;
                // put focus on the last tuned channel
                if (channel.getId().equals(mFirstFocusChannelId)) {
                    firstFocusView = focusAtEnd ? row.getChildAt(row.getChildCount()-1) : row;
                    focusAtEnd = false;
                    mFirstFocusChannelId = null;
                    focusChannelFound = true;
                }
                displayedChannels++;
            }
            added = end;
        }

        /**
         * Focus while the rows go up: on the channel last watched as soon as its row is there, else
         * on the first row once a screenful is (or straight away when there is no channel to look
         * for). Back from a channel, focus goes once the place is back (restorePlace), not here.
         */
        private void focusEarly() {
            if (focusedEarly != null || firstFocusView == null || mKeepHeaderFocus || pendingPlace() != null) return;
            if (focusChannelFound || mFirstFocusChannelId == null || added >= screenful) {
                onNowIn(firstFocusView).requestFocus();
                focusedEarly = firstFocusView;
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Timber.d("*** Display programs post execute");
            timing("rows built: " + built);
            buildDone = true;
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                mRowsAdding = false;
                onRowsAdded();
                return;
            }
            // The rows built last (or none at all) still go up through the frames
            mRowsAdding = true;
            scheduleFrame();
        }

        @Override
        protected void onCancelled(Void aVoid) {
            // Its rows stop going up (a frame already asked for sees the cancel and adds nothing)
            if (mDisplayProgramsTask == this) mRowsAdding = false;
        }

        /** The whole page is on screen: paging rows, status, focus, then the rest of All. */
        private void onRowsAdded() {
            addBottomPagingRow();

            updateChannelStatus(displayedChannels);
            mFilterStatus.setText(mFilters.toString() + " for "+getGuideHours()+" hours");
            mFilterStatus.setTextColor(mFilters.any() ? Color.WHITE : ContextCompat.getColor(requireContext(), R.color.guide_text_muted));

            mResetButton.setVisibility(mCurrentGuideStart.isAfter(LocalDateTime.now()) ? View.VISIBLE : View.GONE); // show reset button if paged ahead

            mSpinner.setVisibility(View.GONE);
            updateNowLine();
            // Left for a channel while the rows went up: the place kept on leaving stays for the way back
            if (isResumed()) {
                if (firstFocusView != null && !mKeepHeaderFocus && firstFocusView != focusedEarly) {
                    onNowIn(firstFocusView).requestFocus();
                }
                if (pendingPlace() != null) restorePlace();
                // Something always has focus once the rows are up (after restorePlace's own post)
                final View fallback = firstFocusView;
                mProgramRows.post(() -> ensureFocus(fallback));
            }
            mKeepHeaderFocus = false;

            onFirstScreen(displayedChannels);
            timing("whole page on screen: " + displayedChannels + " rows");
            mLoadStartMs = 0;
            mFirstScreenLogged = false;

            // The first screen is up: now the rest of All, or a fresh read of the stored list
            if (mPartialList && !mRestLoading) loadRestOfChannels(mLoadGeneration);
            if (mRelistAfterPaint) {
                mRelistAfterPaint = false;
                if (mLiveUpdates != null) mLiveUpdates.relist();
            }
        }
    }

    /** Focus on [fallback] (the page's first or last-watched channel) when nothing in the guide has it. */
    private void ensureFocus(@Nullable View fallback) {
        if (!isResumed() || fallback == null || !fallback.isAttachedToWindow()) return;
        View focus = requireActivity().getCurrentFocus();
        if (focus == null || !focus.isShown() || !isDescendant(getView(), focus)) fallback.requestFocus();
    }

    private static boolean isDescendant(@Nullable View root, View view) {
        if (root == null) return false;
        for (android.view.ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == root) return true;
        }
        return false;
    }

    /** The whole All list, read after its first page shows; the page's rows stay, paging reaches the rest. */
    private void loadRestOfChannels(final int generation) {
        mRestLoading = true;
        TvManagerHelperKt.loadAllGuideChannels(this, channels -> {
            if (!isAdded() || generation != mLoadGeneration || !mPartialList) return null;
            mRestLoading = false;
            if (channels == null) {
                // Try again in a while; the first page stays meanwhile
                mHandler.postDelayed(() -> {
                    if (isAdded() && generation == mLoadGeneration && mPartialList && !mRestLoading) loadRestOfChannels(generation);
                }, 5_000);
                return null;
            }
            onRestOfChannels(TvManager.setLiveChannels(false, channels));
            return null;
        });
    }

    /**
     * Guide load milestones in logcat (tag `GuideTiming`, ms since the load began), in every build:
     * android.util.Log, as the release build's Timber drops info lines. `adb logcat -s GuideTiming`.
     */
    private void timing(String what) {
        if (mLoadStartMs == 0) return;
        android.util.Log.i("GuideTiming", what + " +" + (android.os.SystemClock.uptimeMillis() - mLoadStartMs) + " ms");
    }

    /** Logs the first screen once per load: when its rows are added, and when they are drawn. */
    private void onFirstScreen(int rowCount) {
        if (mLoadStartMs == 0 || mFirstScreenLogged) return;
        mFirstScreenLogged = true;
        timing("first screen, " + mView + ", " + rowCount + " rows" + (mPartialList ? " (first page)" : ""));
        final long start = mLoadStartMs;
        mProgramRows.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            boolean logged = false;
            @Override
            public void onDraw() {
                if (logged) return;
                logged = true;
                android.util.Log.i("GuideTiming", "first screen drawn +" + (android.os.SystemClock.uptimeMillis() - start) + " ms");
                mProgramRows.post(() -> mProgramRows.getViewTreeObserver().removeOnDrawListener(this));
            }
        });
    }

    private void onRestOfChannels(List<BaseItemDto> all) {
        android.util.Log.i("GuideTiming", "whole All list in, " + all.size() + " channels");
        List<BaseItemDto> page = mAllChannels;
        mPartialList = false;
        mAllCount = all.size();
        mFavoriteCount = countFavorites(all);
        mAllChannels = all;
        updateToggle();
        if (all.isEmpty()) {
            showNoChannels(false);
            return;
        }
        boolean busy = mProgramsLoading || mRowsAdding || (mDisplayProgramsTask != null && mDisplayProgramsTask.getStatus() != AsyncTask.Status.FINISHED);
        if (!busy && GuideModel.isFirstPageOf(idsOf(page), idsOf(all))) {
            // The rows shown are the list's first rows: only the paging row for the rest comes
            mCurrentDisplayChannelEndNdx = Math.min(mCurrentDisplayChannelEndNdx, all.size() - 1);
            addBottomPagingRow();
            updateChannelStatus(shownChannelIds().size());
        } else {
            // The channels changed between the two reads: this page anew from the whole list,
            // focus kept on the channel it is on (or where it is, outside the rows)
            View focus = getActivity() != null ? getActivity().getCurrentFocus() : null;
            boolean inRows = focus != null && isInGrid(focus);
            UUID refocus = GuideModel.guideRefocusChannel(inRows, mSelectedProgram != null ? mSelectedProgram.getChannelId() : null);
            if (refocus != null) mFirstFocusChannelId = refocus;
            mKeepHeaderFocus = focus != null && !inRows;
            displayChannels(0, pageSize);
        }
    }

    /** "Showing 75 of 1,700 channels". */
    private void updateChannelStatus(int displayed) {
        java.text.NumberFormat numbers = java.text.NumberFormat.getIntegerInstance();
        int total = mPartialList && mAllCount >= 0 ? mAllCount : mAllChannels.size();
        mChannelStatus.setText(getString(R.string.msg_guide_channels_count, numbers.format(displayed), numbers.format(total)));
    }

    /** The paging row for the channels above this page, first in both columns (none on the first page). */
    private void addTopPagingRow() {
        if (mCurrentDisplayChannelStartNdx <= 0 || mAllChannels == null || mCurrentDisplayChannelStartNdx > mAllChannels.size()) return;
        int pageUpStart = Math.max(0, mCurrentDisplayChannelStartNdx - pageSize);

        TextView placeHolder = new TextView(requireContext());
        placeHolder.setHeight(guideRowHeightPx);
        mChannels.addView(placeHolder, 0);

        String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(pageUpStart).getNumber(), mAllChannels.get(mCurrentDisplayChannelStartNdx - 1).getNumber());
        mProgramRows.addView(new GuidePagingButton(requireContext(), LiveTvGuideFragment.this, pageUpStart, label), 0);
    }

    /** The paging row for the channels below this page, last in both columns (none on the last page or with filters). */
    private void addBottomPagingRow() {
        if (mAllChannels == null || mCurrentDisplayChannelEndNdx >= mAllChannels.size() - 1 || mFilters.any()) return;
        int pageDnEnd = Math.min(mCurrentDisplayChannelEndNdx + pageSize, mAllChannels.size() - 1);

        TextView placeHolder = new TextView(requireContext());
        placeHolder.setHeight(guideRowHeightPx);
        mChannels.addView(placeHolder);

        String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(mCurrentDisplayChannelEndNdx + 1).getNumber(), mAllChannels.get(pageDnEnd).getNumber());
        mProgramRows.addView(new GuidePagingButton(requireContext(), LiveTvGuideFragment.this, mCurrentDisplayChannelEndNdx + 1, label));
    }

    // Live updates (GuideLiveUpdates): favourite and lineup changes applied to the rows in place.

    GuideView getGuideView() { return mView; }
    int getLoadGeneration() { return mLoadGeneration; }
    @Nullable List<BaseItemDto> getLiveChannels() { return mAllChannels; }

    /** The guide shows its channels and isn't loading: a live change may edit the rows. */
    boolean isLiveReady() {
        return isAdded() && getView() != null && mView != null && mAllChannels != null && !mPartialList
                && mSpinner.getVisibility() != View.VISIBLE
                && !mProgramsLoading
                && !mRowsAdding
                && (mDisplayProgramsTask == null || mDisplayProgramsTask.getStatus() == AsyncTask.Status.FINISHED);
    }

    /** The channel ids of the rows shown, top to bottom. */
    private List<UUID> shownChannelIds() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < mChannels.getChildCount(); i++) {
            View child = mChannels.getChildAt(i);
            if (child instanceof GuideChannelHeader) ids.add(((GuideChannelHeader) child).getChannel().getId());
        }
        return ids;
    }

    private static List<UUID> idsOf(List<BaseItemDto> channels) {
        List<UUID> ids = new ArrayList<>(channels.size());
        for (BaseItemDto channel : channels) ids.add(channel.getId());
        return ids;
    }

    /** The rows of [target] to show after a live change (indexes); null: show a page anew. */
    @Nullable
    private kotlin.ranges.IntRange liveWindow(List<BaseItemDto> target) {
        return GuideModel.guideLiveWindow(idsOf(mAllChannels), mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx, shownChannelIds(), idsOf(target));
    }

    /** The channels that get a new row when [target] is applied: their programmes are needed first. */
    List<UUID> rowsNeededFor(List<BaseItemDto> target) {
        List<UUID> needed = new ArrayList<>();
        if (mAllChannels == null) return needed;
        kotlin.ranges.IntRange window = liveWindow(target);
        if (window == null) return needed;
        java.util.Set<UUID> shown = new java.util.HashSet<>(shownChannelIds());
        java.util.Set<UUID> old = new java.util.HashSet<>(idsOf(mAllChannels));
        for (int i = window.getFirst(); i <= window.getLast(); i++) {
            UUID id = target.get(i).getId();
            // With filters a channel already in the list and not shown has nothing that passes
            if (!shown.contains(id) && (!mFilters.any() || !old.contains(id))) needed.add(id);
        }
        return needed;
    }

    /** Only the counts changed (the list is the same): the toggle shows them. */
    void onLiveCounts(@Nullable Integer allCount) {
        if (allCount != null && mView == GuideView.FAVORITES && allCount != mAllCount) {
            mAllCount = allCount;
            updateToggle();
        }
    }

    /** The view's guide data now ends at [end] (its channels changed): the dates follow, when they differ. */
    void onLiveGuideEnd(LocalDateTime end) {
        if (end.equals(mFurthestEnd)) return;
        mFurthestEnd = end;
        List<LocalDate> days = GuideModel.guideDays(LocalDateTime.now(), mFurthestEnd);
        boolean show = days.size() >= 2 && mAllChannels != null && !mAllChannels.isEmpty();
        if (days.equals(mGuideDays) && show == (mDays.getVisibility() == View.VISIBLE)) return;
        View focus = requireActivity().getCurrentFocus();
        int focusedDay = focus != null && focus.getParent() == mDays ? mDays.indexOfChild(focus) : -1;
        LocalDate day = focusedDay >= 0 && focusedDay < mGuideDays.size() ? mGuideDays.get(focusedDay) : null;
        updateDays();
        if (day != null) {
            int at = mGuideDays.indexOf(day);
            View chip = mDays.getChildAt(at >= 0 ? at : Math.min(focusedDay, mDays.getChildCount() - 1));
            if (chip != null) chip.requestFocus();
            else mViewFavorites.requestFocus();
        }
    }

    /** The place kept when the guide was left, for the view it shows; null when there is none. */
    @Nullable
    private GuidePlace pendingPlace() {
        return mPlace != null && mPlace.getView() == mView ? mPlace : null;
    }

    /** Where the guide is (see GuidePlace); null when it shows no channel rows. */
    @Nullable
    private GuidePlace currentPlace() {
        if (mView == null || mAllChannels == null || mChannels == null || mProgramVScroller == null || mProgramHScroller == null) return null;
        int y = mProgramVScroller.getScrollY();
        UUID topId = null;
        int offset = 0;
        // Positions from the programme rows (the ones this scroller moves); the names beside them
        for (int i = 0; i < mChannels.getChildCount() && i < mProgramRows.getChildCount(); i++) {
            View header = mChannels.getChildAt(i);
            View row = mProgramRows.getChildAt(i);
            if (header instanceof GuideChannelHeader && row.getBottom() > y) {
                topId = ((GuideChannelHeader) header).getChannel().getId();
                offset = y - row.getTop();
                break;
            }
        }
        if (topId == null) return null;
        LocalDateTime left = mCurrentGuideStart.plusMinutes(guideRowWidthPerMinPx > 0 ? mProgramHScroller.getScrollX() / guideRowWidthPerMinPx : 0);
        UUID pageStart = mCurrentDisplayChannelStartNdx >= 0 && mCurrentDisplayChannelStartNdx < mAllChannels.size()
                ? mAllChannels.get(mCurrentDisplayChannelStartNdx).getId() : null;
        UUID focus = mSelectedProgram != null ? mSelectedProgram.getChannelId() : null;
        return new GuidePlace(mView, mCurrentGuideStart, pageStart, topId, offset, left,
                GuidePlaces.placeAtNow(left, LocalDateTime.now()), focus, TvManager.getLastLiveTvChannel());
    }

    /**
     * Back where the guide was left, once the page's rows are laid out: the same rows and time in
     * view, focus on the channel played last (Channel Up / Down in the player included).
     */
    private void restorePlace() {
        final GuidePlace place = mPlace;
        mPlace = null;
        if (place == null || mProgramVScroller == null) return;
        final UUID focusId = GuidePlaces.placeFocus(place, TvManager.getLastLiveTvChannel(), mAllChannels != null ? idsOf(mAllChannels) : null);
        mProgramRows.post(() -> {
            if (!isAdded() || mProgramVScroller == null) return;
            Integer topRowTop = null;
            Integer focusRowTop = null;
            LinearLayout focusRow = null;
            for (int i = 0; i < mChannels.getChildCount() && i < mProgramRows.getChildCount(); i++) {
                View header = mChannels.getChildAt(i);
                if (!(header instanceof GuideChannelHeader)) continue;
                UUID id = ((GuideChannelHeader) header).getChannel().getId();
                View row = mProgramRows.getChildAt(i);
                if (id.equals(place.getTopId())) topRowTop = row.getTop();
                if (id.equals(focusId) && row instanceof LinearLayout) {
                    focusRowTop = row.getTop();
                    focusRow = (LinearLayout) row;
                }
            }
            final int x = GuidePlaces.placeScrollX(place, mCurrentGuideStart, guideRowWidthPerMinPx);
            final int y = GuidePlaces.placeScrollY(topRowTop, place.getTopOffset(), focusRowTop, guideRowHeightPx, mProgramVScroller.getHeight());
            if (focusRow != null) {
                LocalDateTime at = place.getAtNow() ? LocalDateTime.now() : mCurrentGuideStart.plusMinutes(guideRowWidthPerMinPx > 0 ? x / guideRowWidthPerMinPx : 0);
                cellAt(focusRow, at).requestFocus();
            }
            // After the scroll the focus asked for (it runs at the next layout)
            mProgramRows.post(() -> {
                if (!isAdded() || mProgramVScroller == null) return;
                mProgramVScroller.scrollTo(0, y);
                mProgramHScroller.scrollTo(x, 0);
            });
        });
    }

    /** The cell of [row] at [time] (the one on then), else its first. */
    private static View cellAt(LinearLayout row, @Nullable LocalDateTime time) {
        if (time != null) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View cell = row.getChildAt(i);
                if (cell instanceof ProgramGridCell) {
                    BaseItemDto program = ((ProgramGridCell) cell).getProgram();
                    if (program != null && program.getEndDate() != null && program.getEndDate().isAfter(time)) return cell;
                }
            }
        }
        return row.getChildCount() > 0 ? row.getChildAt(0) : row;
    }

    /** Puts [view] at [index] of [parent], moving it only when it isn't there already. */
    private static void placeAt(LinearLayout parent, View view, int index) {
        if (parent.getChildAt(index) == view) return;
        if (view.getParent() == parent) parent.removeView(view);
        parent.addView(view, Math.min(index, parent.getChildCount()));
    }

    /** Up / down between programme rows after rows came or went: each cell to the one at its time. */
    private void relinkRows() {
        LinearLayout prev = null;
        for (int i = 0; i < mProgramRows.getChildCount(); i++) {
            View child = mProgramRows.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                prev = null;
                continue;
            }
            LinearLayout row = (LinearLayout) child;
            for (int c = 0; c < row.getChildCount(); c++) {
                row.getChildAt(c).setNextFocusUpId(View.NO_ID);
                row.getChildAt(c).setNextFocusDownId(View.NO_ID);
            }
            if (prev != null) {
                TvManager.setFocusParams(row, prev, true);
                TvManager.setFocusParams(prev, row, false);
            }
            prev = row;
        }
    }

    /**
     * Shows [target] instead of the guide's channels without a reload: rows of channels that went are
     * removed, new ones added (their programmes are already in TvManager), changed ones redrawn, and
     * the rest moved only when the order changed. Focus stays on its row and programme (the nearest
     * row when its row went) and the rows on screen stay where they were.
     */
    void applyLiveChannels(List<BaseItemDto> target, @Nullable Integer allCount) {
        if (!isLiveReady()) return;
        boolean favorites = mView == GuideView.FAVORITES;
        List<BaseItemDto> old = mAllChannels;
        kotlin.ranges.IntRange window = liveWindow(target);
        View focus = requireActivity().getCurrentFocus();

        List<BaseItemDto> stored = TvManager.setLiveChannels(favorites, target);
        if (favorites) {
            mFavoriteCount = stored.size();
            if (allCount != null) mAllCount = allCount;
        } else {
            mAllCount = stored.size();
            mFavoriteCount = countFavorites(stored);
        }

        if (stored.isEmpty()) {
            boolean inGrid = focus == null || isInGrid(focus) || !focus.isShown();
            mAllChannels = stored;
            showNoChannels(inGrid);
            updateToggle();
            updateDays();
            return;
        }

        if (window == null) {
            // None of the rows shown is left (or none was shown, as in empty Favorites): this page anew
            boolean inGrid = focus == null || isInGrid(focus) || focus == mShowAllButton;
            mAllChannels = stored;
            mEmptyFavorites.setVisibility(View.GONE);
            mKeepHeaderFocus = !inGrid;
            mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
            updateToggle();
            updateDays();
            if (inGrid) mViewFavorites.requestFocus();
            int start = Math.max(0, Math.min(mCurrentDisplayChannelStartNdx, stored.size() - 1));
            displayChannels(mFilters.any() ? 0 : start, PAGE_SIZE);
            return;
        }

        // The rows shown now, and where focus and the screen are
        java.util.Map<UUID, View[]> rows = new java.util.HashMap<>();
        List<UUID> order = new ArrayList<>();
        UUID focusedId = null;
        boolean focusOnHeader = false;
        LocalDateTime focusTime = null;
        for (int i = 0; i < mChannels.getChildCount() && i < mProgramRows.getChildCount(); i++) {
            View header = mChannels.getChildAt(i);
            if (!(header instanceof GuideChannelHeader)) continue;
            View row = mProgramRows.getChildAt(i);
            UUID id = ((GuideChannelHeader) header).getChannel().getId();
            rows.put(id, new View[]{header, row});
            order.add(id);
            if (focus == header) {
                focusedId = id;
                focusOnHeader = true;
            } else if (focus != null && focus.getParent() == row) {
                focusedId = id;
                if (focus instanceof ProgramGridCell && ((ProgramGridCell) focus).getProgram() != null)
                    focusTime = ((ProgramGridCell) focus).getProgram().getStartDate();
            }
        }
        int scrollY = mProgramVScroller.getScrollY();
        UUID anchorId = focusedId;
        if (anchorId == null) {
            for (UUID id : order) {
                if (rows.get(id)[1].getBottom() > scrollY) {
                    anchorId = id;
                    break;
                }
            }
        }
        int anchorOffset = anchorId != null ? rows.get(anchorId)[1].getTop() - scrollY : 0;

        // The rows to show: kept ones updated, new ones built
        java.util.Set<UUID> oldIds = new java.util.HashSet<>(idsOf(old));
        List<UUID> desired = new ArrayList<>();
        java.util.Map<UUID, View[]> fresh = new java.util.HashMap<>();
        for (int i = window.getFirst(); i <= window.getLast(); i++) {
            BaseItemDto channel = stored.get(i);
            View[] pair = rows.get(channel.getId());
            if (pair != null) {
                ((GuideChannelHeader) pair[0]).update(channel);
                desired.add(channel.getId());
            } else if (!mFilters.any() || !oldIds.contains(channel.getId())) {
                LinearLayout row = getProgramRow(TvManager.getProgramsForChannel(channel.getId(), mFilters), channel.getId());
                if (row == null) continue; // nothing that passes the filters
                fresh.put(channel.getId(), new View[]{getChannelHeader(requireContext(), channel), row});
                desired.add(channel.getId());
            }
        }
        java.util.Set<UUID> desiredSet = new java.util.HashSet<>(desired);
        if (desired.isEmpty()) {
            // Only filtered-out channels are left in this page
            mAllChannels = stored;
            showNoChannels(false);
            updateToggle();
            return;
        }

        // Focus first moves off a row that goes, so it never falls anywhere else
        View keepFocus = focusedId != null ? focus : null;
        if (focusedId != null && !desiredSet.contains(focusedId)) {
            UUID next = GuideModel.nearestKeptRow(order, focusedId, desiredSet);
            if (next == null) next = desired.get(0);
            View[] pair = rows.containsKey(next) ? rows.get(next) : fresh.get(next);
            keepFocus = focusOnHeader ? pair[0] : cellAt((LinearLayout) pair[1], focusTime);
            if (keepFocus.isAttachedToWindow()) keepFocus.requestFocus();
            anchorId = next;
        } else if (anchorId != null && !desiredSet.contains(anchorId)) {
            anchorId = GuideModel.nearestKeptRow(order, anchorId, desiredSet);
        }

        // Paging rows go (they come back for the new page), then the rows that went
        for (int i = mChannels.getChildCount() - 1; i >= 0; i--) {
            if (!(mChannels.getChildAt(i) instanceof GuideChannelHeader)) {
                mChannels.removeViewAt(i);
                if (i < mProgramRows.getChildCount()) mProgramRows.removeViewAt(i);
            }
        }
        for (UUID id : order) {
            if (desiredSet.contains(id)) continue;
            View[] pair = rows.get(id);
            mChannels.removeView(pair[0]);
            mProgramRows.removeView(pair[1]);
        }
        // The rest in the new order; a view is moved only when out of place
        for (int i = 0; i < desired.size(); i++) {
            UUID id = desired.get(i);
            View[] pair = rows.containsKey(id) ? rows.get(id) : fresh.get(id);
            placeAt(mChannels, pair[0], i);
            placeAt(mProgramRows, pair[1], i);
            if (fresh.containsKey(id)) ((GuideChannelHeader) pair[0]).loadImage();
        }

        mAllChannels = stored;
        if (mFilters.any()) {
            mCurrentDisplayChannelStartNdx = 0;
            mCurrentDisplayChannelEndNdx = stored.size() - 1;
        } else {
            mCurrentDisplayChannelStartNdx = window.getFirst();
            mCurrentDisplayChannelEndNdx = window.getLast();
        }
        addTopPagingRow();
        addBottomPagingRow();
        relinkRows();
        updateChannelStatus(desired.size());
        mEmptyFavorites.setVisibility(View.GONE);
        updateToggle();
        updateNowLine();
        if (keepFocus != null && !keepFocus.hasFocus() && keepFocus.isAttachedToWindow()) keepFocus.requestFocus();

        // The screen stays put: the anchor row (focused, else the first in view) keeps its place
        if (anchorId != null) {
            View[] anchor = rows.containsKey(anchorId) ? rows.get(anchorId) : fresh.get(anchorId);
            final View anchorRow = anchor != null ? anchor[1] : null;
            final int offset = anchorOffset;
            if (anchorRow != null) {
                mProgramVScroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mProgramVScroller.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (anchorRow.getParent() == mProgramRows) mProgramVScroller.scrollTo(0, Math.max(0, anchorRow.getTop() - offset));
                    }
                });
                mProgramRows.requestLayout();
            }
        }
    }

    private boolean isInGrid(View view) {
        for (android.view.ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == mChannels || p == mProgramRows) return true;
        }
        return false;
    }

    private int currentCellId = 0;

    private GuideChannelHeader getChannelHeader(Context context, org.jellyfin.sdk.model.api.BaseItemDto channel){
        return new GuideChannelHeader(context, this, channel);
    }

    private LinearLayout getProgramRow(List<BaseItemDto> programs, UUID channelId) {

        LinearLayout programRow = new LinearLayout(requireContext());

        if (programs.size() == 0) {
            if (mFilters.any()) return null; // don't show rows with no program data

            int minutes = ((Long) ((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - mCurrentGuideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue();
            int slot = 0;
            do {
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        mCurrentGuideStart.plusMinutes(30l * slot),
                        mCurrentGuideEnd.plusMinutes(30l * (slot + 1))
                );

                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(30 * guideRowWidthPerMinPx, guideRowHeightPx));
                programRow.addView(cell);
                if (slot == 0)
                    cell.setFirst();
                if (slot == (minutes / 30) - 1)
                    cell.setLast();
                slot++;
            } while((30*slot) < minutes);
            return programRow;
        }

        LocalDateTime prevEnd = getCurrentLocalStartDate();
        for (BaseItemDto item : programs) {
            LocalDateTime start = item.getStartDate() != null ? item.getStartDate() : getCurrentLocalStartDate();
            if (start.isBefore(getCurrentLocalStartDate())) {
                start = getCurrentLocalStartDate();
            }

            if (start.isBefore(prevEnd)) {
                // Programmes that overlap (a guide merged from several sources): a later one
                // starts where the last ended, or isn't drawn when nothing of it is left
                if (item.getEndDate() == null || !item.getEndDate().isAfter(prevEnd)) continue;
                start = prevEnd;
            }

            if (start.isAfter(prevEnd)) {
                // fill empty time slot
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        prevEnd,
                        start
                );

                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(((Long) ((start.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
                if (prevEnd == mCurrentGuideStart) {
                    cell.setFirst();
                }
                programRow.addView(cell);
            }
            LocalDateTime end = item.getEndDate() != null ? item.getEndDate() : getCurrentLocalEndDate();
            if (end.isAfter(getCurrentLocalEndDate())) end = getCurrentLocalEndDate();
            prevEnd = end;
            Long duration = (end.toInstant(ZoneOffset.UTC).toEpochMilli() - start.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000;
            if (duration > 0) {
                ProgramGridCell program = new ProgramGridCell(requireContext(), this, item, false);
                program.setId(currentCellId++);
                program.setLayoutParams(new ViewGroup.LayoutParams(duration.intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
                if (start == mCurrentGuideStart) {
                    program.setFirst();
                }
                if (end == mCurrentGuideEnd) {
                    program.setLast();
                }

                programRow.addView(program);
            }
        }

        //If not at end of time period - fill in the rest
        if (prevEnd.isBefore(mCurrentGuideEnd)) {
            // fill empty time slot
            BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                    getContext(),
                    channelId,
                    prevEnd,
                    mCurrentGuideEnd
            );

            ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
            cell.setId(currentCellId++);
            cell.setLayoutParams(new ViewGroup.LayoutParams(((Long)((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
            programRow.addView(cell);
        }

        return programRow;
    }

    private void fillTimeLine(LocalDateTime start, int hours) {
        mCurrentGuideStart = start;
        mCurrentGuideStart = mCurrentGuideStart
                .withMinute(mCurrentGuideStart.getMinute())
                .withSecond(0)
                .withNano(0);

        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mCurrentGuideStart));
        mCurrentGuideEnd = mCurrentGuideStart
                .plusHours(hours);
        int oneHour = 60 * guideRowWidthPerMinPx;
        int halfHour = 30 * guideRowWidthPerMinPx;

        int interval = mCurrentGuideStart.getMinute() >= 30 ? 60 - mCurrentGuideStart.getMinute() : 30 - mCurrentGuideStart.getMinute();
        mTimeline.removeAllViews();

        LocalDateTime current = mCurrentGuideStart;
        while (current.isBefore(mCurrentGuideEnd)) {
            TextView time = new TextView(requireContext());
            time.setText(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(current));
            time.setTextSize(13);
            time.setTextColor(ContextCompat.getColor(requireContext(), R.color.guide_text_muted));
            time.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            time.setPadding(Utils.convertDpToPixel(requireContext(), 8), 0, 0, 0);
            time.setSingleLine(true);
            time.setWidth(interval != 60 ? ( interval < 15 ? 15 * guideRowWidthPerMinPx : interval * guideRowWidthPerMinPx) : oneHour);
            mTimeline.addView(time);
            current = current.plusMinutes(interval);
            //after first one, we always go on hours
            interval = interval < 30 ? 30 : 60;
        }
        updateDaySelection();
        updateNowLine();
    }

    /** The "now" line through the grid and its time on the timeline; hidden when now isn't in the hours shown. */
    private void updateNowLine() {
        if (mNowLine == null || mCurrentGuideEnd == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(mCurrentGuideStart) || !now.isBefore(mCurrentGuideEnd) || mProgramRows.getChildCount() == 0) {
            mNowLine.setVisibility(View.GONE);
            mNowPill.setVisibility(View.GONE);
            return;
        }
        final float x = java.time.Duration.between(mCurrentGuideStart, now).getSeconds() / 60f * guideRowWidthPerMinPx;
        mNowLine.setTranslationX(x);
        mNowLine.setVisibility(View.VISIBLE);
        mNowPill.setText(DateTimeExtensionsKt.getTimeFormatter(requireContext()).format(now));
        mNowPill.setVisibility(View.VISIBLE);
        mNowPill.post(() -> {
            float left = Math.max(0f, x - mNowPill.getWidth() / 2f);
            mNowPill.setTranslationX(left);
            // A half-hour label the pill covers steps aside, so the two never print over each other
            float gap = Utils.convertDpToPixel(requireContext(), 4);
            for (int i = 0; i < mTimeline.getChildCount(); i++) {
                if (!(mTimeline.getChildAt(i) instanceof TextView)) continue;
                TextView label = (TextView) mTimeline.getChildAt(i);
                float start = label.getLeft() + label.getPaddingLeft();
                float end = start + label.getPaint().measureText(label.getText().toString());
                label.setAlpha(end + gap > left && start - gap < left + mNowPill.getWidth() ? 0f : 1f);
            }
        });
    }

    /** Every minute: the now line moves, and programmes that started or ended change highlight. */
    private final Runnable mNowTick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            updateNowLine();
            for (int r = 0; r < mProgramRows.getChildCount(); r++) {
                View row = mProgramRows.getChildAt(r);
                if (!(row instanceof LinearLayout)) continue;
                LinearLayout cells = (LinearLayout) row;
                for (int c = 0; c < cells.getChildCount(); c++) {
                    View cell = cells.getChildAt(c);
                    if (cell instanceof ProgramGridCell) ((ProgramGridCell) cell).refreshNow();
                }
            }
            mHandler.postDelayed(this, 60_000);
        }
    };

    public LocalDateTime getCurrentLocalStartDate() { return mCurrentGuideStart; }
    public LocalDateTime getCurrentLocalEndDate() { return mCurrentGuideEnd; }

    private Runnable detailUpdateTask = new Runnable() {
        @Override
        public void run() {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            LiveTvGuideFragmentHelperKt.refreshSelectedProgram(LiveTvGuideFragment.this);
        }
    };

    /** The header's live preview follows focus: the channel on now after a rest, else the picture. */
    private void notePreviewFocus(RelativeLayout programView) {
        if (mPreview == null) return;
        // Picking channels for multi-view: its bar covers the box, and multi-view needs the connections
        if (mMultiView != null && mMultiView.isActive()) {
            mPreview.onFocus(null, GuidePreviewKind.PICTURE);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (programView instanceof ProgramGridCell) {
            BaseItemDto program = ((ProgramGridCell) programView).getProgram();
            if (program == null) {
                mPreview.onFocus(null, GuidePreviewKind.PICTURE);
                return;
            }
            boolean noData = program.getType() == org.jellyfin.sdk.model.api.BaseItemKind.FOLDER;
            mPreview.onFocus(program.getChannelId(), GuidePreviewKt.guidePreviewKind(program.getStartDate(), program.getEndDate(), now, noData));
        } else if (programView instanceof GuideChannelHeader) {
            // A channel's name: what it shows now
            mPreview.onFocus(((GuideChannelHeader) programView).getChannel().getId(), GuidePreviewKind.LIVE);
        } else {
            mPreview.onFocus(null, GuidePreviewKind.PICTURE);
        }
    }

    void detailUpdateInternal() {
        if (mSelectedProgram == null) return;

        mTitle.setText(mSelectedProgram.getName());
        mSummary.setText(mSelectedProgram.getOverview());
        boolean noData = mSelectedProgram.getType() == org.jellyfin.sdk.model.api.BaseItemKind.FOLDER;
        if (!noData && mSelectedProgram.getStartDate() != null && mSelectedProgram.getEndDate() != null) {
            String range = GuideModel.formatTimeRange(mSelectedProgram.getStartDate(), mSelectedProgram.getEndDate(), DateTimeExtensionsKt.getTimeFormatter(requireContext()));
            String episode = GuideModel.episodeText(mSelectedProgram.getParentIndexNumber(), mSelectedProgram.getIndexNumber(), mSelectedProgram.getEpisodeTitle(),
                    n -> getString(R.string.lbl_season_number, n), n -> getString(R.string.lbl_episode_number, n));
            mProgramTime.setText(GuideModel.detailLine(range, mSelectedProgram.getChannelName(), episode));
        } else {
            mProgramTime.setText("");
        }

        // Info row: only the badges (New, Repeat, Live, rating); time, channel and episode are on the line above
        InfoLayoutHelper.addInfoRow(requireContext(), mSelectedProgram, mInfoRow, false);
        for (int i = 0; i < mInfoRow.getChildCount(); i++) {
            if (mInfoRow.getChildAt(i) instanceof BaseItemInfoRowView) ((BaseItemInfoRowView) mInfoRow.getChildAt(i)).setProgramSubText(false);
        }

        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mSelectedProgram.getStartDate()));
        String url = imageHelper.getValue().getPrimaryImageUrl(mSelectedProgram, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT);
        // No picture of its own (short or ended programmes often have none): the channel's logo, never an empty box
        boolean logo = url == null;
        if (logo) url = channelImageUrl(mSelectedProgram.getChannelId());
        // A logo whole in the box (cropped to 16:9 it read "CN" cut in half); a programme's picture fills it
        mImage.setScaleType(logo ? android.widget.ImageView.ScaleType.FIT_CENTER : android.widget.ImageView.ScaleType.CENTER_CROP);
        mImage.load(url, null, ContextCompat.getDrawable(requireContext(), R.drawable.blank10x10), 0, 0);

    }

    /**
     * A row's programme on now, else the view itself: the guide opens on what plays (and its preview),
     * not on the row's first cell, which had ended up to half an hour before (seen on the Sony).
     */
    private View onNowIn(View view) {
        if (!(view instanceof ViewGroup) || view instanceof ProgramGridCell) return view;
        ViewGroup row = (ViewGroup) view;
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof ProgramGridCell)) continue;
            BaseItemDto program = ((ProgramGridCell) child).getProgram();
            if (program != null && program.getStartDate() != null && program.getEndDate() != null
                    && !program.getStartDate().isAfter(now) && program.getEndDate().isAfter(now)) return child;
        }
        return view;
    }

    /** The logo of a channel in the list shown, or null. */
    private String channelImageUrl(java.util.UUID channelId) {
        if (channelId == null || mChannels == null) return null;
        for (int i = 0; i < mChannels.getChildCount(); i++) {
            View child = mChannels.getChildAt(i);
            if (child instanceof GuideChannelHeader) {
                BaseItemDto channel = ((GuideChannelHeader) child).getChannel();
                if (channel != null && channelId.equals(channel.getId())) {
                    return imageHelper.getValue().getPrimaryImageUrl(channel, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT);
                }
            }
        }
        return null;
    }

    /**
     * The record icons of the programmes on screen, from Jellyfin's timers now (programme id, no
     * dashes -> [timer id, series timer id]): changed in place, nothing else redrawn.
     */
    int applyRecordMarks(java.util.Map<String, String[]> marks) {
        if (mProgramRows == null) return 0;
        return applyRecordMarks(mProgramRows, marks);
    }

    /** How many programme cells changed. */
    private static int applyRecordMarks(ViewGroup parent, java.util.Map<String, String[]> marks) {
        int changed = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ProgramGridCell) {
                ProgramGridCell cell = (ProgramGridCell) child;
                BaseItemDto p = cell.getProgram();
                if (p == null || p.getId() == null) continue;
                String[] m = marks.get(p.getId().toString().replace("-", "").toLowerCase(java.util.Locale.ROOT));
                String timer = m != null ? m[0] : null, series = m != null ? m[1] : null;
                if (!java.util.Objects.equals(timer, p.getTimerId()) || !java.util.Objects.equals(series, p.getSeriesTimerId())) {
                    cell.setRecSeriesTimer(series);
                    cell.setRecTimer(timer);
                    changed++;
                }
            } else if (child instanceof ViewGroup) {
                changed += applyRecordMarks((ViewGroup) child, marks);
            }
        }
        return changed;
    }

    public void setSelectedProgram(RelativeLayout programView) {
        mSelectedProgramView = programView;
        notePreviewFocus(programView);
        if (mSelectedProgramView instanceof ProgramGridCell) {
            mSelectedProgram = ((ProgramGridCell)mSelectedProgramView).getProgram();
            mHandler.removeCallbacks(detailUpdateTask);
            mHandler.postDelayed(detailUpdateTask, 500);
        } else if (mSelectedProgramView instanceof GuideChannelHeader) {
            for (int i = 0; i < mChannels.getChildCount(); i++) {
                if (mSelectedProgramView == mChannels.getChildAt(i)) {
                    LinearLayout programRow = (LinearLayout)mProgramRows.getChildAt(i);
                    if (programRow == null)
                        return;
                    for (int ii = 0; ii < programRow.getChildCount(); ii++) {
                        ProgramGridCell prog = (ProgramGridCell)programRow.getChildAt(ii);
                        if (prog.getProgram() != null && prog.getProgram().getStartDate().isBefore(LocalDateTime.now()) && prog.getProgram().getEndDate().isAfter(LocalDateTime.now())) {
                            mSelectedProgram = prog.getProgram();
                            if (mSelectedProgram != null) {
                                mHandler.removeCallbacks(detailUpdateTask);
                                mHandler.postDelayed(detailUpdateTask, 500);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }
}
